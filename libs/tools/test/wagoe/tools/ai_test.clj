(ns wagoe.tools.ai-test
  "BOU-272: every `bb ai` subcommand failed in a generated project.

     $ bb ai explain --file trace.txt
     Could not locate wagoe/ai/shell/cli_entry.clj on classpath

   `run-clojure!` shelled a plain `clojure -M -m wagoe.ai.shell.cli-entry`, but
   generated projects carry com.wagoe/wagoe-ai only in their :mcp alias, never
   in :deps — so the namespace was unreachable. That is five documented
   commands (explain, gen-tests, sql, docs, admin-entity), all of them rows in
   the shipped `wagoe` skill's decision table.

   The AI library was fine. The invocation could not find it, so these tests
   drive the command construction rather than the AI code — testing the library
   would have passed throughout."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.tools.ai :as ai]
            [wagoe.tools.scaffold :as scaffold]))

(deftest ^:unit ai-deps-pins-the-published-artifact
  (testing "without an override, the injected dep names wagoe-ai at a version"
    (let [deps (#'ai/ai-deps)]
      (is (str/includes? deps "com.wagoe/wagoe-ai"))
      (is (str/includes? deps ":mvn/version"))
      (is (not (str/includes? deps ":local/root"))))))

(deftest ^:unit ai-deps-carries-the-cli-parser
  ;; The first version of this fix injected wagoe-ai alone. That still failed,
  ;; because wagoe.ai.shell.cli-entry requires clojure.tools.cli and the
  ;; published wagoe-ai POM does not carry it:
  ;;
  ;;   Could not locate clojure/tools/cli__init.class …
  ;;
  ;; libs/ai now declares tools.cli, which fixes the POM from the next release.
  ;; Naming it here as well is what makes `bb ai` work against the version this
  ;; currently pins.
  (testing "tools.cli travels with the injected dependency"
    (doseq [[label deps] [["published pin" (#'ai/ai-deps nil)]
                          ["local root"    (#'ai/ai-deps "/tmp/ai")]]]
      (is (str/includes? deps "org.clojure/tools.cli")
          (str label ": cli-entry cannot load without tools.cli"))))

  (testing "and the library itself declares what it requires"
    ;; Injecting is a workaround for one call site; anything else resolving
    ;; wagoe-ai needs the POM to be honest.
    (let [ai-deps-edn (slurp "libs/ai/deps.edn")]
      (is (str/includes? ai-deps-edn "org.clojure/tools.cli")
          "libs/ai requires clojure.tools.cli, so it must declare it"))))

(deftest ^:unit ai-deps-honours-the-local-override
  (testing "a root swaps the pin for a local checkout"
    ;; Passing the root rather than stubbing ai-deps: an earlier version of this
    ;; test with-redef'd the function to return a canned string and then
    ;; asserted on that string, which exercised nothing.
    ;; Assert the wagoe-ai coordinate specifically. Checking that the whole
    ;; string lacks :mvn/version broke as soon as tools.cli joined it — and it
    ;; was testing the wrong thing anyway.
    (let [coord (get-in (read-string (#'ai/ai-deps "/tmp/ai"))
                        [:deps 'com.wagoe/wagoe-ai])]
      (is (= {:local/root "/tmp/ai"} coord))))

  (testing "no root falls back to the published pin"
    (let [coord (get-in (read-string (#'ai/ai-deps nil))
                        [:deps 'com.wagoe/wagoe-ai])]
      (is (contains? coord :mvn/version))
      (is (not (contains? coord :local/root))))))

(deftest ^:unit ai-deps-is-readable-edn
  (testing "the injected -Sdeps argument parses"
    ;; A malformed string here fails at the clojure CLI with a message about
    ;; EDN, several layers from the cause.
    (let [parsed (read-string (#'ai/ai-deps))]
      (is (map? parsed))
      (is (contains? (:deps parsed) 'com.wagoe/wagoe-ai)))))

(deftest ^:unit ai-version-matches-the-scaffolder-pin
  (testing "the release pins in libs/tools move together"
    ;; scaffold.clj and ai.clj both hardcode the suite version. They are bumped
    ;; by the same release step, so a mismatch means one was missed.
    (is (= @#'scaffold/scaffolder-version @#'ai/ai-version)
        "wagoe-ai and wagoe-scaffolder pins have drifted — both are bumped by the same release")))
