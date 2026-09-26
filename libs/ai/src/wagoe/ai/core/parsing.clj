(ns wagoe.ai.core.parsing
  "Pure response-parsing functions for AI outputs.

   FC/IS rule: no I/O here — receives raw AI response strings,
   returns parsed data or error maps."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; =============================================================================
;; Code fences
;; =============================================================================

(def ^:private fence-opener #"^```([\w.+-]*)[ \t]*")
(def ^:private fence-closer #"^```[ \t]*")

(def ^:private string-aware-langs
  ;; Languages whose string literals may hold a line that looks like a fence.
  #{"" "clojure" "clj" "cljc" "cljs" "edn" "json"})

(def ^:private clojure-langs #{"clojure" "clj" "cljc" "cljs"})

(defn- in-string-after?
  "Whether a Clojure/EDN/JSON reader is inside a string at the end of `line`."
  [in-string? ^String line]
  (loop [i 0
         s in-string?]
    (if (>= i (count line))
      s
      (let [c (.charAt line i)]
        (cond
          (= c \\)                  (recur (+ i 2) s)
          (= c \")                  (recur (inc i) (not s))
          (and (not s) (= c \;))    s
          :else                     (recur (inc i) s))))))

(defn- close-block [{:keys [info lines]}]
  {:info info :body (str/join "\n" lines)})

(defn code-blocks
  "The fenced blocks in `text`, in order, as {:info str :body str}.

   Fences start a line, as in Markdown. A closer is a bare ``` outside a string
   literal, so a fence quoted inside a generated test does not end the block.
   A block the output limit cut off runs to the end of the text."
  [text]
  (let [{:keys [blocks open]}
        (reduce (fn [{:keys [open] :as acc} line]
                  (cond
                    (nil? open)
                    (if-let [[_ info] (re-matches fence-opener line)]
                      (assoc acc :open {:info (str/lower-case info) :lines [] :in-string? false})
                      acc)

                    (and (not (:in-string? open)) (re-matches fence-closer line))
                    (-> acc (update :blocks conj (close-block open)) (assoc :open nil))

                    :else
                    (assoc acc :open (-> open
                                         (update :lines conj line)
                                         (assoc :in-string?
                                                (and (contains? string-aware-langs (:info open))
                                                     (in-string-after? (:in-string? open) line)))))))
                {:blocks [] :open nil}
                (str/split-lines text))]
    (cond-> blocks open (conj (close-block open)))))

(defn strip-code-fence
  "The code in `text` without fences or the prose around them. Text with no
   fence is returned trimmed.

   With `langs`, the blocks tagged with one of them are joined, so an answer
   split over two ```clojure blocks stays whole and a ```bash example ahead of
   the ```json one is skipped. Failing that, untagged blocks, then the first.

   Every parser here reads through this. Each used to strip its own fence —
   ```json in one, ```clojure in another — and an ```edn answer from the
   admin-entity generator matched neither (BOU-493)."
  ([text] (strip-code-fence text nil))
  ([text langs]
   (when text
     (let [blocks (code-blocks text)
           picked (or (seq (filter #(contains? langs (:info %)) blocks))
                      (when langs (seq (filter #(= "" (:info %)) blocks)))
                      (take 1 blocks))]
       (str/trim (if (seq picked)
                   (str/join "\n\n" (map :body picked))
                   text))))))

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
    ;; Coercive parse: external AI text → data; exception → nil. The raw text
    ;; is the fallback for JSON the fences led us away from.
    (let [parse   (fn [s] (try (json/parse-string s true) (catch Exception _ nil)))
          cleaned (strip-code-fence text #{"json"})]
      (or (parse (or (re-find #"(?s)\{.*\}" cleaned) cleaned))
          (some-> (re-find #"(?s)\{.*\}" text) parse)
          {:error "Failed to parse AI response as JSON" :raw text}))))

;; =============================================================================
;; Feature 1: NL Scaffolding response parsing
;; =============================================================================

(def ^:private valid-field-types
  #{"string" "text" "int" "decimal" "boolean" "email" "uuid" "enum" "date" "json"})

(defn- normalise-field [f]
  (let [t (let [raw (get f :type (get f "type" "string"))]
            (if (valid-field-types raw) raw "string"))]
    (cond-> {:name     (get f :name (get f "name"))
             :type     t
             :required (boolean (get f :required (get f "required" true)))
             :unique   (boolean (get f :unique (get f "unique" false)))}
      (= t "enum")
      (assoc :enum-values (get f :enum-values
                               (get f "enum-values"
                                    (get f :values
                                         (get f "values"))))))))

(defn parse-module-spec
  "Parse an AI-generated module specification JSON into a normalised map.

   Expected AI output shape, one entity or several (BOU-497):
   {\"module-name\": \"billing\",
    \"entities\": [{\"name\": \"Invoice\", \"fields\": [...]},
                  {\"name\": \"InvoiceLineItem\", \"belongs-to\": \"Invoice\",
                   \"fields\": [...]}],
    \"http\": true, \"web\": true}

   The older singular shape — `entity` plus `fields` — is still read.

   Returns:
     {:module-name :entities [{:name :fields :belongs-to?}] :http :web}, plus
     :entity and :fields for the first entity, or {:error str} on failure."
  [response-text]
  (let [parsed (parse-json-response response-text)]
    (if (:error parsed)
      parsed
      (let [{:keys [module-name entity fields entities]} parsed
            entities (if (sequential? entities)
                       entities
                       [{:name entity :fields fields}])]
        (cond
          (not (string? module-name))
          {:error "AI response missing module-name"}

          (or (empty? entities) (not (every? (comp string? :name) entities)))
          {:error "AI response missing entity"}

          (not (every? (comp sequential? :fields) entities))
          {:error "AI response fields must be an array"}

          :else
          (let [entities (mapv (fn [{:keys [name fields belongs-to]}]
                                 (cond-> {:name name :fields (mapv normalise-field fields)}
                                   (string? belongs-to) (assoc :belongs-to belongs-to)))
                               entities)]
            {:module-name module-name
             :entity      (:name (first entities))
             :fields      (:fields (first entities))
             :entities    entities
             :http        (boolean (get parsed :http true))
             :web         (boolean (get parsed :web true))}))))))

(defn normalise-module-spec
  "Normalise a provider-parsed module spec map into canonical scaffolder shape.

   Args:
     module-spec - map parsed from provider JSON mode

   Returns:
     Normalised map with keyword keys and validated field specs,
     or {:error str} on failure."
  [module-spec]
  (parse-module-spec (json/generate-string module-spec)))

(defn- enum-value->str
  "An enum value as the CLI spells it. Provider JSON gives strings, a
   hand-built spec gives keywords."
  [v]
  (if (keyword? v) (name v) (str v)))

(defn module-spec->cli-args
  "Convert a parsed module spec map into CLI args for the scaffolder.

   Args:
     spec - normalised module spec map from parse-module-spec

   Returns:
     Vector of string args for wagoe.scaffolder.shell.cli-entry."
  [{:keys [module-name entity fields http web]}]
  (let [base       ["generate" "--module-name" module-name "--entity" entity]
        ;; `values=` carries the enum's values through. Without it the spec said
        ;; only `status:enum` and the generated schema was `[:enum]`, which
        ;; matches nothing — the values were parsed and then dropped (BOU-447).
        field-args (mapcat (fn [{:keys [name type required unique enum-values]}]
                             ["--field" (str/join ":"
                                                  (filter some? [name type
                                                                 (when (seq enum-values)
                                                                   (str "values="
                                                                        (str/join "," (map enum-value->str enum-values))))
                                                                 (when required "required")
                                                                 (when unique "unique")]))])
                           fields)
        no-http    (when-not http ["--no-http"])
        no-web     (when-not web  ["--no-web"])]
    (vec (concat base field-args no-http no-web))))

(defn normalise-setup-spec
  "The seven setup choices, with a default for every one the provider omitted.

   Keys are read in both forms. `parse-json-response` keywordizes, and the
   caller read string keys, so every answer fell through to its default — the
   description reached the provider, came back parsed, and was then thrown away
   (BOU-401). Returns string keys: this is written straight out as JSON.

   The database default is sqlite, not postgresql: an absent choice must still
   yield a project that boots without a database server (BOU-228)."
  [data]
  (let [choice (fn [k fallback]
                 (let [v (get data (keyword k) (get data k))]
                   (if (some? v) v fallback)))]
    {"project-name" (choice "project-name" "my-app")
     "database"     (choice "database" "sqlite")
     "ai-provider"  (choice "ai-provider" "none")
     "payment"      (choice "payment" "none")
     "cache"        (choice "cache" "none")
     "email"        (choice "email" "none")
     "admin-ui"     (boolean (choice "admin-ui" true))}))

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
  (strip-code-fence response-text clojure-langs))

;; =============================================================================
;; Feature 6: Admin Entity Generator response parsing
;; =============================================================================

(defn parse-admin-entity
  "Parse an admin entity EDN answer.

   Returns:
     {:text edn-string :entity-name str}, where :text is the EDN without fence
     or prose, or {:error str :raw-text str} naming what was actually wrong."
  [response-text]
  (let [edn-text (strip-code-fence response-text (conj clojure-langs "edn"))
        parsed   (try
                   {:value (edn/read-string edn-text)}
                   (catch Exception e
                     {:error (str "AI response is not valid EDN: " (ex-message e))}))
        value    (:value parsed)]
    (cond
      (:error parsed)
      (assoc parsed :raw-text response-text)

      (not (and (map? value) (keyword? (ffirst value))))
      {:error    "AI response is EDN but not a map keyed by entity name"
       :raw-text response-text}

      :else
      {:text edn-text :entity-name (name (ffirst value))})))

(defn ensure-test-metadata
  "Tag every unmetadata'd `deftest` in `test-source` with `^:<test-type>`.

   Kaocha selects suites on this metadata, so a test namespace without it runs
   in no suite — present in the file, absent from every run. The system prompt
   asks the model for it; measured against two of the framework's own core
   namespaces, it produced 15 deftests and tagged none of them. The test type
   is already derived in Clojure by `context/determine-test-type`, so this is a
   fact the code knows and does not need to ask for.

   A deftest that already carries metadata is left alone — the model may have
   tagged it more precisely than the path-based default, and a second tag would
   be noise at best.

   Args:
     test-source - generated Clojure source string
     test-type   - :unit, :integration or :contract

   Returns:
     The source with metadata applied, or nil for nil input."
  [test-source test-type]
  (when test-source
    (str/replace test-source
                 ;; `deftest` must be followed by a name, not by `^` (already
                 ;; tagged) — hence the negative lookahead. Anchored to a line
                 ;; start so `deftest` inside a docstring or a comment about
                 ;; deftest is untouched.
                 #"(?m)^(\s*\(deftest\s+)(?!\^)"
                 (str "$1^" test-type " "))))

(defn strip-noncode
  "`source` with string, regex, character-literal and comment content blanked.

   Both checks below need to reason about structure, and both were wrong
   without this: a `(` inside a docstring is not an open paren, and an
   `edn/read` inside a test's string literal is not a use of the `edn` alias.
   The second was measured — a generated namespace that merely *quoted*
   `(edn/read d)` in a test string had `[clojure.edn :as edn]` added to its
   requires, which clj-kondo then flags as unused.

   Blanked, not removed: offsets and line structure are preserved, so a caller
   can still relate a position back to the original.

   `wagoe.tools.parsing/strip-comments-and-strings` does the same job for the
   quality gates. It is not shared: libs/tools is Babashka-only and declares no
   Wagoe dependency, libs/ai declares none either, and a common home would mean
   one of them taking on the other's dependency tree. Two small copies beat
   that, but they are copies — a fix here is worth checking against there.

   Args:
     source - Clojure source string

   Returns:
     Source of the same length with non-code characters replaced by spaces
     (newlines kept), or nil for nil input."
  [source]
  (when source
    (let [out (StringBuilder. (count source))]
      (loop [i     0
             state :code]
        (if (>= i (count source))
          (str out)
          (let [c (.charAt ^String source i)]
            (case state
              :string  (cond
                         (= c \\) (do (.append out "  ") (recur (+ i 2) :string))
                         (= c \") (do (.append out \") (recur (inc i) :code))
                         :else    (do (.append out (if (= c \newline) \newline \space))
                                      (recur (inc i) :string)))
              :comment (if (= c \newline)
                         (do (.append out \newline) (recur (inc i) :code))
                         (do (.append out \space) (recur (inc i) :comment)))
              :code    (cond
                         (= c \") (do (.append out \") (recur (inc i) :string))
                         (= c \;) (do (.append out \space) (recur (inc i) :comment))
                         ;; Character literal: `\(`, `\;` and `\"` are each one
                         ;; token, so the following character is consumed
                         ;; without ever being read as code.
                         (= c \\) (do (.append out "  ") (recur (+ i 2) :code))
                         :else    (do (.append out c) (recur (inc i) :code))))))))))

(def ^:private closer->opener
  {\) \( , \] \[ , \} \{})

(defn delimiter-balance
  "Count of still-open delimiters in `source`, or nil if they do not nest.

   A generated namespace that hits the model's output limit is cut off
   mid-form — measured, one stopped at `result (s` — and writing that file
   produces `EOF while reading` rather than anything the caller can use.
   Counting open delimiters is the cheap, provider-free way to see it: a
   complete file ends at 0.

   A count alone is not enough. `(is (= 1 1])` nets to zero and is not
   readable, so the kind of each opener is tracked and a closer that does not
   match the innermost one is rejected. Depth-only, all three of `(is (= 1 1])`,
   `([)]` and `(deftest a (is [1 2)])` were reported complete.

   Args:
     source - Clojure source string

   Returns:
     Number of unclosed delimiters (0 means balanced), or nil when a closer
     appears with nothing open or with the wrong opener — which is damage
     rather than truncation, and equally unreadable."
  [source]
  (loop [chars (seq (strip-noncode source))
         open  '()]
    (if-not chars
      (count open)
      (let [c (first chars)
            r (next chars)]
        (cond
          (#{\( \[ \{} c)
          (recur r (conj open c))

          (contains? closer->opener c)
          (when (= (first open) (closer->opener c))
            (recur r (rest open)))

          :else (recur r open))))))

(defn truncated?
  "Whether `source` looks cut off mid-form rather than complete.

   Args:
     source - Clojure source string

   Returns:
     true when delimiters are left open (or a stray closer appears)."
  [source]
  (not= 0 (delimiter-balance source)))

(def standard-aliases
  "Aliases the generator uses in test bodies but routinely forgets to require.

   Restricted to clojure.* namespaces with one conventional alias each, because
   the repair below infers the namespace from the alias — which is only sound
   where the mapping is unambiguous. An alias outside this map is left to fail
   at compile time rather than guessed at."
  {"str"    "clojure.string"
   "set"    "clojure.set"
   "walk"   "clojure.walk"
   "edn"    "clojure.edn"
   "io"     "clojure.java.io"
   "pprint" "clojure.pprint"})

(defn require-clause
  "The text of the namespace form's `:require` clause, or nil.

   Whether a namespace is required cannot be answered by searching the whole
   file: `clojure.set/subset?` in a test body contains the text `clojure.set`,
   so a whole-file search calls it required and the file then dies at load with
   `ClassNotFoundException: clojure.set`. Measured — that is exactly how a
   generated namespace failed.

   Args:
     source - Clojure source string

   Returns:
     Substring covering `(:require ...)` inclusive, or nil when there is none."
  [source]
  (when-let [code (strip-noncode source)]
    (when-let [start (str/index-of code "(:require")]
      (loop [i     start
             depth 0]
        (if (>= i (count code))
          nil                       ; unterminated — treat as no clause
          (let [c (.charAt ^String code i)]
            (cond
              (#{\( \[ \{} c) (recur (inc i) (inc depth))
              (#{\) \] \}} c) (if (= 1 depth)
                                (subs source start (inc i))
                                (recur (inc i) (dec depth)))
              :else           (recur (inc i) depth))))))))

(defn- alias-bound?
  "Whether `require-text` binds `alias` with `:as` or `:as-alias`.

   The question a `str/join` in the body actually asks. Whether
   `clojure.string` is required is a different question and answering that one
   instead was wrong: `[clojure.string :as string]` in the ns form with
   `str/join` in the body left the alias unbound and the namespace looking
   satisfied, so nothing was repaired and the file failed to load."
  [require-text alias]
  (boolean (re-find (re-pattern (str ":as(?:-alias)?\\s+"
                                     (java.util.regex.Pattern/quote alias)
                                     "(?![\\w.*+!?<>=_-])"))
                    require-text)))

(defn missing-standard-requires
  "Standard namespaces `test-source` uses without requiring.

   Two shapes, both measured against the framework's own namespaces: an alias
   use (`str/join` with no alias bound to `str`, which fails with
   `No such namespace: str`) and a fully qualified use
   (`clojure.set/subset?` with no require, which fails with
   `ClassNotFoundException: clojure.set`). They are checked separately because
   satisfying one does not satisfy the other — a namespace can be required
   under a different alias, and an alias can be bound to a different namespace.

   Args:
     test-source - generated Clojure source string

   Returns:
     Sorted seq of {:alias str :namespace str :aliased? bool}, empty when
     nothing is missing. `:aliased?` distinguishes the two shapes so the repair
     can add `[ns :as alias]` only where an alias is actually needed."
  [test-source]
  (if-not test-source
    []
    ;; Usage is a question about code, so it is not asked of string or comment
    ;; content — see `strip-noncode`. Requiredness is a question about the ns
    ;; form alone — see `require-clause`.
    (let [code     (strip-noncode test-source)
          required (or (require-clause test-source) "")
          used?    (fn [name']
                     (re-find (re-pattern (str "(?<![\\w.:-])"
                                               (java.util.regex.Pattern/quote name')
                                               "/"))
                              code))]
      (->> standard-aliases
           (keep (fn [[alias ns-name]]
                   ;; An alias already bound to something else is left alone:
                   ;; the reference resolves, and a second binding of the same
                   ;; alias is a compile error rather than a repair.
                   (let [need-alias? (and (used? alias)
                                          (not (alias-bound? required alias)))
                         need-ns?    (and (used? ns-name)
                                          (not (str/includes? required ns-name)))]
                     (when (or need-alias? need-ns?)
                       {:alias alias :namespace ns-name
                        :aliased? (boolean need-alias?)}))))
           (sort-by :alias)))))

(defn ensure-standard-requires
  "Add `:require` entries for standard namespaces `test-source` uses but omits.

   Only touches a namespace form that already has a `:require` clause; a
   generated test namespace always does, and synthesising one would mean
   guessing where it belongs.

   Args:
     test-source - generated Clojure source string

   Returns:
     The source with the missing requires added, or nil for nil input."
  [test-source]
  (when test-source
    (let [missing (missing-standard-requires test-source)]
      (if (or (empty? missing) (nil? (require-clause test-source)))
        test-source
        (let [entries (->> missing
                           (map (fn [{:keys [alias namespace aliased?]}]
                                  ;; No alias where none is used: `:as` on a
                                  ;; namespace only ever called by its full
                                  ;; name is dead, and clj-kondo can say so.
                                  (if aliased?
                                    (str "[" namespace " :as " alias "]")
                                    (str "[" namespace "]"))))
                           (str/join "\n            "))]
          (str/replace-first test-source
                             #"\(:require\s+"
                             (str "(:require " entries "\n            ")))))))
