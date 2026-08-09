(ns wagoe.tools.dev-test
  "Tests for the smoke-check guard on system-booting aliases.

   `clojure -M:repl-clj` then `(go)` is the documented REPL workflow and it
   died with `ClassNotFoundException: org.h2.Driver`, for every profile,
   because the alias carried no JDBC driver. Nothing caught it: smoke-check
   verified the alias *existed*, which it did."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.dev :as dev]))

(def ^:private monorepo-shape
  "Drivers per alias, app main is wagoe.main — the framework repository."
  {:deps    {'org.clojure/clojure {:mvn/version "1.12.5"}}
   :aliases {:repl-clj  {:extra-paths ["dev" "dev/repl"]
                         :extra-deps  {'nrepl/nrepl {:mvn/version "1.7.0"}
                                       'com.h2database/h2 {:mvn/version "2.4.240"}}
                         :main-opts   ["-m" "nrepl.cmdline" "--port" "7888"]}
             :run       {:extra-deps {'com.h2database/h2 {:mvn/version "2.4.240"}}
                         :main-opts  ["-m" "wagoe.main"]}
             :clj-kondo {:replace-deps {'clj-kondo/clj-kondo {:mvn/version "2026.04.15"}}
                         :main-opts    ["-m" "clj-kondo.main"]}
             :outdated  {:extra-deps {'olical/depot {:mvn/version "2.4.1"}}
                         :main-opts  ["-m" "depot.outdated.main"]}
             :repl-cljs {:extra-deps {'cider/piggieback {:mvn/version "0.6.1"}}
                         :main-opts  ["-m" "nrepl.cmdline" "--middleware"
                                      "[cider.piggieback/wrap-cljs-repl]"]}}})

(def ^:private generated-project-shape
  "Drivers in base :deps, app main is <project>.main — `wagoe new` output."
  {:deps    {'org.clojure/clojure   {:mvn/version "1.12.5"}
             'org.xerial/sqlite-jdbc {:mvn/version "3.53.0.0"}}
   :aliases {:repl {:extra-paths ["dev"]
                    :main-opts   ["-m" "nrepl.cmdline"]}
             :run  {:main-opts ["-m" "demoproj.main"]}}})

(deftest ^:unit booting-aliases-are-recognised-in-both-layouts
  (testing "the monorepo's REPL and run aliases boot the system"
    (is (= [:repl-clj :run]
           (mapv first (filter (partial dev/boots-the-system? "wagoe.main")
                               (:aliases monorepo-shape))))))

  (testing "a generated project's differently-named aliases are recognised too"
    ;; The first draft matched `wagoe.main` and `dev/repl` literally, found
    ;; nothing in a generated project, and failed `bb quickstart` in CI.
    (is (= [:repl :run]
           (mapv first (filter (partial dev/boots-the-system? "demoproj.main")
                               (:aliases generated-project-shape))))))

  (testing "a tool that happens to have a .main namespace is not the app"
    ;; Matching any `-m …\.main` caught clj-kondo.main and
    ;; depot.outdated.main, neither of which opens a database.
    (let [booting (set (map first (filter (partial dev/boots-the-system? "wagoe.main")
                                          (:aliases monorepo-shape))))]
      (is (not (contains? booting :clj-kondo)))
      (is (not (contains? booting :outdated)))))

  (testing "a ClojureScript nREPL needs no driver"
    (is (not (dev/boots-the-system? "wagoe.main"
                                    [:repl-cljs (get-in monorepo-shape [:aliases :repl-cljs])])))))

(deftest ^:unit drivers-must-be-reachable-not-necessarily-per-alias
  (testing "a driver in base :deps satisfies every alias"
    (is (true? (dev/drivers-on-base-classpath? generated-project-shape)))
    (is (nil? (dev/aliases-missing-drivers generated-project-shape "demoproj.main"))
        "asking per alias is the wrong question when the base classpath has one"))

  (testing "without a base driver, each booting alias must declare one"
    (is (false? (dev/drivers-on-base-classpath? monorepo-shape)))
    (is (empty? (dev/aliases-missing-drivers monorepo-shape "wagoe.main"))))

  (testing "the alias that shipped broken is named"
    (let [broken (update-in monorepo-shape [:aliases :repl-clj :extra-deps]
                            dissoc 'com.h2database/h2)]
      (is (= [:repl-clj] (dev/aliases-missing-drivers broken "wagoe.main"))))))

(deftest ^:unit app-main-ns-is-read-from-the-source-tree
  (testing "the framework's own main namespace is found"
    ;; Guards the derivation itself: if this returns nil the check silently
    ;; falls back to recognising only nREPL aliases.
    (is (= "wagoe.main" (dev/app-main-ns "."))))

  (testing "a directory with no src/*/main.clj has none"
    (is (nil? (dev/app-main-ns "libs/tools")))))
