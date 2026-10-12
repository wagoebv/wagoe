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

(deftest ^:unit no-library-appears-twice-in-a-batch
  ;; GitHub says an advisory affects *some* listed package@version; with one
  ;; version per package in a request, the reply names which.
  (is (= [{'a/a "1.0" 'b/b "1.0"} {'a/a "2.0"}]
         (sut/batches [['a/a "1.0"] ['a/a "2.0"] ['b/b "1.0"] ['a/a "1.0"]] 100)))
  (testing "and a large round is split"
    (is (= [{'a/a "1"} {'b/b "1"}] (sut/batches [['a/a "1"] ['b/b "1"]] 1)))))

(deftest ^:unit an-advisory-is-traced-to-the-version-asked-about
  (let [batch {'org.postgresql/postgresql "42.7.10" 'x/y "1.0"}
        reply [{:ghsa_id "GHSA-1" :cve_id "CVE-1" :severity "high"
                :vulnerabilities [{:package {:ecosystem "maven" :name "org.postgresql:postgresql"}
                                   :first_patched_version "42.7.11"}]}
               {:ghsa_id "GHSA-2" :severity "low" :withdrawn_at "2026-01-01T00:00:00Z"
                :vulnerabilities [{:package {:name "x:y"} :first_patched_version "1.1"}]}]]
    (is (= [{:lib 'org.postgresql/postgresql :version "42.7.10" :id "GHSA-1" :cve "CVE-1"
             :severity :high :fixed "42.7.11"}]
           (sut/findings batch reply))
        "a withdrawn advisory is not a finding")))

(deftest ^:unit many-files-sharing-an-alias-are-said-once
  (is (= ":build (4 files)"
         (sut/users-line #{"libs/a :build" "libs/b :build" "libs/c :build" "libs/d :build"})))
  (is (= "(root), libs/a; libs/b :test"
         (sut/users-line #{"(root)" "libs/a" "libs/b :test"}))))

(deftest ^:unit an-alias-excuses-only-what-it-alone-uses
  (let [allow [{:alias ":build" :why "deploy tooling"}]
        used  {['x/x "1"] #{"libs/a :build" "libs/b :build"}
               ['y/y "1"] #{"libs/a :build" "(root)"}}]
    (is (sut/allowed? allow used {:lib 'x/x :version "1" :id "GHSA-1"}))
    (is (not (sut/allowed? allow used {:lib 'y/y :version "1" :id "GHSA-1"}))
        "the same version also ships, so it is not excused"))
  (testing "an id excuses the advisory wherever it is"
    (is (sut/allowed? [{:id "GHSA-1" :why "x"}] {['y/y "1"] #{"(root)"}}
                      {:lib 'y/y :version "1" :id "GHSA-1"}))))
