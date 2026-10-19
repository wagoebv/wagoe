(ns wagoe.ai.shell.test-runner
  "Runs a generated test namespace in the child JVM `test-check/run-errors`
   starts. Kept out of the gen-tests process: the code is the model's, unread."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [malli.core :as m]
            [malli.error :as me]))

(defn fixture-errors
  "One error per var in `ns-sym` tagged ^{:schema s} whose value does not
   match `s`. A test that passes on invented data proves nothing."
  [ns-sym]
  (vec (for [[n v] (sort-by key (ns-interns ns-sym))
             :let [s (:schema (meta v))]
             :when s
             :let [problem (try (some-> (m/explain s @v) me/humanize)
                                (catch Exception e (str "not a schema: " (ex-message e))))]
             :when problem]
         (str "Fixture " n " does not match its :schema: " (pr-str problem)))))

(defn- test-errors [ns-sym]
  (let [out (java.io.StringWriter.)
        {:keys [test fail error]} (binding [t/*test-out* out] (t/run-tests ns-sym))]
    (cond
      (zero? test)          ["The namespace defines no tests"]
      (pos? (+ fail error)) (into [(str "Running it: " fail " failures, " error " errors")]
                                  (->> (str/split-lines (str out))
                                       (remove str/blank?)
                                       (take 60)))
      :else                 [])))

(defn errors
  "Load `file`, which defines `ns-sym`, and check it: fixtures first, then
   the tests. Returns [line], empty when clean."
  [file ns-sym]
  (try
    (load-file file)
    (let [bad (fixture-errors ns-sym)]
      (if (seq bad) bad (test-errors ns-sym)))
    (catch Throwable e
      [(str "It does not load: " (ex-message e)
            (some->> (ex-cause e) ex-message (str " — ")))])))

(defn -main
  "Writes `errors` for `file` to `result-file` as EDN, then exits: the tests
   may leave non-daemon threads running."
  [file ns-name result-file]
  (spit result-file (pr-str (errors file (symbol ns-name))))
  (System/exit 0))
