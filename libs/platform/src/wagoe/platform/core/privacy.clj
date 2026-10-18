(ns wagoe.platform.core.privacy
  "Pure halves of data export and retention: the export document, and which
  retention policies run with which cutoff."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [wagoe.core.utils.case-conversion :as case-conversion])
  (:import [java.time Duration Instant Period ZoneOffset]
           [java.time.format DateTimeParseException]))

(def export-format "wagoe.data-export/1")

(def subject-types #{:user :tenant})

(defn subject-error
  "Why `subject` cannot be exported, or nil."
  [{:keys [type id]}]
  (cond
    (not (contains? subject-types type))
    {:error {:type :validation-error
             :message (str "Export subject :type must be one of " (sort subject-types)
                           ", not " (pr-str type))}}

    (str/blank? (str id))
    {:error {:type :validation-error :message "Export subject needs an :id"}}))

(defn export-document
  "The export of `subject` from each exporter's data in `sections`
   ({exporter-id data}). An exporter that returned nil holds nothing for this
   type of subject and is left out."
  [subject sections ^Instant now]
  {:format      export-format
   :subject     (select-keys subject [:type :id])
   :exported-at now
   :sections    (into (sorted-map) (remove (comp nil? val)) sections)})

(defn- json-key
  "A keyword key camelCased with its namespace kept, at every depth: :user and
   :billing/user, or :id and :billing/id, must not become one key."
  [k]
  (let [n (case-conversion/kebab-case->camel-case-string (name k))]
    (if-let [ns (namespace k)] (str ns "/" n) n)))

(defn export-response
  "`doc`, an export from `IDataExport`, as a JSON download. Keys are
   camelCased and keep their namespace."
  [doc]
  {:status  200
   :headers {"Content-Type"        "application/json; charset=utf-8"
             "Content-Disposition" "attachment; filename=\"data-export.json\""
             "Cache-Control"       "no-store"}
   :body    (json/generate-string doc {:key-fn json-key})})

(defn parse-retain
  "An ISO-8601 period (\"P2Y\", \"P90D\") or duration (\"PT12H\") as a
   java.time Period or Duration, or {:error …}."
  [s]
  (let [s      (str s)
        parsed (or (try (Period/parse s) (catch DateTimeParseException _ nil))
                   (try (Duration/parse s) (catch DateTimeParseException _ nil)))
        ;; A zero period would delete everything at the next run.
        positive? (fn [p] (if (instance? Period p)
                            (not (or (.isZero ^Period p) (.isNegative ^Period p)))
                            (not (or (.isZero ^Duration p) (.isNegative ^Duration p)))))]
    (if (and parsed (positive? parsed))
      parsed
      {:error {:type :configuration-error
               :message (str "Retention " (pr-str s) " is not a positive ISO-8601 period such as \"P2Y\" or \"P90D\"")}})))

(defn cutoff
  "`now` less `retain`, a Period or Duration. Periods count calendar years and
   months in UTC."
  ^Instant [retain ^Instant now]
  (if (instance? Period retain)
    (.toInstant (.minus (.atZone now ZoneOffset/UTC) ^Period retain))
    (.minus now ^Duration retain)))

(defn retention-plan
  "{:plan {policy-id Period-or-Duration}} for the configured policies, or
   {:error …}.

   `configured` is {policy-id {:retain \"P2Y\"}}; `contributed` the ids modules
   contribute. A configured id nobody contributes is an error rather than a
   no-op: a misspelled policy would otherwise keep data for ever, silently."
  [configured contributed]
  (let [unknown (sort (remove (set contributed) (keys configured)))]
    (if (seq unknown)
      {:error {:type :configuration-error
               :message (str "Retention policies " (vec unknown) " are configured but no enabled module provides them."
                             " Provided: " (vec (sort contributed)))}}
      (reduce-kv (fn [acc id {:keys [retain]}]
                   (let [r (parse-retain retain)]
                     (if (:error r)
                       (reduced {:error (update (:error r) :message #(str id ": " %))})
                       (assoc-in acc [:plan id] r))))
                 {:plan (sorted-map)}
                 configured))))
