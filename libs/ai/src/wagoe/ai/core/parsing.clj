(ns wagoe.ai.core.parsing
  "Pure response-parsing functions for AI outputs.

   FC/IS rule: no I/O here — receives raw AI response strings,
   returns parsed data or error maps."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; =============================================================================
;; JSON parsing
;; =============================================================================

(defn parse-json-response
  "Parse a JSON string from an AI response.

   Handles responses that may include markdown code fences or leading text.

   Args:
     text - raw AI response string

   Returns:
     Parsed map on success, {:error str :raw text} on failure."
  [text]
  (when text
    (let [cleaned (-> text
                      (str/replace #"(?s)```json\s*" "")
                      (str/replace #"```\s*$" "")
                      (str/trim))
          ;; Try to extract just the JSON object if there's surrounding text
          json-str (or (re-find #"(?s)\{.*\}" cleaned) cleaned)]
      ;; Coercive parse: external AI text → data; exception → error map
      (try
        (json/parse-string json-str true)
        (catch Exception _
          {:error "Failed to parse AI response as JSON" :raw text})))))

;; =============================================================================
;; Feature 1: NL Scaffolding response parsing
;; =============================================================================

(defn parse-module-spec
  "Parse an AI-generated module specification JSON into a normalised map.

   Expected AI output shape:
   {\"module-name\": \"product\", \"entity\": \"Product\",
    \"fields\": [{\"name\": \"price\", \"type\": \"decimal\", \"required\": true}],
    \"http\": true, \"web\": true}

   Returns:
     Normalised map with keyword keys and validated field specs,
     or {:error str} on failure."
  [response-text]
  (let [parsed (parse-json-response response-text)]
    (if (:error parsed)
      parsed
      (let [{:keys [module-name entity fields]} parsed
            valid-types #{"string" "text" "int" "decimal" "boolean" "email"
                          "uuid" "enum" "date" "json"}]
        (cond
          (not (string? module-name))
          {:error "AI response missing module-name"}

          (not (string? entity))
          {:error "AI response missing entity"}

          (not (sequential? fields))
          {:error "AI response fields must be an array"}

          :else
          {:module-name module-name
           :entity      entity
           :fields      (mapv (fn [f]
                                (let [t (let [raw (get f :type (get f "type" "string"))]
                                          (if (valid-types raw) raw "string"))]
                                  (cond-> {:name     (get f :name (get f "name"))
                                           :type     t
                                           :required (boolean (get f :required (get f "required" true)))
                                           :unique   (boolean (get f :unique (get f "unique" false)))}
                                    (= t "enum")
                                    (assoc :enum-values (get f :enum-values
                                                             (get f "enum-values"
                                                                  (get f :values
                                                                       (get f "values"))))))))
                              fields)
           :http        (boolean (get parsed :http true))
           :web         (boolean (get parsed :web true))})))))

(defn normalise-module-spec
  "Normalise a provider-parsed module spec map into canonical scaffolder shape.

   Args:
     module-spec - map parsed from provider JSON mode

   Returns:
     Normalised map with keyword keys and validated field specs,
     or {:error str} on failure."
  [module-spec]
  (parse-module-spec (json/generate-string module-spec)))

(defn module-spec->cli-args
  "Convert a parsed module spec map into CLI args for the scaffolder.

   Args:
     spec - normalised module spec map from parse-module-spec

   Returns:
     Vector of string args for wagoe.scaffolder.shell.cli-entry."
  [{:keys [module-name entity fields http web]}]
  (let [base       ["generate" "--module-name" module-name "--entity" entity]
        field-args (mapcat (fn [{:keys [name type required unique]}]
                             ["--field" (str/join ":"
                                                  (filter some? [name type
                                                                 (when required "required")
                                                                 (when unique "unique")]))])
                           fields)
        no-http    (when-not http ["--no-http"])
        no-web     (when-not web  ["--no-web"])]
    (vec (concat base field-args no-http no-web))))

;; =============================================================================
;; Feature 4: SQL Copilot response parsing
;; =============================================================================

(defn parse-sql-response
  "Parse an AI-generated SQL copilot response.

   Expected AI output:
   {\"honeysql\": \"...\", \"explanation\": \"...\", \"raw-sql\": \"...\"}

   Returns:
     Map with :honeysql :explanation :raw-sql,
     or {:error str} on failure."
  [response-text]
  (let [parsed (parse-json-response response-text)]
    (if (:error parsed)
      parsed
      {:honeysql    (or (:honeysql parsed) (get parsed :honeySQL) "")
       :explanation (or (:explanation parsed) "")
       :raw-sql     (or (:raw-sql parsed) (get parsed :rawSql ""))})))

;; =============================================================================
;; Feature 3: Test Generator response parsing
;; =============================================================================

(defn parse-generated-tests
  "Extract Clojure test code from an AI response.

   The AI should return raw Clojure, but may wrap in code fences.

   Args:
     response-text - raw AI response string

   Returns:
     Clean Clojure source string."
  [response-text]
  (when response-text
    (-> response-text
        (str/replace #"(?s)```clojure\s*" "")
        (str/replace #"```\s*$" "")
        (str/trim))))
