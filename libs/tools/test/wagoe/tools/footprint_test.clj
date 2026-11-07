(ns wagoe.tools.footprint-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.footprint :as fp]))

(def ^:private tree
  "Trimmed `clojure -Stree` output for a lib on core and platform-sql."
  (str "org.clojure/clojure 1.12.6\n"
       "  . org.clojure/spec.alpha 0.5.238\n"
       "wagoe/core /tmp/x/libs/core\n"
       "  X metosin/malli 0.20.1 :use-top\n"
       "metosin/malli 0.20.1\n"
       "wagoe/platform-sql /tmp/x/libs/platform-sql\n"
       "  X wagoe/core /tmp/x/libs/core :use-top\n"
       "  . com.github.seancorfield/next.jdbc 1.3.1118\n"
       "  . io.sentry/sentry-clj 8.34.247\n"
       "    . ring/ring-core 1.15.3\n"))

(deftest ^:unit parse-tree-keeps-only-included-entries
  (let [libs (map :lib (fp/parse-tree tree))]
    (is (= '[org.clojure/clojure org.clojure/spec.alpha wagoe/core metosin/malli
             wagoe/platform-sql com.github.seancorfield/next.jdbc
             io.sentry/sentry-clj ring/ring-core]
           libs))))

(deftest ^:unit footprint-splits-wagoe-from-external
  (let [declared {"jobs"         '[org.clojure/clojure metosin/malli]
                  "core"         '[metosin/malli]
                  "platform-sql" '[com.github.seancorfield/next.jdbc]}
        fp       (fp/footprint "jobs" (fp/parse-tree tree) #(str "wagoe-" %) declared)]
    (is (= "wagoe-jobs" (:artifact fp)))
    (is (= ["wagoe-core" "wagoe-platform-sql"] (:wagoe fp)))
    (is (= ["clojure" "malli" "next.jdbc"] (:declared fp)))
    (is (= 6 (:external-count fp)))
    (testing "Ring is present through sentry, so it is not reported absent"
      (is (= ["wagoe-platform" "Jetty" "Reitit" "Muuntaja"] (:not-included fp))))))

(deftest ^:unit footprint-says-nothing-when-the-http-server-is-there
  (let [with-jetty (str tree "org.eclipse.jetty/jetty-server 12.1.14\n"
                        "wagoe/platform /tmp/x/libs/platform\n")
        fp         (fp/footprint "admin" (fp/parse-tree with-jetty) identity (constantly []))]
    (is (= [] (:not-included fp)))))

(deftest ^:unit collapse-stems-folds-artifact-families
  (is (= ["jetty-*" "malli" "commons-net" "commons-lang3"]
         (fp/collapse-stems '[org.eclipse.jetty/jetty-http org.eclipse.jetty.ee9/jetty-ee9-nested
                              metosin/malli commons-net/commons-net
                              org.apache.commons/commons-lang3])))
  (testing "a family split across fi.metosin and metosin is still one family"
    (is (= ["reitit-*"]
           (fp/collapse-stems '[metosin/reitit-core fi.metosin/reitit-openapi])))))

(deftest ^:unit all-libs-reads-the-vector-and-skips-comments
  (is (= ["tools" "core" "jobs"]
         (fp/all-libs "(def x 1)\n(def all-libs\n  [\"tools\" ; \"gone\"\n   \"core\"\n   \"jobs\"])"))))
