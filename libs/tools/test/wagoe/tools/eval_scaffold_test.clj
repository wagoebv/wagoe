(ns wagoe.tools.eval-scaffold-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.eval-scaffold :as es]))

(def ^:private catalogue (es/load-prompts "eval/scaffold/prompts.edn"))

(deftest ^:unit prompts-are-five-with-the-conj-expectations
  (is (= 5 (count (:prompts catalogue))))
  (is (apply distinct? (map :id (:prompts catalogue))))
  (doseq [p (:prompts catalogue)]
    (is (= #{:no-protocol-interop :no-request-identity}
           (into #{} (comp (map :check) (filter #{:no-protocol-interop :no-request-identity}))
                 (es/prompt-expectations catalogue p)))
        (str (:id p) " lacks a Conj expectation")))
  (testing "every check has an implementation"
    (doseq [p (:prompts catalogue)
            e (es/prompt-expectations catalogue p)]
      (is (not (:error (es/check-expectation e {}))) (str (:id p) " " (:id e))))))

(def ^:private ports
  "(ns app.thing.ports)
(defprotocol IThingRepository
  \"Things.\"
  (create-thing [this data])
  (find-thing [this id]))")

(deftest ^:unit interop-on-a-protocol-method-is-caught
  (let [service "(ns app.thing.shell.service)
(defn create [repo data]
  (.create-thing repo data))
(defn s [x] (.toString x))
;; (.find-thing repo id) in a comment is fine
(defn f [repo id] (find-thing repo id))"
        hits    (es/interop-on-protocol-hits {"src/ports.clj" ports "src/service.clj" service})]
    (is (= [{:file "src/service.clj" :line 3 :call "(.create-thing"}] hits))))

(deftest ^:unit protocol-methods-read-from-defprotocol
  (is (= #{"create-thing" "find-thing"} (es/protocol-methods ports))))

(deftest ^:unit identity-read-is-caught-and-user-is-not
  (is (= [{:file "src/h.clj" :line 2}]
         (es/request-identity-hits {"src/h.clj" "(defn h [request]\n  (let [actor (:identity request)] actor))"})))
  (is (empty? (es/request-identity-hits {"src/h.clj" "(defn h [request] (:user request))"
                                         "src/k.clj" "(def m {:identity-provider 1})"
                                         "resources/x.edn" "{:identity 1}"}))))

(deftest ^:unit keyword-and-pattern-expectations
  (let [src {"src/p.clj" "[:map [:name :string] [:price :int]]"}]
    (is (= {:id :f :pass? false :missing [:stock]}
           (es/check-expectation {:id :f :check :keywords :keywords [:name :price :stock]} src)))
    (is (:pass? (es/check-expectation {:id :m :check :matches :pattern "price"} src)))
    (is (false? (:pass? (es/check-expectation {:id :x :check :nope} src))))))

(deftest ^:unit edits-are-added-plus-removed-lines
  (is (= 9 (es/numstat-edits "3\t1\tsrc/a.clj\n5\t0\tsrc/b.clj\n-\t-\timg.png\n")))
  (is (= 0 (es/numstat-edits ""))))

(deftest ^:unit summary-counts-mergeable-without-edits
  (is (= {:mergeable 2 :of 4 :median-edits 0}
         (es/summarise [{:edits 0} {:edits 0} {:edits nil} {:edits 3}])))
  (is (= 1.5 (es/median [0 3])))
  (is (nil? (es/median []))))

(deftest ^:unit green-ignores-skipped-steps
  (is (es/green? {:a {:status :pass} :b {:status :skipped}} [{:pass? true}]))
  (is (not (es/green? {:a {:status :fail}} [])))
  (is (not (es/green? {} [{:pass? false}]))))

(deftest ^:unit a-cell-sees-only-its-own-provider
  (let [env {"ANTHROPIC_API_KEY" "a" "OPENAI_API_KEY" "o" "PATH" "/bin"}]
    (is (= {"PATH" "/bin" "OLLAMA_URL" "http://localhost:11434" "AI_MODEL" "qwen2.5-coder:7b"}
           (es/cell-env env "ollama")))
    (is (= {"PATH" "/bin" "OPENAI_API_KEY" "o" "AI_MODEL" "gpt-4o-mini"}
           (es/cell-env env "openai")))))

(deftest ^:unit each-prompt-is-saved-as-it-finishes
  (let [saved (atom [])]
    (with-redefs [es/new-base-project! (constantly "base")
                  es/run-prompt        (fn [_ _ p] {:id (:id p) :mergeable? true :edits 0 :duration-ms 0})]
      (let [r (es/evaluate {:repo "." :env {} :save! #(swap! saved conj %)
                            :catalogue {:prompts [{:id :a} {:id :b}]}}
                           "ollama")]
        (is (= [[:partial 1] [:partial 2]] (map (juxt :status (comp count :prompts)) @saved)))
        (is (= :ran (:status r)))))))

(deftest ^:unit a-provider-refusal-is-not-a-score
  (testing "the bracket describe-failure prints"
    (is (= "[openai, gpt-4o-mini, HTTP 401]"
           (es/provider-error "The AI provider (openai) rejected the API key.\n  [openai, gpt-4o-mini, HTTP 401]\n  clj-http: status 401")))
    (is (= "[openai, gpt-4o-mini, HTTP 429]" (es/provider-error "x\n  [openai, gpt-4o-mini, HTTP 429]")))
    (is (= "[ollama, qwen2.5-coder:7b, HTTP 404]" (es/provider-error "[ollama, qwen2.5-coder:7b, HTTP 404]")))
    (is (= "Cannot reach Ollama at http://localhost:11434. Is it running?"
           (es/provider-error "Cannot reach Ollama at http://localhost:11434. Is it running?\n"))))
  (testing "a model's bad output is a score"
    (is (nil? (es/provider-error "AI scaffolder could not parse the description.")))
    (is (nil? (es/provider-error "[ollama, qwen2.5-coder:7b, HTTP 400]"))))
  (is (= :provider-error (es/run-status [{:edits 0} {:failure {:provider-error "[x, HTTP 429]"}}])))
  (is (= :ran (es/run-status [{:edits 0} {:failure {:step :generate}}])))
  (testing "the run stops at the first refusal"
    (with-redefs [es/new-base-project! (constantly "base")
                  es/run-prompt        (fn [_ _ p] {:id (:id p) :edits nil :duration-ms 0
                                                    :failure {:provider-error "[x, HTTP 401]"}})]
      (let [r (es/evaluate {:repo "." :env {} :catalogue {:prompts [{:id :a} {:id :b}]}} "ollama")]
        (is (= [:a] (map :id (:prompts r))))
        (is (= :provider-error (:status r)))))))
