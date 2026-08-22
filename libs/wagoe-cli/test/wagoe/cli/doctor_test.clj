(ns wagoe.cli.doctor-test
  "The parts of `wagoe doctor` that decide what the reader is told.

   Running the stages needs a project and four subprocesses; what is worth
   pinning here is the reasoning between them — which result becomes the one
   next action, and what happens when a stage says something this cannot read."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.cli.doctor :as sut]))

(deftest ^:unit the-results-are-read-from-the-line-that-is-ours
  ;; bb prints its own noise on some systems — JAVA_TOOL_OPTIONS on this
  ;; machine, task banners elsewhere — so treating all of stdout as EDN throws
  ;; and the stage reads as unreadable when it was fine.
  (let [out (str "Picked up JAVA_TOOL_OPTIONS: -Djava.awt.headless=true\n"
                 "{:section :config, :summary {:passed 1, :warnings 0, :errors 0}, "
                 ":results [{:id :providers, :level :pass, :msg \"ok\"}]}\n")]
    (is (= [{:id :providers :level :pass :msg "ok"}]
           (sut/parse-stage-output out))))

  (testing "output with nothing of ours in it reads as nothing, not as empty"
    ;; nil and [] mean different things to the caller: nil is "could not tell",
    ;; [] is "told me, and there was nothing".
    (is (nil? (sut/parse-stage-output "Wagoe Config Doctor — dev\n  ✓ providers\n")))
    (is (nil? (sut/parse-stage-output "")))
    (is (nil? (sut/parse-stage-output nil))))

  (testing "a truncated map is unreadable rather than an exception"
    (is (nil? (sut/parse-stage-output "{:section :config, :results [{:id"))))

  (testing "the last of ours wins"
    ;; `bb doctor --env all` prints one map per profile.
    (let [out (str "{:section :config, :results [{:id :a, :level :pass, :msg \"1\"}]}\n"
                   "{:section :config, :results [{:id :b, :level :error, :msg \"2\"}]}\n")]
      (is (= [:b] (map :id (sut/parse-stage-output out)))))))

(deftest ^:unit the-next-action-is-the-earliest-worst-thing
  (let [results [{:level :pass  :msg "env ok"}
                 {:level :warn  :msg "first warning"}
                 {:level :error :msg "first error"}
                 {:level :error :msg "second error"}
                 {:level :warn  :msg "second warning"}]]

    (testing "an error outranks a warning that came before it"
      (is (= "first error" (:msg (sut/next-action results)))))

    (testing "and the earliest error wins, because the later ones follow from it"
      ;; Stages run from the most fundamental outwards. A broken config makes
      ;; every command that boots the system fail for the same one reason, and
      ;; telling the reader about the third symptom wastes the trip.
      (is (not= "second error" (:msg (sut/next-action results)))))

    (testing "warnings only when nothing errored"
      (is (= "first warning"
             (:msg (sut/next-action (remove #(= :error (:level %)) results))))))

    (testing "and nothing to say when everything passed"
      (is (nil? (sut/next-action [{:level :pass :msg "fine"}])))
      (is (nil? (sut/next-action []))))))

(deftest ^:unit an-old-projects-tools-are-reported-once-not-once-per-stage
  ;; Every stage predating `--edn` fails to be read for the same reason. Three
  ;; copies of the same paragraph is the noise this command exists to remove —
  ;; and the reader has one thing to do, not three.
  (let [results [{:level :pass :msg "commands ok"}
                 {:level :warn :id :environment :unreadable? true :msg "env: could not read"}
                 {:level :warn :id :config      :unreadable? true :msg "config: could not read"}
                 {:level :warn :id :project     :unreadable? true :msg "project: could not read"}]
        out     (sut/collapse-unreadable results)]

    (is (= 2 (count out)))
    (is (= :tools-version (:id (sut/next-action out))))

    (testing "and it names the version to move to, not just the problem"
      (is (str/includes? (:fix (sut/next-action out)) "wagoe-tools")))

    (testing "naming which checks were lost, so the reader can run them by hand"
      (let [msg (:msg (sut/next-action out))]
        (is (every? #(str/includes? msg %) ["environment" "config" "project"]))))

    (testing "the passing stage is untouched"
      (is (some #(= "commands ok" (:msg %)) out))))

  (testing "one unreadable stage stays as it is — there is nothing to collapse"
    (let [results [{:level :warn :id :config :unreadable? true :msg "config: could not read"}]]
      (is (= results (sut/collapse-unreadable results)))))

  (testing "and a healthy run is not rewritten"
    (let [results [{:level :pass :msg "a"} {:level :pass :msg "b"}]]
      (is (= results (sut/collapse-unreadable results))))))

(deftest ^:unit the-blocking-stages-are-the-ones-later-stages-depend-on
  ;; Not a style choice: with no JVM nothing runs, and with an unparseable
  ;; config every command that boots the system fails for that one reason. A
  ;; run that carried on would bury its own finding under three more screens.
  (let [by-id (into {} (map (juxt :id identity)) sut/stages)]
    (is (:blocking? (:environment by-id)))
    (is (:blocking? (:config by-id)))
    (is (not (:blocking? (:commands by-id))))
    (is (not (:blocking? (:project by-id))))

    (testing "environment comes before config, and both before the rest"
      (is (= [:environment :config :commands :project] (map :id sut/stages))))

    (testing "every stage that claims to speak EDN asks for it"
      (doseq [{:keys [id task edn?]} sut/stages]
        (is (= edn? (boolean (some #{"--edn"} task)))
            (str id " disagrees with itself about whether it returns data"))))))

(deftest ^:unit a-plain-babashka-project-is-not-a-wagoe-project
  ;; The guard used to be "is there a bb.edn". Any Babashka project has one and
  ;; none of the tasks, so the run reported four stages it "could not read the
  ;; results" of, each suggesting a command that does not exist there — four
  ;; wrong answers to a question with an obvious right one.
  (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "wagoe-doctor-test-" (System/nanoTime)))]
    (try
      (.mkdirs tmp)
      (testing "no bb.edn at all"
        (is (not (sut/wagoe-project? tmp))))

      (testing "a bb.edn that is not ours"
        (spit (io/file tmp "bb.edn") "{:tasks {hello {:task (println \"hi\")}}}")
        (is (not (sut/wagoe-project? tmp))))

      (testing "and one that is"
        (spit (io/file tmp "bb.edn")
              "{:deps {com.wagoe/wagoe-tools {:mvn/version \"1.0.0-beta-6\"}}}")
        (is (sut/wagoe-project? tmp)))
      (finally
        (doseq [f (reverse (file-seq tmp))] (.delete f))))))
