(ns wagoe.tools.eval-report-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wagoe.tools.eval-report :as er]))

(defn- night [provider date mergeable & [median-edits]]
  {:status :ran :provider provider :date date
   :model (if (= "ollama" provider) "qwen2.5-coder:7b" "gpt-4o-mini")
   :label (if (= "ollama" provider) "Ollama" "OpenAI")
   :prompts [{:id :product :prompt "p" :failure {:step :tests :log "boom"}}]
   :summary {:mergeable mergeable :of 5 :median-edits median-edits}})

(defn- date [d] (str (.plusDays (java.time.LocalDate/parse "2026-10-01") d)))

(deftest ^:unit summary-per-provider-over-the-window
  (let [rs [(night "ollama" (date 0) 1) ; outside 30 days of 2026-11-10
            (night "ollama" (date 30) 4 2) (night "ollama" (date 31) 3 4) (night "ollama" (date 32) 4 0)
            (night "openai" (date 32) 5 0)
            {:status :skipped :provider "openai" :date (date 31)}]
        [o a] (er/summaries rs (date 40) 30)]
    (is (= {:provider "ollama" :nights 3 :mergeable 4 :of 5 :median-edits 2}
           (select-keys o [:provider :nights :mergeable :of :median-edits])))
    (is (= "OpenAI/gpt-4o-mini: 5 of 5 prompts mergeable without edits, median edits 0 (1 nights)"
           (er/summary-line a)))))

(deftest ^:unit regression-is-a-drop-of-more-than-one-below-the-weekly-average
  (let [week (for [d (range 7)] (night "ollama" (date d) 4))]
    (is (nil? (er/regression (concat week [(night "ollama" (date 7) 3)]) "ollama")))
    (is (= {:mergeable 2 :nights 7}
           (select-keys (er/regression (concat week [(night "ollama" (date 7) 2)]) "ollama") [:mergeable :nights])))
    (is (nil? (er/regression (concat week [(night "openai" (date 7) 0)]) "ollama"))
        "another provider's night is not this one's")
    (is (nil? (er/regression [(night "ollama" (date 7) 0)] "ollama"))
        "no history, no regression")
    (is (nil? (er/regression [(night "ollama" (date 0) 5) (night "ollama" (date 20) 0)] "ollama"))
        "a night older than 7 days is not in the average")))

(deftest ^:unit issue-body-carries-the-failing-log
  (let [r (er/regression (concat (for [d (range 3)] (night "ollama" (date d) 5))
                                 [(night "ollama" (date 3) 1)])
                         "ollama")]
    (is (str/includes? (er/issue-body r) "fell to 1 of 5"))
    (is (str/includes? (er/issue-body r) "boom"))))

(deftest ^:unit stability-line-waits-for-thirty-nights
  (let [ollama (for [d (range 30)] (night "ollama" (date d) 4))
        openai (for [d (range 29)] (night "openai" (date d) 5))]
    (is (nil? (er/stability-line (butlast ollama))))
    (is (= "AI scaffolding, last 30 nights: 4/5 prompts mergeable without edits (Ollama/qwen2.5-coder:7b)."
           (er/stability-line (concat ollama openai))))
    (is (= (str "AI scaffolding, last 30 nights: 4/5 prompts mergeable without edits (Ollama/qwen2.5-coder:7b),"
                " 5/5 (OpenAI/gpt-4o-mini).")
           (er/stability-line (concat ollama openai [(night "openai" (date 29) 5)]))))))
