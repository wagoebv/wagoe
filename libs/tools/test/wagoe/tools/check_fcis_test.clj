(ns wagoe.tools.check-fcis-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [wagoe.tools.check-fcis :as fcis]))

(deftest ^:unit core-source-paths-includes-libs
  (testing "FC/IS scanner includes libs/*/src/wagoe/*/core files"
    (let [scanned (map str (fcis/core-source-paths))]
      (is (some #(re-find #"libs/.+/src/wagoe/.+/core/" %) scanned)
          "expected at least one libs/<lib>/src/wagoe/<lib>/core/ file"))))

(deftest ^:unit core-source-paths-includes-test-support
  (testing "FC/IS scanner includes src/wagoe/test_support/core"
    (let [scanned (map str (fcis/core-source-paths))]
      (is (some #(re-find #"test_support/core" %) scanned)
          "expected src/wagoe/test_support/core.clj to be scanned"))))

(deftest ^:unit core-source-paths-includes-app-layout
  (testing "FC/IS scanner includes src/wagoe/<module>/core in an app layout (no libs/)"
    ;; A project scaffolded with `boundary new` has no libs/ tree — its modules
    ;; live under src/wagoe/<module>/core/. Build a fixture and scan it via the
    ;; explicit-root arity (the default arity reads user.dir = monorepo root).
    (let [tmp      (io/file (System/getProperty "java.io.tmpdir")
                            (str "fcis-app-" (System/currentTimeMillis)))
          core-clj (io/file tmp "src" "wagoe" "invoice" "core" "invoice.clj")]
      (try
        (io/make-parents core-clj)
        (spit core-clj "(ns wagoe.invoice.core.invoice)\n(defn total [xs] (reduce + xs))\n")
        (let [scanned (map str (fcis/core-source-paths tmp))]
          (is (some #(re-find #"src/wagoe/invoice/core/invoice\.clj" %) scanned)
              "expected the scaffolded app module's core file to be scanned"))
        (finally
          (doseq [f (reverse (file-seq tmp))] (.delete f)))))))

(deftest ^:unit scan-fq-calls-detects-runtime-nondeterminism
  (testing "FC/IS scanner flags runtime-dependent identity, time, environment, and timezone access in core"
    (let [violations (#'fcis/scan-fq-calls
                      "fake-file.clj"
                      (str "(ns example.core)\n"
                           "(java.util.UUID/randomUUID)\n"
                           "(java.time.Instant/now)\n"
                           "(java.time.LocalDate/now)\n"
                           "(java.time.ZoneId/systemDefault)\n"
                           "(java.lang.System/currentTimeMillis)\n"
                           "(java.lang.System/getProperty \"environment\")\n"
                           "(java.lang.ProcessHandle/current)\n"))
          symbols (set (map :symbol violations))]
      (is (contains? symbols "java.util.UUID/randomUUID"))
      (is (contains? symbols "java.time.Instant/now"))
      (is (contains? symbols "java.time.LocalDate/now"))
      (is (contains? symbols "java.time.ZoneId/systemDefault"))
      (is (contains? symbols "java.lang.System/currentTimeMillis"))
      (is (contains? symbols "java.lang.System/getProperty"))
      (is (contains? symbols "java.lang.ProcessHandle/current")))))

(deftest ^:unit scan-simple-static-calls-detects-imported-runtime-nondeterminism
  (testing "FC/IS scanner flags imported and implicit simple-class runtime access in core"
    (let [imports ["java.time.Instant"
                   "java.time.LocalDate"
                   "java.time.ZoneId"
                   "java.util.UUID"]
          violations (#'fcis/scan-simple-static-calls
                      "fake-file.clj"
                      (str "(ns example.core\n"
                           "  (:import (java.time Instant LocalDate ZoneId)\n"
                           "           (java.util UUID)))\n"
                           "(Instant/now)\n"
                           "(LocalDate/now)\n"
                           "(ZoneId/systemDefault)\n"
                           "(UUID/randomUUID)\n"
                           "(System/currentTimeMillis)\n"
                           "(ProcessHandle/current)\n")
                      imports)
          symbols (set (map :symbol violations))]
      (is (contains? symbols "java.time.Instant/now"))
      (is (contains? symbols "java.time.LocalDate/now"))
      (is (contains? symbols "java.time.ZoneId/systemDefault"))
      (is (contains? symbols "java.util.UUID/randomUUID"))
      (is (contains? symbols "java.lang.System/currentTimeMillis"))
      (is (contains? symbols "java.lang.ProcessHandle/current")))))

;; ---------------------------------------------------------------------------
;; Throw / mutable-state ban + escape hatch
;; ---------------------------------------------------------------------------

(defn- check-src
  "Write `src` to a temp core file and run check-file with `config`."
  ([src] (check-src src {}))
  ([src config]
   (let [f (io/file (System/getProperty "java.io.tmpdir")
                    (str "fcis-impurity-" (System/currentTimeMillis) "-" (hash src))
                    "core" "sample.clj")]
     (try
       (io/make-parents f)
       (spit f src)
       (fcis/check-file f config)
       (finally
         (doseq [x (reverse (file-seq (.getParentFile f)))] (.delete x)))))))

(deftest ^:unit flags-throw-in-core
  (testing "(throw ...) in a core namespace is a :throw violation"
    (let [vs (check-src "(ns ex.core)\n(defn f [x] (when-not x (throw (ex-info \"bad\" {:type :validation-error}))))\n")]
      (is (some #(= :throw (:kind %)) vs)))))

(deftest ^:unit allow-throw-metadata-exempts
  (testing "^:wagoe/allow-throw ns metadata suppresses the throw violation"
    (let [vs (check-src "(ns ^:wagoe/allow-throw ex.core)\n(defn f [x] (throw (ex-info \"bad\" {:type :x})))\n")]
      (is (not (some #(= :throw (:kind %)) vs))))))

(deftest ^:unit allow-throw-config-exempts
  (testing ".boundary/check-fcis.edn :allow-throw allowlist suppresses by ns name"
    (let [vs (check-src "(ns ex.core)\n(defn f [] (throw (ex-info \"bad\" {})))\n"
                        {:allow-throw #{"ex.core"}})]
      (is (not (some #(= :throw (:kind %)) vs))))))

(deftest ^:unit flags-mutable-state-in-core
  (testing "defonce/atom/swap!/reset! in a core namespace are :mutable-state violations"
    (let [vs (check-src (str "(ns ex.core)\n"
                             "(defonce reg (atom {}))\n"
                             "(defn add! [k v] (swap! reg assoc k v))\n"
                             "(defn clear! [] (reset! reg {}))\n"))
          labels (set (map :req (filter #(= :mutable-state (:kind %)) vs)))]
      (is (contains? labels "defonce"))
      (is (contains? labels "atom"))
      (is (contains? labels "swap!"))
      (is (contains? labels "reset!")))))

(deftest ^:unit allow-mutable-state-metadata-exempts
  (testing "^:wagoe/allow-mutable-state ns metadata suppresses mutable-state violations"
    (let [vs (check-src "(ns ^:wagoe/allow-mutable-state ex.core)\n(defonce reg (atom {}))\n")]
      (is (not (some #(= :mutable-state (:kind %)) vs))))))

(deftest ^:unit ignores-throw-inside-string-literal
  (testing "a (throw ...) inside a string literal (e.g. a code generator) is not flagged"
    (let [vs (check-src "(ns ex.core)\n(defn gen [] (format \"(defn f [] (throw (ex-info \\\"x\\\" {})))\"))\n")]
      (is (not (some #(= :throw (:kind %)) vs))))))

(deftest ^:unit flags-extended-mutation-primitives
  (testing "volatile!/vswap!/alter-var-root/ref-set/add-watch are flagged as mutable state"
    (let [vs (check-src (str "(ns ex.core)\n"
                             "(defn a [] (volatile! 0))\n"
                             "(defn b [v] (vswap! v inc))\n"
                             "(defn c [] (alter-var-root #'x (constantly 1)))\n"
                             "(defn d [r] (ref-set r 1))\n"
                             "(defn e [r] (add-watch r :k (fn [& _])))\n"))
          labels (set (map :req (filter #(= :mutable-state (:kind %)) vs)))]
      (is (contains? labels "volatile!"))
      (is (contains? labels "vswap!"))
      (is (contains? labels "alter-var-root"))
      (is (contains? labels "ref-set"))
      (is (contains? labels "add-watch")))))

(deftest ^:unit does-not-flag-double-bang-symbols
  (testing "a fn named swap!! / reset!! is not a false positive for swap!/reset!"
    (let [vs (check-src "(ns ex.core)\n(defn f [a] (swap!! a inc))\n(defn g [a] (reset!! a 0))\n")]
      (is (not (some #(= :mutable-state (:kind %)) vs))))))
