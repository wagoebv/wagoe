(ns wagoe.ai.shell.providers.replicate-test
  "BOU-281: Replicate has no OpenAI-compatible endpoint, so its request and
   response shapes differ from every other provider in three ways that each get
   a test here — a flat prompt instead of a conversation, chunked output instead
   of a string, and per-model input constraints instead of per-API ones.

   The shaping is pure and driven directly; the wiring is asserted separately,
   because a provider that is never dispatched to would pass every test below."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.ai.ports :as ports]
            [integrant.core :as ig]
            [wagoe.ai.shell.module-wiring]
            [wagoe.ai.shell.providers.replicate :as sut]))

(deftest ^:unit a-conversation-becomes-a-flat-prompt
  ;; Replicate takes `prompt` plus optional `system_prompt`, not messages.
  (testing "system messages are kept separate from the rest"
    (let [input (sut/messages->input [{:role :system :content "Be terse."}
                                      {:role :user   :content "count orders"}])]
      (is (= "Be terse." (:system_prompt input)))
      (is (= "count orders" (:prompt input)))))

  (testing "several messages of a kind keep their order"
    (let [input (sut/messages->input [{:role :system :content "A"}
                                      {:role :user   :content "one"}
                                      {:role :system :content "B"}
                                      {:role :user   :content "two"}])]
      (is (= "A\n\nB" (:system_prompt input)))
      (is (= "one\n\ntwo" (:prompt input)))))

  (testing "no system message means no system_prompt key"
    ;; Sending an empty one would override a model's own default.
    (is (not (contains? (sut/messages->input [{:role :user :content "hi"}])
                        :system_prompt))))

  (testing "string roles work as well as keywords"
    ;; Messages arrive from config and from code, so both forms occur.
    (is (= "S" (:system_prompt (sut/messages->input [{:role "system" :content "S"}
                                                     {:role "user" :content "u"}]))))))

(deftest ^:unit chunked-output-is-joined
  ;; Replicate returns a list of chunks. Printing it verbatim would show the
  ;; user a vector.
  (testing "the chunks a real call returns"
    (is (= "\n\nOK" (sut/output->text ["\n\n" "OK"]))))

  (testing "a model that returns a plain string still works"
    (is (= "OK" (sut/output->text "OK"))))

  (testing "nothing stays nothing, rather than becoming \"null\""
    (is (nil? (sut/output->text nil)))))

(deftest ^:unit a-rejected-input-names-the-field
  ;; Input constraints are per-model: anthropic/claude-4.5-haiku rejects
  ;; max_tokens below 1024. The caller cannot know that from the API, so the
  ;; detail is worth more than the status.
  (testing "the 422 body a real call returns"
    (let [body (str "{\"detail\":\"- input.max_tokens: Must be greater than or equal to 1024\\n\","
                    "\"status\":422,\"title\":\"Input validation failed\"}")]
      (is (= "- input.max_tokens: Must be greater than or equal to 1024"
             (sut/failure-detail body)))))

  (testing "an already-parsed body works too"
    ;; The request uses :as :json. clj-http's default :coerce :unexceptional
    ;; leaves error bodies as strings — measured against a real 422 — but that
    ;; is a default, not a guarantee, and `(str m)` on a map parses as nil,
    ;; silently dropping the detail. The earlier test only passed a string, so
    ;; it could not have caught this.
    (is (= "- input.max_tokens: Must be greater than or equal to 1024"
           (sut/failure-detail
            {:detail "- input.max_tokens: Must be greater than or equal to 1024\n"
             :status 422 :title "Input validation failed"}))))

  (testing "a parsed body with only a title falls back to it"
    (is (= "Input validation failed" (sut/failure-detail {:title "Input validation failed"}))))

  (testing "a body with nothing useful yields nil, so the caller keeps its own message"
    (is (nil? (sut/failure-detail "{\"status\":500}")))
    (is (nil? (sut/failure-detail {:status 500})))
    (is (nil? (sut/failure-detail "not json at all")))
    (is (nil? (sut/failure-detail nil)))))

