(ns wagoe.storage.readme-test
  "Every function a storage README calls exists. The Quick Start called
   `s3/create-adapter` and `storage/put`, which never did (BOU-694)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io PushbackReader StringReader]))

(def ^:private docs
  "From the repo root."
  ["libs/storage/README.md"
   "libs/storage/src/wagoe/storage/README.md"])

(defn- code-blocks [text]
  (map second (re-seq #"(?s)```clojure\n(.*?)```" text)))

(defn- forms [block]
  (binding [*read-eval*              false
            *default-data-reader-fn* (fn [_ v] v)]
    (let [r (PushbackReader. (StringReader. block))]
      (doall (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} r)))))))

(defn- aliases
  "alias -> namespace, from every `[ns :as alias]` in `form`."
  [form]
  (into {}
        (for [x (tree-seq coll? seq form)
              :when (and (vector? x) (symbol? (first x)) (= :as (second x)))]
          [(name (nth x 2)) (first x)])))

(defn- resolves?
  "Whether qualified symbol `s` names a var, or a static member of a class."
  [alias->ns s]
  (if-let [ns-sym (alias->ns (namespace s))]
    (try (require ns-sym)
         (some? (ns-resolve (find-ns ns-sym) (symbol (name s))))
         (catch java.io.FileNotFoundException _ false))
    (class? (resolve (symbol (namespace s))))))

(defn- unresolved
  "Qualified symbols in `text`'s code blocks that resolve to nothing. Aliases
   carry over from block to block, as in one REPL session."
  [text]
  (first
   (reduce (fn [[missing alias->ns] block]
             (let [fs        (forms block)
                   alias->ns (merge alias->ns (apply merge (map aliases fs)))]
               [(into missing
                      (for [s (tree-seq coll? seq fs)
                            ;; Alias-shaped only: `com.wagoe/wagoe-storage` is a
                            ;; dependency coordinate, not a call.
                            :when (and (qualified-symbol? s)
                                       (not (str/includes? (namespace s) "."))
                                       (not (resolves? alias->ns s)))]
                        s))
                alias->ns]))
           [[] {}]
           (code-blocks text))))

(deftest ^:unit readme-calls-functions-that-exist
  (doseq [doc docs]
    (testing doc
      (is (empty? (unresolved (slurp (io/file doc))))))))
