(ns wagoe.tools.relocate-test
  "Relocation stubs published under the retired org.boundary-app group. The
   value at stake is that every retired artifact gets exactly one stub, at a
   version that actually becomes the group's newest, pointing at a coordinate
   that exists — a wrong id or version here is permanent, since Clojars never
   lets a release be replaced."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [wagoe.tools.deploy :as deploy]
            [wagoe.tools.relocate :as relocate]))

(defn- parse-xml
  "Parse a pom string into an element tree, so assertions run against real XML
   rather than substring matches that would pass on malformed output.
   clojure.data.xml rather than javax.xml — these tests run under Babashka."
  [s]
  (xml/parse-str s))

(defn- elements
  "Depth-first seq of every element in the tree rooted at `el`."
  [el]
  (when (map? el)
    (cons el (mapcat elements (:content el)))))

(defn- tag-text
  "Text of the first element named `tag` under `el`, or nil. Tag names are
   compared on the local name so the pom's default namespace is irrelevant."
  [el tag]
  (some (fn [e]
          (when (= tag (name (:tag e)))
            (str/join (filter string? (:content e)))))
        (elements el)))

(defn- find-tag
  "The first element named `tag` under `el`, or nil."
  [el tag]
  (some (fn [e] (when (= tag (name (:tag e))) e)) (elements el)))

(deftest ^:unit old-artifact-name-test
  (testing "the wagoe- prefix is swapped for boundary-, not appended"
    (is (= "boundary-core" (relocate/old-artifact-name "core")))
    (is (= "boundary-platform" (relocate/old-artifact-name "platform"))))

  (testing "libs whose dir already starts with wagoe- map to a single boundary- prefix"
    ;; libs/wagoe-cli publishes com.wagoe/wagoe-cli and was org.boundary-app/
    ;; boundary-cli — not boundary-wagoe-cli. The id is derived from the real
    ;; coordinate in build.clj, so this holds without a special case.
    (is (= "boundary-cli" (relocate/old-artifact-name "wagoe-cli")))
    (is (= "boundary-mcp" (relocate/old-artifact-name "wagoe-mcp"))))

  (testing "no derived id keeps a wagoe- segment"
    (doseq [lib deploy/all-libs]
      (let [old (relocate/old-artifact-name lib)]
        (is (str/starts-with? old "boundary-")
            (str lib " should map into the boundary- namespace"))
        (is (not (str/includes? old "wagoe"))
            (str lib " -> " old " still mentions wagoe"))))))

(deftest ^:unit stub-version-outranks-published-versions-test
  (testing "the stub version sorts above every version already in the old group"
    ;; The highest published version is 1.0.1-alpha-42 — NOT the 1.0.0-beta-1
    ;; that Clojars reports as latest_release, which tracks push order. A stub
    ;; that does not outrank the alphas never becomes the group's newest, and
    ;; the deprecation notice never surfaces on the Clojars page.
    (is (= "1.0.1" relocate/stub-version))
    (is (not (str/includes? relocate/stub-version "-"))
        "a qualifier would sort BELOW the release of the same version")))

(deftest ^:unit stub-pom-test
  (let [pom (relocate/stub-pom "core")
        el  (parse-xml pom)]

    (testing "the stub is published under the retired group and old artifact id"
      (is (= "org.boundary-app" (tag-text el "groupId")))
      (is (= "boundary-core" (tag-text el "artifactId")))
      (is (= "1.0.1" (tag-text el "version"))))

    (testing "packaging is pom — a relocation carries no code"
      (is (= "pom" (tag-text el "packaging"))))

    (testing "the relocation points at the live com.wagoe coordinate"
      (let [rel (find-tag el "relocation")]
        (is (some? rel) "distributionManagement/relocation must be present")
        (is (= "com.wagoe" (tag-text rel "groupId")))
        (is (= "wagoe-core" (tag-text rel "artifactId")))
        (is (= (deploy/read-version "core") (tag-text rel "version"))
            "target version must track build.clj, not a hardcoded literal")))

    (testing "the description carries the deprecation notice"
      ;; This text is the only part of the exercise that reaches deps.edn users:
      ;; tools.deps ignores <relocation>, so the Clojars page is the channel.
      (let [desc (tag-text el "description")]
        (is (str/starts-with? desc "DEPRECATED"))
        (is (str/includes? desc "com.wagoe/wagoe-core"))
        (is (str/includes? desc "wagoe.org"))))))

(deftest ^:unit stub-pom-covers-every-lib-test
  (testing "every lib in the deploy registry produces a parseable, distinct stub"
    (let [poms (map relocate/stub-pom deploy/all-libs)
          ids  (map #(tag-text (parse-xml %) "artifactId") poms)]
      (is (= (count deploy/all-libs) (count poms)))
      (is (= (count ids) (count (set ids)))
          "two libs must never collide on one retired artifact id")
      (doseq [pom poms]
        (is (some? (parse-xml pom)) "every stub must be well-formed XML")))))

(deftest ^:unit pom-path-test
  (testing "the filename matches the Maven convention deps-deploy derives coords from"
    (is (= "target/relocation/boundary-core-1.0.1.pom" (relocate/pom-path "core")))
    (is (str/ends-with? (relocate/pom-path "platform") ".pom"))))