(defn- stub-post
  "clj-http.client/post returning `body` as a parsed prediction response.

   Stubbed at the HTTP layer rather than at `ports/complete`: with-redefs on a
   protocol method does not intercept the record's own call to it, so an
   earlier version of these tests made real API calls and failed on the fake
   token instead of exercising the branch under test."
  [body]
  (fn [_url _opts] {:body body}))

(deftest ^:unit a-prediction-that-fails-after-a-2xx-is-an-error
  ;; The HTTP call succeeds and the run does not. Reading :text as nil here
  ;; would report a parse failure for what is a provider error.
  (let [p (sut/create-replicate-provider {:api-key "t"})]
    (testing "a failed status becomes an error, carrying the provider's reason"
      (with-redefs [http/post (stub-post {:status "failed" :error "CUDA OOM"})]
        (let [r (ports/complete p [{:role :user :content "x"}] {})]
          (is (= "CUDA OOM" (:error r)))
          (is (nil? (:text r))))))

    (testing "a failed status with no reason still reports the status"
      (with-redefs [http/post (stub-post {:status "canceled"})]
        (is (str/includes? (:error (ports/complete p [{:role :user :content "x"}] {}))
                           "canceled"))))

    (testing "a succeeded prediction joins its chunks into :text"
      (with-redefs [http/post (stub-post {:status "succeeded" :output ["Hel" "lo"]})]
        (let [r (ports/complete p [{:role :user :content "x"}] {})]
          (is (= "Hello" (:text r)))
          (is (nil? (:error r))))))))

(deftest ^:unit json-is-extracted-from-prose
  ;; Replicate has no JSON mode, so models wrap the object in prose or fences
  ;; despite the instruction.
  (let [p (sut/create-replicate-provider {:api-key "t"})]
    (testing "a fenced object is still parsed"
      (with-redefs [http/post (stub-post {:status "succeeded"
                                          :output ["Here you go:\n```json\n"
                                                   "{\"sql\":\"SELECT 1\"}\n```"]})]
        (is (= {:sql "SELECT 1"}
               (:data (ports/complete-json p [{:role :user :content "x"}] nil {}))))))

    (testing "output with no object at all is an error, not a silent nil"
      (with-redefs [http/post (stub-post {:status "succeeded"
                                          :output ["I cannot help with that."]})]
        (let [r (ports/complete-json p [{:role :user :content "x"}] nil {})]
          (is (str/includes? (:error r) "not valid JSON"))
          (is (= "I cannot help with that." (:raw r))
              "the raw text is kept, so the failure can be diagnosed"))))

    (testing "an upstream error passes through untouched"
      (with-redefs [http/post (stub-post {:status "failed" :error "boom"})]
        (is (= "boom" (:error (ports/complete-json p [{:role :user :content "x"}] nil {}))))))))

(deftest ^:unit the-request-matches-what-replicate-accepts
  ;; Verified against the real API: the model goes in the path, the token in a
  ;; Bearer header, and `Prefer: wait` is what makes this synchronous. Without
  ;; that header the response is a pending prediction with no output.
  (let [captured (atom nil)
        p (sut/create-replicate-provider {:api-key "tok" :model "owner/name"})]
    (with-redefs [http/post (fn [url opts]
                              (reset! captured {:url url :opts opts})
                              {:body {:status "succeeded" :output ["ok"]}})]
      (ports/complete p [{:role :system :content "S"} {:role :user :content "U"}] {}))
    (let [{:keys [url opts]} @captured
          body (json/parse-string (:body opts) true)]
      (is (= "https://api.replicate.com/v1/models/owner/name/predictions" url))
      (is (= "Bearer tok" (get-in opts [:headers "Authorization"])))
      (is (= "wait" (get-in opts [:headers "Prefer"]))
          "without Prefer: wait the call returns before the model does")
      (is (= "U" (get-in body [:input :prompt])))
      (is (= "S" (get-in body [:input :system_prompt]))))))

