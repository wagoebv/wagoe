(ns wagoe.storage.schema-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [wagoe.storage.schema :as schema])
  (:import [java.io PushbackReader StringReader]))

(def ^:private docs
  "Every document that shows a `:wagoe/storage` config, from the repo root."
  ["libs/storage/README.md"
   "libs/storage/src/wagoe/storage/README.md"
   "docs/modules/libraries/pages/storage.adoc"])

(defn- code-blocks [text]
  (map second (re-seq #"(?s)(?:```clojure|\[source,clojure\]\n----)\n(.*?)(?:```|----)" text)))

(defn- forms [block]
  (let [r (PushbackReader. (StringReader. block))]
    (take-while #(not= ::eof %)
                (repeatedly #(edn/read {:eof ::eof :default (fn [_ v] (str v))} r)))))

(defn- storage-configs
  "Each value a document gives `:wagoe/storage`."
  [text]
  (for [block (code-blocks text)
        :when (re-find #":wagoe/storage \{" block)
        [k v] (partition 2 (forms block))
        :when (= :wagoe/storage k)]
    v))

(deftest ^:unit documented-configs-are-the-shape-module-wiring-reads
  ;; The README showed {:adapter :local :local {...}}, which nothing reads (BOU-646).
  (doseq [doc docs]
    (let [configs (storage-configs (slurp (io/file doc)))]
      (testing doc
        (is (seq configs) "documents a config")
        (doseq [c configs]
          (is (m/validate schema/StorageConfig c) (pr-str (m/explain schema/StorageConfig c))))))))

(deftest ^:unit storage-config-rejects-what-is-not-read
  (is (m/validate schema/StorageConfig {:provider :local :root "uploads"}))
  (is (m/validate schema/StorageConfig {:base-path "uploads"}) "local is the default")
  (is (not (m/validate schema/StorageConfig {:adapter :local :local {:base-path "uploads"}})))
  (is (not (m/validate schema/StorageConfig {:provider :s3 :bucket "b"})) "S3 needs a region")
  (is (not (m/validate schema/StorageConfig {:provider :dropbox :root "x"}))))
