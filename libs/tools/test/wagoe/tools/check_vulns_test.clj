(ns wagoe.tools.check-vulns-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.check-vulns :as sut]))

(def ^:private m2 "/home/ci/.m2/repository/")

(deftest ^:unit a-jar-path-names-its-coordinates
  (is (= ['org.eclipse.jetty/jetty-util "12.1.8"]
         (sut/jar-coord m2 (str m2 "org/eclipse/jetty/jetty-util/12.1.8/jetty-util-12.1.8.jar"))))
  (testing "a classifier jar is the same library version"
    (is (= ['io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64 "18.6.0"]
           (sut/jar-coord m2 (str m2 "io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/18.6.0/"
                                  "embedded-postgres-binaries-linux-amd64-18.6.0-sources.jar")))))
  (testing "a jar outside the Maven repository has none"
    (is (nil? (sut/jar-coord m2 "/home/ci/.gitlibs/libs/x/y/abc/thing.jar")))))

(deftest ^:unit each-version-of-a-library-is-scanned-once
  (let [rounds (sut/synthetic-deps [['a/a "1.0"] ['a/a "2.0"] ['b/b "1.0"] ['a/a "1.0"]])]
    (is (= [{:deps {'a/a {:mvn/version "1.0"} 'b/b {:mvn/version "1.0"}}}
            {:deps {'a/a {:mvn/version "2.0"}}}]
           rounds))))

(deftest ^:unit only-versions-something-uses-are-reported
  ;; A synthetic file's own transitive resolution can pull a version no
  ;; project uses; reporting it would be a false alarm.
  (let [report [{:dependency "a/a" :mvn/version "1.0"
                 :vulnerabilities [{:advisory {:severity "HIGH"
                                               :identifiers [{:value "GHSA-1"} {:value "CVE-1"}]}
                                    :firstPatchedVersion {:identifier "1.1"}}]}
                {:dependency "z/z" :mvn/version "9.0"
                 :vulnerabilities [{:advisory {:severity "LOW" :identifiers [{:value "GHSA-2"}]}}]}]]
    (is (= [{:lib 'a/a :version "1.0" :id "GHSA-1" :cve "CVE-1" :severity :high :fixed "1.1"}]
           (sut/findings [report] {['a/a "1.0"] #{"(root)"}})))))

(deftest ^:unit many-files-sharing-an-alias-are-said-once
  (is (= ":build (4 files)"
         (sut/users-line #{"libs/a :build" "libs/b :build" "libs/c :build" "libs/d :build"})))
  (is (= "(root), libs/a; libs/b :test"
         (sut/users-line #{"(root)" "libs/a" "libs/b :test"}))))