(deftest ^:unit the-provider-is-reachable-from-config
  ;; Every test above drives the record directly, so they would all pass while
  ;; `:replicate` in config.edn still threw "Unknown AI provider" — which is
  ;; exactly what it did before this change.
  ;; Driven through ig/init-key rather than the private build-provider: that is
  ;; the entry point a config.edn actually reaches.
  (testing ":replicate in config builds a Replicate provider"
    (let [svc (ig/init-key :wagoe/ai-service {:provider :replicate :api-key "t"})]
      (is (= :replicate (ports/provider-name (:provider svc))))))

  (testing "and the other providers still build — dispatch is additive"
    (doseq [p [:ollama :openai :no-op]]
      (is (some? (:provider (ig/init-key :wagoe/ai-service
                                         {:provider p :api-key "t" :base-url "http://x"})))
          (str p " stopped building"))))

  (testing "an unknown provider still throws, rather than falling through"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ig/init-key :wagoe/ai-service {:provider :nope})))))

(deftest ^:unit a-default-model-is-chosen
  ;; Without one, a config that omits :model would send a nil model in the URL
  ;; path and 404.
  (is (str/includes? sut/default-model "/")
      "a Replicate model is owner/name")
  (is (= sut/default-model (:model (sut/create-replicate-provider {:api-key "t"})))))

(deftest ^:unit no-provider-carries-its-own-json-parser
  ;; wagoe.ai.core.parsing/parse-json-response exists for this, and every
  ;; provider had reimplemented it — four copies of the same try/catch around
  ;; a `{.*}` regex. The behaviour was identical, so this is deduplication
  ;; rather than a fix, and the value is that it stays one copy (BOU-281).
  (let [dir (or (some #(when (.isDirectory (io/file %)) (io/file %))
                      ["libs/ai/src/wagoe/ai/shell/providers"
                       "src/wagoe/ai/shell/providers"])
                (throw (ex-info "providers dir not found — cannot check" {})))
        clj (filter #(str/ends-with? (.getName %) ".clj") (file-seq dir))]

    (testing "the sources were found — otherwise this passes vacuously"
      (is (<= 4 (count clj))))

    (doseq [f clj
            :let [src (slurp f)]]
      (testing (str (.getName f) " delegates JSON extraction")
        (is (not (str/includes? src "(re-find #\"(?s)\\{.*\\}\""))
            "re-implements the shared parser's object extraction")))))

(deftest ^:unit every-provider-registry-agrees
  ;; Adding :replicate needed three edits in three places, and I found the
  ;; third only from review: build-provider dispatches, bb doctor validates
  ;; config values, and wagoe.ai.schema is the public contract projects
  ;; validate against. A config could initialise successfully and still fail
  ;; schema validation (BOU-281).
  (let [read-first (fn [& paths]
                     (or (some #(when (.exists (io/file %)) (slurp %)) paths)
                         (throw (ex-info "source not found — cannot compare registries"
                                         {:tried paths
                                          :cwd (System/getProperty "user.dir")}))))
        wiring (read-first "libs/ai/src/wagoe/ai/shell/module_wiring.clj"
                           "src/wagoe/ai/shell/module_wiring.clj")
        schema (read-first "libs/ai/src/wagoe/ai/schema.clj"
                           "src/wagoe/ai/schema.clj")
        dispatched (set (map (comp keyword second)
                             (re-seq #"(?m)^\s+:([a-z-]+)\s+\([a-z-]+/create-" wiring)))
        ;; Only the provider enums. schema.clj holds several others, and
        ;; matching every :enum made this compare unrelated ones.
        enums      (->> (re-seq #"\[:enum ([^\]]+)\]" schema)
                        (map (comp str/trim second))
                        (filter #(str/includes? % ":ollama"))
                        (map (fn [body] (set (re-seq #"(?<=:)[a-z-]+" body)))))]

    (testing "the sources parsed — otherwise this passes vacuously"
      (is (<= 5 (count dispatched)) (str "found only " (pr-str dispatched)))
      (is (= 2 (count enums)) "expected AIConfig and ProviderConfig enums"))

    (testing "every provider enum matches what build-provider can build"
      (let [disp-names (set (map name dispatched))]
        (doseq [enum-names enums]
          (is (= disp-names enum-names)
              (str "schema accepts " (pr-str (set/difference enum-names disp-names))
                   " that cannot be built, and is missing "
                   (pr-str (set/difference disp-names enum-names)))))))))
