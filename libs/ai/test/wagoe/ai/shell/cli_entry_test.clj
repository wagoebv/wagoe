(ns wagoe.ai.shell.cli-entry-test
  "BOU-279 and BOU-280: the AI CLI dropped two failure modes it was built to
   handle.

   Every subcommand destructured `parse-opts` as `{:keys [options arguments]}`
   and never read `:errors`, so an unknown option vanished — `--fil` for
   `--file` was dropped, its value became a positional argument nothing reads,
   and the command complained about missing input. The message pointed away
   from the mistake, which was on the same line.

   And every subcommand branches on `(:error result)`, but the message it
   printed came straight from the provider: `Connection refused`. A fresh
   project has no provider configured, `make-service-from-env` falls back to
   Ollama on localhost, and nothing is listening — so a first `bb ai` command
   reported a refused connection to a service the user never chose."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.cli :as cli]
            [wagoe.ai.shell.cli-entry :as sut]
            [wagoe.ai.shell.service :as svc]))

(defn- cli-entry-source
  "The CLI source, from the repo root or from libs/ai.

   `bb test:all` runs the main suite from the root and the standalone lib suite
   from libs/ai, so a single relative path works in one and throws in the other.
   Throws rather than returning nil: a wiring assertion that quietly passes when
   it cannot find the file it inspects is the failure it exists to catch."
  []
  (let [candidates ["libs/ai/src/wagoe/ai/shell/cli_entry.clj"
                    "src/wagoe/ai/shell/cli_entry.clj"]]
    (or (some #(when (.exists (io/file %)) (slurp %)) candidates)
        (throw (ex-info "cli_entry.clj not found — cannot verify the wiring"
                        {:cwd (System/getProperty "user.dir") :tried candidates})))))

(def ^:private no-env
  "No provider variables set. Passed explicitly: reading the ambient
   environment made these assertions depend on whether the developer happened to
   have OLLAMA_URL exported, which is the very fault the function explains."
  {"OLLAMA_URL" nil "OPENAI_BASE_URL" nil})

(def ^:private unconfigured
  "The env fallback nobody asked for — no variable, no config entry."
  {:configured? false})

(def ^:private configured
  "A provider the user chose, however they chose it."
  {:configured? true})

(deftest ^:unit only-the-unasked-for-fallback-reports-no-provider
  (let [msg (sut/explain-provider-error
             {:error "Connection refused" :provider :ollama} unconfigured no-env)]
    (testing "it says so and names every path that would fix it"
      (is (str/includes? msg "No AI provider is configured"))
      (doseq [v ["ANTHROPIC_API_KEY" "OPENAI_API_KEY" "OPENAI_BASE_URL" "OLLAMA_URL"]]
        (is (str/includes? msg v) (str v " is a supported path and must be listed")))
      (is (str/includes? msg "config.edn")
          "configuring :wagoe/ai-service is a supported path too"))

    (testing "and drops the raw message that sent people to the wrong thing"
      (is (not (str/includes? msg "Connection refused"))))))

(deftest ^:unit a-provider-the-user-chose-is-never-called-unconfigured
  ;; Three bases for this judgement have now been wrong. Ollama configured in
  ;; config.edn is the case that survived the previous two fixes: the provider
  ;; keyword is :ollama and no OLLAMA_URL is set, so both earlier versions told
  ;; the user to configure a provider they had already configured.
  (testing "Ollama from config.edn, with no OLLAMA_URL"
    (let [msg (sut/explain-provider-error
               {:error "Connection refused" :provider :ollama} configured no-env)]
      (is (not (str/includes? msg "No AI provider is configured")))
      (is (str/includes? msg "Cannot reach Ollama"))))

  (testing "an endpoint configured in config.edn is named, not just env ones"
    ;; The URL comes from the provider record, so a base-url set in
    ;; resources/conf/<env>/config.edn is reported like an env-var one. Reading
    ;; only OLLAMA_URL left a config-file user with a message naming no address.
    (let [msg (sut/explain-provider-error
               {:error "Connection refused" :provider :ollama}
               {:configured? true :provider {:base-url "http://127.0.0.1:9"}}
               no-env)]
      (is (str/includes? msg "http://127.0.0.1:9"))
      (is (not (str/includes? msg "No AI provider is configured")))))

  (testing "with a fallback configured, the endpoint reported is the one that ran"
    ;; service.clj retries on :fallback and returns *its* result, so the result
    ;; may describe a different provider than the service's primary. Reading
    ;; the URL from the service named the primary's endpoint and sent the user
    ;; to debug a service that was never the one that failed.
    (let [primary-is-ollama {:configured? true
                             :provider {:base-url "http://127.0.0.1:11434"}}
          fallback-result   {:error "Connection refused" :provider :openai
                             :base-url "http://127.0.0.1:8080"}
          msg (sut/explain-provider-error fallback-result primary-is-ollama no-env)]
      (is (str/includes? msg "http://127.0.0.1:8080")
          "must name the fallback, which is what actually refused")
      (is (not (str/includes? msg "11434"))
          "naming the primary sends the user to the wrong service")))

  (testing "an OpenAI-compatible endpoint that is down names that endpoint"
    (let [msg (sut/explain-provider-error
               {:error "Connection refused" :provider :openai} configured
               {"OPENAI_BASE_URL" "http://localhost:8080" "OLLAMA_URL" nil})]
      (is (str/includes? msg "http://localhost:8080"))
      (is (not (str/includes? msg "No AI provider is configured")))))

  (testing "a deliberately configured Ollama names its URL"
    (let [msg (sut/explain-provider-error
               {:error "Connection refused" :provider :ollama} configured
               {"OLLAMA_URL" "http://box:11434" "OPENAI_BASE_URL" nil})]
      (is (str/includes? msg "http://box:11434"))))

  (testing "any other provider refusing is unreachable, not unconfigured"
    (let [msg (sut/explain-provider-error
               {:error "Connection refused" :provider :anthropic} configured no-env)]
      (is (str/includes? msg "Cannot reach"))
      (is (str/includes? msg "anthropic"))
      (is (not (str/includes? msg "No AI provider is configured"))))))

(deftest ^:unit a-rejected-key-is-reported-without-the-request
  ;; The 401 previously arrived as the whole clj-http map — request options, the
  ;; client object, and every response header including CF-RAY — for what is a
  ;; one-line configuration problem.
  (let [raw (str "clj-http: status 401 {:cached nil, :http-client #object[...], "
                 ":headers {\"Server\" \"cloudflare\", \"CF-RAY\" \"a2788333ba861cb6-AMS\"}}")
        msg (sut/explain-provider-error {:error raw :provider :anthropic} configured no-env)]
    (testing "it says the key was rejected, and by whom"
      (is (str/includes? msg "rejected the API key"))
      (is (str/includes? msg "anthropic")))

    (testing "and carries none of the request detail"
      (doseq [leak ["CF-RAY" "http-client" "cloudflare" ":headers"]]
        (is (not (str/includes? msg leak)) (str leak " leaked into the message"))))))

(deftest ^:unit the-two-kinds-of-429-give-opposite-advice
  ;; A real key with no credit returns 429 `insufficient_quota`, and telling
  ;; that user to wait and retry sends them to do nothing indefinitely. The two
  ;; are only distinguishable from the response body, which `(.getMessage e)`
  ;; does not carry — hence :status and :body on the error result.
  (testing "an exhausted balance says so"
    (let [msg (sut/explain-provider-error
               {:error "clj-http: status 429" :status 429 :provider :openai
                :body  "{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"credit_balance_exhausted\"}}"}
               configured no-env)]
      (is (str/includes? msg "no credit left"))
      (is (not (str/includes? msg "retry"))
          "retrying will never help, so it must not be suggested")))

  (testing "an actual rate limit still says retry"
    (let [msg (sut/explain-provider-error
               {:error "clj-http: status 429" :status 429 :provider :openai
                :body  "{\"error\":{\"type\":\"rate_limit_exceeded\"}}"}
               configured no-env)]
      (is (str/includes? msg "rate-limiting"))
      (is (str/includes? msg "retry"))))

  (testing "a 429 with no body falls back to rate limiting"
    ;; The safer default: suggesting a retry when the cause is unknown wastes a
    ;; minute; claiming an empty wallet when it is a burst is simply wrong.
    (is (str/includes? (sut/explain-provider-error
                        {:error "clj-http: status 429" :status 429 :provider :openai}
                        configured no-env)
                       "rate-limiting"))))

(deftest ^:unit an-unrecognised-error-is-passed-through
  ;; Replacing a message this does not understand with a friendlier guess would
  ;; hide the real one — the failure mode the whole change is about.
  (let [raw "Cannot read source file: nope.clj"]
    (is (= raw (sut/explain-provider-error {:error raw :provider :ollama} configured no-env)))))

(deftest ^:unit the-service-records-whether-a-provider-was-chosen
  ;; The wiring for the fact above. Deriving it downstream has been wrong three
  ;; times, so it is set where the service is built and asserted here.
  (let [src (cli-entry-source)]
    (testing "an env-configured provider is marked chosen"
      (is (<= 3 (count ;; Pattern/quote rather than an inline literal: `?` is a regex
          ;; quantifier, and escaping it through two layers of tooling is how
          ;; this assertion first matched nothing and passed as zero.
                 (re-seq (re-pattern (java.util.regex.Pattern/quote ":configured? true")) src)))
          "expected the anthropic, openai-base-url and openai-key branches"))

    (testing "config.edn counts as chosen"
      (is (str/includes? src "(assoc (ig/init-key :wagoe/ai-service ai-cfg) :configured? true)")))

    (testing "and the bare fallback is only chosen when OLLAMA_URL says so"
      (is (false? (:configured? (#'sut/make-service-from-env {}))))
      (is (true? (:configured? (#'sut/make-service-from-env {"OLLAMA_URL" "http://x:1"})))))))

(deftest ^:unit a-provider-named-in-config-outranks-an-exported-key
  ;; The env chain used to be consulted first, so any exported provider key beat
  ;; :wagoe/ai-service. A dead ANTHROPIC_API_KEY left in a shell profile
  ;; silently overrode a config entry that had just been edited to :replicate,
  ;; and nothing reported that the config was ignored (BOU-401).
  ;;
  ;; The env is not read here: the branch is what matters, and reading it would
  ;; make the result depend on what the developer happens to have exported —
  ;; the fault this fixes.
  (let [from-env {:configured? true :provider ::came-from-env}]
    (testing "a config entry naming a provider decides"
      (with-redefs [wagoe.ai.shell.cli-entry/config-provider
                    (fn [] {:provider :ollama :base-url "http://config-wins:1"})
                    wagoe.ai.shell.cli-entry/make-service-from-env
                    (fn [] from-env)]
        (let [service (#'sut/make-service-from-config)]
          (is (not= ::came-from-env (:provider service))
              "an exported key must no longer outrank a named provider")
          (is (true? (:configured? service))))))

    (testing "no usable config entry falls through to the env chain"
      ;; Generated projects ship no :wagoe/ai-service, and :no-op means the same
      ;; thing here — exporting a key has to keep working for them.
      (with-redefs [wagoe.ai.shell.cli-entry/config-provider (fn [] nil)
                    wagoe.ai.shell.cli-entry/make-service-from-env (fn [] from-env)]
        (is (= ::came-from-env (:provider (#'sut/make-service-from-config))))))))

(deftest ^:unit unknown-options-are-reported-rather-than-dropped
  ;; parse-or-exit! exits the process on a bad flag, so the assertion is on
  ;; tools.cli's own :errors — driving the exit would end the test run.
  (testing "tools.cli reports what the CLI used to discard"
    (let [{:keys [errors]} (cli/parse-opts
                            ["--fil" "/tmp/trace.txt"]
                            sut/explain-opts)]
      (is (seq errors))
      (is (str/includes? (first errors) "--fil"))))

  (testing "a valid invocation still parses cleanly"
    (let [{:keys [errors options]} (cli/parse-opts
                                    ["--file" "/tmp/trace.txt"]
                                    sut/explain-opts)]
      (is (empty? errors))
      (is (= "/tmp/trace.txt" (:file options))))))

(deftest ^:unit every-error-print-site-translates
  ;; The tests above drive `explain-provider-error` directly, so they pass even
  ;; if nothing calls it — verified by reverting the wiring and watching them
  ;; stay green. This asserts the wiring instead: a subcommand that prints the
  ;; provider's raw `:error` puts `Connection refused` back in front of the
  ;; user, which is the whole defect.
  (let [src (cli-entry-source)]

    (testing "the source is readable — otherwise this passes vacuously"
      (is (str/includes? src "explain-provider-error")))

    (testing "no site prints the raw error"
      (is (not (str/includes? src "(str \"Error: \" (:error result))"))
          "found a subcommand printing the provider's message unchanged"))

    (testing "every subcommand prints its result through the translator"
      ;; Counted rather than pattern-matched per site: the formatter now takes
      ;; the whole result, so there is no `(:error …)` in the call to anchor on.
      ;; `describe-failure` wraps `explain-provider-error` (BOU-567).
      (let [sites (re-seq #"\(describe-failure result service\)" src)]
        (is (<= 7 (count sites))
            (str "expected every subcommand to translate; found " (count sites)))))))

(deftest ^:unit every-subcommand-checks-parse-errors
  ;; Same reasoning: `parse-or-exit!` is only useful where it is called. Six
  ;; subcommands used raw `cli/parse-opts` and discarded `:errors`.
  (let [src (cli-entry-source)
        ;; `[a-z-]+-opts` so this matches subcommand option vars and not
        ;; `parse-or-exit!`'s own internal call, whose parameter is `opts`.
        raw (re-seq #"\(cli/parse-opts args ([a-z-]+-opts)\)" src)]
    (testing "no subcommand parses without checking errors"
      (is (empty? raw)
          (str "these still discard :errors — " (pr-str (map second raw)))))

    (testing "and the checked form is actually used"
      (is (<= 7 (count (re-seq #"\(parse-or-exit! args" src)))
          "expected every subcommand to parse through parse-or-exit!"))))

(deftest ^:unit every-failure-names-provider-status-and-message
  ;; BOU-567: a 429 and a cut-off answer both ended in
  ;; "AI CLI exited with error: " and nothing after it.
  (let [svc {:configured? true}]
    (testing "a 429 names the provider, the status and the provider's own words"
      (let [out (sut/describe-failure {:error    "Request was throttled. Resets in ~6s."
                                       :status   429
                                       :provider :replicate
                                       :model    "anthropic/claude-opus-4.6"}
                                      svc)]
        (is (str/includes? out "replicate"))
        (is (str/includes? out "HTTP 429"))
        (is (str/includes? out "Request was throttled. Resets in ~6s."))))

    (testing "a bare clj-http message is completed from the body"
      (let [out (sut/describe-failure {:error    "clj-http: status 429"
                                       :status   429
                                       :body     "{\"error\":{\"message\":\"Too many requests\"}}"
                                       :provider :anthropic}
                                      svc)]
        (is (str/includes? out "Too many requests"))))

    (testing "an empty message still says something"
      (let [out (sut/describe-failure {:error "" :provider :openai} svc)]
        (is (str/includes? out "openai"))
        (is (str/includes? out "no message"))))

    (testing "a failure after the call has no status to name"
      (is (not (str/includes? (sut/describe-failure {:error "cut off" :provider :mock} svc)
                              "HTTP"))))))

(deftest ^:unit admin-entity-goes-to-every-profile
  ;; BOU-567: dev and test were hardcoded, so prod never got the file.
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "profiles" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (testing "a project with no profiles yet gets dev and test"
      (is (= ["dev" "test"] (sut/profiles (.getPath root)))))
    (doseq [p ["dev" "test" "prod"]]
      (let [f (io/file root "resources" "conf" p "config.edn")]
        (io/make-parents f)
        (spit f "{}")))
    (io/make-parents (io/file root "resources" "conf" "notes" "x"))
    (testing "every profile with a config.edn, and nothing else"
      (is (= ["dev" "prod" "test"] (sut/profiles (.getPath root)))))))

(deftest ^:unit an-existing-admin-file-is-never-overwritten-unasked
  ;; BOU-567 review: writing every profile also overwrote a hand-edited prod
  ;; config, and --yes skipped the only prompt.
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "keep" (make-array java.nio.file.attribute.FileAttribute 0)))
        prod (io/file root "resources" "conf" "prod" "admin" "invoices.edn")
        hand "{:invoices {:label \"Hand edited\"}} ; keep me\n"]
    (doseq [p ["dev" "test" "prod"]]
      (let [f (io/file root "resources" "conf" p "config.edn")]
        (io/make-parents f)
        (spit f "{}")))
    (io/make-parents prod)
    (spit prod hand)
    (let [targets (sut/admin-entity-targets (.getPath root)
                                            [{:entity-name "invoices" :text "{:invoices {}}"}])]
      (testing "every target is listed, and the existing one is marked"
        (is (= 3 (count targets)))
        (is (= [(.getPath prod)]
               (map :path (filter :exists? targets)))))

      (testing "without --force the existing file stays byte-identical"
        (let [written (sut/write-admin-entities! targets false)]
          (is (= hand (slurp prod)))
          (is (= 2 (count (filter :written? written))))
          (is (= "{:invoices {}}"
                 (slurp (io/file root "resources" "conf" "dev" "admin" "invoices.edn"))))))

      (testing "--force overwrites it"
        (sut/write-admin-entities! targets true)
        (is (= "{:invoices {}}" (slurp prod)))))))

(deftest ^:unit a-test-namespace-that-fails-its-checks-is-not-written
  ;; BOU-572: rc-4 wrote one that stopped the whole suite from loading.
  (let [failed {:text "(ns t-test)" :check-errors ["[t-test] Unable to resolve symbol"]}
        passed {:text "(ns t-test)"}]
    (is (= :refuse (sut/gen-tests-outcome failed "test/t_test.clj" false)))
    (is (= :refuse (sut/gen-tests-outcome failed nil false)) "not printed as if it worked either")
    (is (= :write (sut/gen-tests-outcome failed "test/t_test.clj" true)) "--force writes it anyway")
    (is (= :print (sut/gen-tests-outcome failed nil true)))
    (is (= :write (sut/gen-tests-outcome passed "test/t_test.clj" false)))
    (is (= :print (sut/gen-tests-outcome passed nil false)))))

(deftest ^:unit admin-entity-next-steps-name-only-what-a-config-lacks
  ;; rc-4 advised adding the allowlist entry and the #include when both were
  ;; already there (BOU-580).
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "admin-next" (make-array java.nio.file.attribute.FileAttribute 0)))
        conf (fn [profile text]
               (let [f (io/file root "resources" "conf" profile "config.edn")]
                 (io/make-parents f)
                 (spit f text)))]
    (conf "dev" (str "{:active\n {:wagoe/http {:port #or [#env HTTP_PORT 3000]}\n"
                     "  :wagoe/admin\n  {:entity-discovery {:mode :allowlist :allowlist #{:users :invoices}}\n"
                     "   :entities #merge [#include \"admin/users.edn\" #include \"admin/invoices.edn\"]}}}\n"))
    (conf "test" (str "{:active\n {:wagoe/admin\n  {:entity-discovery {:mode :allowlist :allowlist #{:users}}\n"
                      "   :entities #merge [#include \"admin/users.edn\"]}}}\n"))
    (conf "prod" (str "{:active\n {:wagoe/admin\n  {:entity-discovery {:mode :allowlist :allowlist #{:users :invoices}}\n"
                      "   :entities #merge [#include \"admin/users.edn\" #include \"admin/invoices.edn\"]}}}\n"))
    (is (= ["test: add :invoices to :entity-discovery :allowlist"
            "test: add #include \"admin/invoices.edn\" to :entities"]
           (sut/admin-entity-next-steps (.getPath root) ["invoices"])))
    (is (= [] (sut/admin-entity-next-steps (.getPath root) ["users"]))
        "every profile has users")))

(deftest ^:unit the-help-lists-every-variable-the-env-chain-reads
  ;; The help listed no Replicate variables (BOU-580). The chain and the help
  ;; now read one table.
  (doseq [{:keys [var provider]} sut/provider-env]
    (let [service (#'sut/make-service-from-env {var "x"})]
      (is (= provider (-> service :provider type .getSimpleName
                          (str/replace "Provider" "") str/lower-case keyword))
          var)
      (is (str/includes? sut/help-text var) var)))
  (is (str/includes? sut/help-text "REPLICATE_API_TOKEN"))
  (is (str/includes? sut/help-text "AI_MODEL")))

(defn- run-admin-entity
  "Run `bb ai admin-entity` in a fresh project with the AI stubbed, on `stdin`
   and with or without a terminal. Returns {:out :exit :written?}."
  [args stdin tty?]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "admin-entity" (make-array java.nio.file.attribute.FileAttribute 0)))
        dev  (io/file root "resources" "conf" "dev" "config.edn")
        exit (atom nil)]
    (io/make-parents dev)
    (spit dev "{}")
    (let [out (with-redefs [sut/make-service-from-config (constantly nil)
                            sut/tty?                     (constantly tty?)
                            svc/generate-admin-entity    (fn [& _]
                                                           {:text     "{:invoices {}}"
                                                            :entities [{:entity-name "invoices"
                                                                        :text        "{:invoices {}}"}]})]
                (binding [sut/*exit!* #(reset! exit %)]
                  (with-out-str
                    (with-in-str stdin
                      (sut/cmd-admin-entity (into ["--root" (.getPath root)] args))))))]
      {:out      out
       :exit     @exit
       :written? (.exists (io/file root "resources" "conf" "dev" "admin" "invoices.edn"))})))

(deftest ^:unit admin-entity-never-assumes-consent
  ;; --force still asked "Write these files? [Y/n]", and without a terminal an
  ;; unread answer was taken as yes (BOU-585).
  (doseq [flag ["--force" "--yes"]]
    (testing (str flag " writes without asking")
      (let [{:keys [out exit written?]} (run-admin-entity [flag "invoices"] "" false)]
        (is (not (str/includes? out "[Y/n]")) out)
        (is (nil? exit) out)
        (is written? out))))
  (testing "no terminal and no flag refuses"
    (let [{:keys [out exit written?]} (run-admin-entity ["invoices"] "y\n" false)]
      (is (= 1 exit) out)
      (is (not written?) out)
      (is (str/includes? out "--yes") out)))
  (testing "a terminal is asked, and a closed stdin is not a yes"
    (let [{:keys [out written?]} (run-admin-entity ["invoices"] "" true)]
      (is (str/includes? out "[Y/n]") out)
      (is (not written?) out))
    (is (:written? (run-admin-entity ["invoices"] "y\n" true)))))
