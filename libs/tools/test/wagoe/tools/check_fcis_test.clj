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
    ;; A project scaffolded with `wagoe new` has no libs/ tree — its modules
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
  (testing ".wagoe/check-fcis.edn :allow-throw allowlist suppresses by ns name"
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

;; ===========================================================================
;; BOU-301: the gate no longer depends on formatting, and requires are an
;; allowlist rather than a list of the libraries someone thought of
;; ===========================================================================

(deftest ^:unit a-newline-between-the-paren-and-the-symbol-does-not-hide-a-call
  ;; The scan was line-based, so `(\n throw …)` matched nothing and the whole
  ;; enforcement could be switched off by pressing return. Both of these are
  ;; the same call to the reader.
  (testing "a split (throw ...)"
    (let [vs (check-src "(ns ex.core)\n(defn f [x]\n  (\n   throw (ex-info \"bad\" {})))\n")]
      (is (some #(= :throw (:kind %)) vs))))

  (testing "a split (swap! ...)"
    (let [vs (check-src "(ns ex.core)\n(def r nil)\n(defn f []\n  (\n   swap! r inc))\n")]
      (is (some #(= :mutable-state (:kind %)) vs))))

  (testing "and the violation is reported at the symbol, not at the paren"
    (let [vs (check-src "(ns ex.core)\n(defn f [x]\n  (\n   throw (ex-info \"bad\" {})))\n")]
      (is (= 4 (:line (first (filter #(= :throw (:kind %)) vs))))))))

(deftest ^:unit mutation-handed-to-a-higher-order-function-is-still-mutation
  ;; `(apply swap! r [inc])` mutates exactly as much as `(swap! r inc)`.
  (testing "apply"
    (let [vs (check-src "(ns ex.core)\n(def r nil)\n(defn f [] (apply swap! r [inc]))\n")]
      (is (some #(= :mutable-state (:kind %)) vs))
      (is (= "swap!" (:req (first (filter #(= :mutable-state (:kind %)) vs))))
          "the report must name the mutating call, not the wrapper")))

  (testing "partial"
    (let [vs (check-src "(ns ex.core)\n(def r nil)\n(def f (partial reset! r))\n")]
      (is (some #(= :mutable-state (:kind %)) vs)))))

(deftest ^:unit a-require-nobody-thought-of-is-a-violation
  ;; The denylist had eleven entries. These are the libraries the ticket named:
  ;; each one lets a core namespace shell out, hold process state, or reach the
  ;; network, and each one passed clean.
  (doseq [lib ["babashka.process" "babashka.fs" "clojure.core.async"
               "hato.client" "aleph.http" "cognitect.aws.client.api"]]
    (testing lib
      (let [vs (check-src (str "(ns ex.core\n  (:require [" lib " :as x]))\n"))]
        (is (some #(and (= :require (:kind %)) (= lib (:req %))) vs)
            (str lib " must not be requirable from a core namespace"))))))

(deftest ^:unit the-pure-libraries-a-core-namespace-really-uses-still-pass
  ;; An allowlist that fails the repo's own honest code is not usable. These
  ;; are taken from what core namespaces require today.
  (doseq [lib ["clojure.string" "clojure.set" "clojure.walk" "clojure.edn"
               "malli.core" "malli.error" "hiccup2.core" "cheshire.core"
               "buddy.core.codecs" "honey.sql" "rewrite-clj.zip"
               "integrant.core"
               "wagoe.user.schema" "wagoe.i18n.ports"
               "wagoe.shared.ui.core.icons" "wagoe.core.utils.case-conversion"]]
    (testing lib
      (let [vs (check-src (str "(ns ex.core\n  (:require [" lib " :as x]))\n"))]
        (is (not (some #(= :require (:kind %)) vs))
            (str lib " is pure and must remain requirable"))))))

(deftest ^:unit clojure-core-async-is-not-mistaken-for-a-modules-own-core
  ;; The pattern that lets a module require its own `…core…` namespaces reads
  ;; `clojure.core.async` as one of them unless it excludes clojure. Written
  ;; without that exclusion, the allowlist let back in the one library the
  ;; ticket named as the gap.
  (let [vs (check-src "(ns ex.core\n  (:require [clojure.core.async :as a]))\n")]
    (is (some #(= :require (:kind %)) vs))))

(deftest ^:unit a-shell-namespace-is-still-refused
  ;; The rule the whole gate exists for, now enforced by not being on the list
  ;; rather than by a pattern.
  (let [vs (check-src "(ns ex.core\n  (:require [wagoe.user.shell.service :as s]))\n")]
    (is (some #(= :require (:kind %)) vs))))

(deftest ^:unit allow-require-exempts-one-namespace-and-one-require
  (let [config {:allow-require {"ex.core" #{"clojure.test"}}}]
    (testing "the named pair is allowed"
      (is (not (some #(= :require (:kind %))
                     (check-src "(ns ex.core\n  (:require [clojure.test :as t]))\n" config)))))

    (testing "but it does not exempt the namespace from anything else"
      (is (some #(= :require (:kind %))
                (check-src "(ns ex.core\n  (:require [babashka.process :as p]))\n" config))))))

(deftest ^:unit an-allowlist-entry-without-a-why-is-refused
  ;; An exemption nobody can explain cannot be burnt down — the same rule
  ;; check-ports applies to its own allowlist.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"no :why"
       (fcis/allowed-requires {:allow-require [{:ns 'ex.core :require 'clojure.test}]})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"no :require"
       (fcis/allowed-requires {:allow-require [{:ns 'ex.core :why "reasons"}]})))
  (testing "a complete entry is accepted"
    (is (= {"ex.core" #{"clojure.test"}}
           (fcis/allowed-requires {:allow-require [{:ns 'ex.core :require 'clojure.test
                                                    :why "it is a testing DSL"}]})))))
