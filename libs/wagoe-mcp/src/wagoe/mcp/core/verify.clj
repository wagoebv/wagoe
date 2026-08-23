(ns wagoe.mcp.core.verify
  "Pure aggregation of the Tier 1 closed verify loop (BOU-101).

   The shell runs the steps that need I/O — generate/write (scaffolder), kondo,
   FC/IS, run the affected tests — and hands the raw per-step results here.
   This namespace turns them into the single structured report the agent reads
   to self-correct: a flat list of issues (file, line, BND code, kind,
   expected/actual) plus an overall verdict. No I/O, no side effects.

   The loop the report describes:
     generate → write → kondo → Malli/FC-IS → run affected core tests → report

   Issue `:severity` drives the verdict:
     :error — kondo error, FC/IS (BND-806) or convention (BND-807) violation,
              or a failing test. Blocks (`:status :fail`) unless soft and
              overridden.
     :warning — kondo warning; never blocks.

   FC/IS (BND-806) and convention (BND-807) issues are *soft* guardrails: an
   audited `{:allow true}` override turns a would-be `:fail` into `:overridden`
   (\"guardrail, not straitjacket\", ADR-032). kondo errors and test failures
   are *hard* — they are never overridable, because they mean the generated code
   does not compile or does not pass.")

;; BND codes the verify loop assigns to its own findings (see devtools catalog).
(def fcis-code "BND-806")
(def convention-code "BND-807")

;; The soft (overridable) guardrail codes among verify issues.
(def ^:private soft-codes #{fcis-code convention-code})

(defn- kondo->issues
  "clj-kondo findings → verify issues. Errors block; warnings inform."
  [findings]
  (for [{:keys [filename row col level type message]} findings
        :when (#{:error :warning} level)]
    {:step     :kondo
     :severity (if (= :error level) :error :warning)
     :file     filename
     :line     row
     :col      col
     :kind     type
     :message  message}))

(defn- fcis->issues
  "check-fcis/check-file violations → verify issues (all BND-806, :error)."
  [violations]
  (for [{:keys [file ns req kind line]} violations]
    {:step     :fcis
     :severity :error
     :code     fcis-code
     :file     file
     :line     line
     :ns       ns
     :kind     kind
     :message  (format "Core namespace %s must not %s %s"
                       ns (name (or kind :use)) req)}))

(defn- tests->issues
  "A failing-tests step → one issue per failure. `failures` is a seq of
   {:ns :var :file :line :message [:expected] [:actual]}."
  [failures]
  (for [{:keys [ns var file line message expected actual]} failures]
    (cond-> {:step     :tests
             :severity :error
             :file     file
             :line     line
             :kind     :test-failure
             :message  (or message (str "Test failed: " ns "/" var))}
      (some? expected) (assoc :expected expected)
      (some? actual)   (assoc :actual actual))))

(defn build-report
  "Assemble the structured verify report from raw per-step results.

   `steps` keys (all optional — a step the shell skipped is simply absent):
     :generate {:success bool :files [{:path :action}] [:errors]}
     :kondo    {:findings [clj-kondo finding ...]}
     :fcis     {:violations [check-fcis violation ...]}
     :tests    {:status :passed|:failed|:error|:unavailable
                [:passed n] [:failed n] [:failures [...]] [:note ...]}
   `opts`:
     :overridden? — caller passed an audited `{:allow true}`; soft issues then
                    yield `:overridden` instead of `:fail`.

   Returns:
     {:status    :pass | :fail | :overridden
      :complete? bool                       ;; false when a step couldn't run
      :issues    [ ... flat, ordered kondo→fcis→tests ... ]
      :counts    {:errors n :warnings n}
      :steps     {<step> <summary>}         ;; compact per-step status
      [:overridable? bool]}                 ;; present when soft issues blocked

   `:complete?` is false when the affected tests did not actually run (the
   runner errored, or none was configured): then `:status :pass` means \"passed
   what ran\", not \"fully verified\". An errored test step also adds a
   `:verify-incomplete` *warning* issue (never blocks); the expected
   no-runner-configured case (`:unavailable`) only lowers `:complete?`."
  ([steps] (build-report steps {}))
  ([{:keys [generate kondo fcis tests]} {:keys [overridden?]}]
   (let [gen-issues   (for [e (:errors generate)]
                        {:step :generate :severity :error :kind :generation
                         :message e})
         test-status  (:status tests)
         kondo-issues (kondo->issues (:findings kondo))
         test-issues  (when (= :failed test-status)
                        (tests->issues (:failures tests)))
         incomplete   (when (= :error test-status)
                        [{:step :tests :severity :warning :kind :verify-incomplete
                          :message (or (:note tests) "Affected tests could not be run.")}])
         ;; The FC/IS step can fail to run rather than fail to pass — a
         ;; malformed .wagoe/check-fcis.edn, for one. Reading only :violations
         ;; would report that as :ok, which is the step saying "clean" about
         ;; files it never looked at.
         fcis-broken  (when (:error fcis)
                        [{:step :fcis :severity :warning :kind :verify-incomplete
                          :message (:error fcis)}])
         issues       (vec (concat gen-issues
                                   kondo-issues
                                   (fcis->issues (:violations fcis))
                                   test-issues
                                   incomplete
                                   fcis-broken))
         errors       (filterv #(= :error (:severity %)) issues)
         warnings     (filterv #(= :warning (:severity %)) issues)
         ;; Soft = every blocking issue carries an overridable BND code. A hard
         ;; issue (kondo error, test failure, generation error) can never be
         ;; overridden away.
         soft?        (and (seq errors)
                           (every? #(contains? soft-codes (:code %)) errors))
         status       (cond
                        (empty? errors)            :pass
                        (and soft? overridden?)    :overridden
                        :else                      :fail)
         ;; Verification is complete only if the tests step actually ran (or was
         ;; not part of this loop at all). :error / :unavailable both mean the
         ;; affected tests were not exercised.
         complete?    (and (or (nil? tests)
                               (contains? #{:passed :failed} test-status))
                           (not (:error fcis)))]
     (cond-> {:status    status
              :complete? complete?
              :issues    issues
              :counts    {:errors (count errors) :warnings (count warnings)}
              :steps     (cond-> {}
                           generate (assoc :generate (if (:success generate) :ok :error))
                           kondo    (assoc :kondo (if (some #(= :error (:severity %)) kondo-issues)
                                                    :error :ok))
                           fcis     (assoc :fcis (cond
                                                     (:error fcis)             :error
                                                     (seq (:violations fcis))  :error
                                                     :else                     :ok))
                           tests    (assoc :tests (or test-status :unknown)))}
       (and (= :fail status) soft?) (assoc :overridable? true)))))

(defn passed?
  "Did the verify loop pass (no blocking issues, or all blocking issues were
   soft and overridden)?"
  [report]
  (contains? #{:pass :overridden} (:status report)))
