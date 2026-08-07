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
            [wagoe.ai.shell.cli-entry :as sut]))

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

(deftest ^:unit a-refused-ollama-fallback-means-no-provider
  ;; Ollama is the fallback when nothing is configured, so a refused connection
  ;; there is the one case that means "configure something".
  (let [msg (sut/explain-provider-error
             {:error "Connection refused" :provider :ollama} no-env)]
    (testing "it says so and names every variable that would fix it"
      (is (str/includes? msg "No AI provider is configured"))
      (doseq [v ["ANTHROPIC_API_KEY" "OPENAI_API_KEY" "OPENAI_BASE_URL" "OLLAMA_URL"]]
        (is (str/includes? msg v) (str v " is a supported path and must be listed"))))

    (testing "and drops the raw message that sent people to the wrong thing"
      (is (not (str/includes? msg "Connection refused"))))))

(deftest ^:unit a-configured-provider-that-is-down-is-not-a-missing-provider
  ;; The distinction the first version got wrong: someone who set
  ;; OPENAI_BASE_URL to a local endpoint has configured a provider. Telling them
  ;; to configure one sends them to the wrong fix.
  (testing "an OpenAI-compatible endpoint that is down names that endpoint"
    (let [msg (sut/explain-provider-error
               {:error "Connection refused" :provider :openai}
               {"OPENAI_BASE_URL" "http://localhost:8080" "OLLAMA_URL" nil})]
      (is (str/includes? msg "http://localhost:8080"))
      (is (not (str/includes? msg "No AI provider is configured")))))

  (testing "a deliberately configured Ollama names its URL"
    (let [msg (sut/explain-provider-error
               {:error "Connection refused" :provider :ollama}
               {"OLLAMA_URL" "http://box:11434" "OPENAI_BASE_URL" nil})]
      (is (str/includes? msg "http://box:11434"))
      (is (not (str/includes? msg "No AI provider is configured")))))

  (testing "any other provider refusing is reported as unreachable, not unconfigured"
    (let [msg (sut/explain-provider-error
               {:error "Connection refused" :provider :anthropic} no-env)]
      (is (str/includes? msg "Cannot reach"))
      (is (str/includes? msg "anthropic"))
      (is (not (str/includes? msg "No AI provider is configured"))))))

(deftest ^:unit a-rejected-key-is-reported-without-the-request
  ;; The 401 previously arrived as the whole clj-http map — request options, the
  ;; client object, and every response header including CF-RAY — for what is a
  ;; one-line configuration problem.
  (let [raw (str "clj-http: status 401 {:cached nil, :http-client #object[...], "
                 ":headers {\"Server\" \"cloudflare\", \"CF-RAY\" \"a2788333ba861cb6-AMS\"}}")
        msg (sut/explain-provider-error {:error raw :provider :anthropic} no-env)]
    (testing "it says the key was rejected, and by whom"
      (is (str/includes? msg "rejected the API key"))
      (is (str/includes? msg "anthropic")))

    (testing "and carries none of the request detail"
      (doseq [leak ["CF-RAY" "http-client" "cloudflare" ":headers"]]
        (is (not (str/includes? msg leak)) (str leak " leaked into the message"))))))

(deftest ^:unit rate-limiting-is-distinguished
  (is (str/includes? (sut/explain-provider-error
                      {:error "clj-http: status 429 {...}" :provider :openai} no-env)
                     "rate-limiting")))

(deftest ^:unit an-unrecognised-error-is-passed-through
  ;; Replacing a message this does not understand with a friendlier guess would
  ;; hide the real one — the failure mode the whole change is about.
  (let [raw "Cannot read source file: nope.clj"]
    (is (= raw (sut/explain-provider-error {:error raw :provider :ollama} no-env)))))

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
      (let [sites (re-seq #"\(explain-provider-error result\)" src)]
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
