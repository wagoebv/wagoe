(ns wagoe.tools.deps-test
  "An unreachable registry must not read as \"nothing newer\".

   Both lookups caught every failure into nil, and nil meant not-outdated, so
   a Clojars or Central outage produced `✓ All dependencies are up to date.`
   and exit 0 — the one answer a drift report must never give when it did not
   manage to look (BOU-443 review).

   The other half: file discovery covered root and libs/* only, so the example
   projects — real manifests someone copies from — were silently outside every
   sweep."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.deps :as deps]))

(def ^:private latest-version #'deps/latest-version)
(def ^:private newer? #'deps/newer?)
(def ^:private unresolved #'deps/unresolved)
(def ^:private find-deps-files #'deps/find-deps-files)

(defn- responding
  "A stub http/get returning `resp` for every URL."
  [resp]
  (fn [_url & _] resp))

(def ^:private a-release
  (json/generate-string {:latest_release "9.9.9"}))

(deftest ^:unit an-unreachable-registry-is-not-an-up-to-date-answer
  (testing "transport failure — both registries throw"
    (with-redefs [http/get (fn [& _] (throw (java.io.IOException. "connection refused")))]
      (is (= ::deps/unavailable (latest-version 'ring/ring-core)))))

  (testing "the registry answers, but not with an artifact — 503 from both"
    (with-redefs [http/get (responding {:status 503 :body "upstream"})]
      (is (= ::deps/unavailable (latest-version 'ring/ring-core)))))

  (testing "a coordinate no registry carries is absent, which is a real answer"
    (with-redefs [http/get (responding {:status 404 :body ""})]
      (is (= ::deps/absent (latest-version 'nobody/nothing)))))

  (testing "and a reachable registry still returns the version"
    (with-redefs [http/get (responding {:status 200 :body a-release})]
      (is (= "9.9.9" (latest-version 'ring/ring-core))))))

(deftest ^:unit an-unavailable-lookup-is-never-reported-as-current
  (testing "`newer?` must not treat the markers as versions — they sort as
            strings and ::unavailable would otherwise compare as something"
    (is (not (newer? ::deps/unavailable "1.0.0")))
    (is (not (newer? ::deps/absent "1.0.0")))
    (is (newer? "2.0.0" "1.0.0"))
    (is (not (newer? "1.0.0" "2.0.0"))))

  (testing "and they are counted, so the caller can tell a failed sweep apart"
    (let [m {'a "1.0.0" 'b ::deps/unavailable 'c ::deps/absent 'd ::deps/unavailable}]
      (is (= ['b 'd] (unresolved m)))
      (is (empty? (unresolved {'a "1.0.0" 'c ::deps/absent}))
          "a clean sweep must report zero, or the exit code is always non-zero"))))

(deftest ^:unit the-sweep-covers-the-example-manifests
  (let [paths (map str (find-deps-files))]

    (testing "the root and the libraries are still there"
      (is (some #(str/ends-with? % "/deps.edn") paths))
      (is (< 20 (count (filter #(str/includes? % "/libs/") paths)))))

    (testing "and the examples, which nothing else reports"
      ;; examples/todo sat two Clojure releases behind and was found by hand.
      (doseq [example ["examples/shop/deps.edn" "examples/todo/deps.edn"]]
        (is (some #(str/ends-with? % example) paths)
            (str example " is outside the sweep"))))

    (testing "the scaffolder's template directory stays out"
      (is (not-any? #(str/includes? % "existing-dir") paths)))))

(deftest ^:unit neither-command-reports-success-after-a-failed-sweep
  ;; Table-driven over both modes on purpose. cmd-check was fixed and cmd-update
  ;; was not, so `--update` went on exiting 0 and printing "All dependencies are
  ;; already up to date" after reaching no registry at all (BOU-443 review).
  ;; Anything added here has to answer for both.
  (doseq [[mode cmd] [["check"  #'deps/cmd-check]
                      ["update" #'deps/cmd-update]]]
    (testing mode

      (let [run (fn [answer]
                  ;; Returns [return-value printed-output]. upgrade-file! is
                  ;; stubbed so --update never writes during a test.
                  (let [result (atom nil)
                        out (with-redefs-fn
                              {#'deps/fetch-all-latest
                               (fn [coords] (into {} (map (fn [c] [c answer]) coords)))
                               #'deps/upgrade-file! (fn [& _] nil)}
                              (fn [] (with-out-str (reset! result (cmd nil)))))]
                    [@result out]))]

        (testing "an unreachable registry is counted and returned, so -main exits non-zero"
          (let [[failed out] (run ::deps/unavailable)]
            (is (pos? failed)
                "returned zero, so the caller cannot tell this sweep from a clean one")
            (is (not (str/includes? out "up to date."))
                (str "claimed currency after reaching no registry:\n" out))
            (is (str/includes? out "lookups failed")
                "said nothing about the lookups that did not happen")))

        (testing "and a sweep that did reach everything returns zero"
          (let [[failed _] (run "0.0.1")]
            (is (zero? failed)
                "a reachable sweep must return zero, or the exit code is always non-zero")))))))

(deftest ^:unit a-generated-manifest-is-reported-but-not-rewritten
  (testing "examples/shop comes from the wagoe-cli template, so writing a
            version into it is undone by the next bb example:regen and fails
            bb example:regen --check until then"
    (is (contains? @#'deps/generated-deps-files "examples/shop"))
    (is (not (contains? @#'deps/generated-deps-files "examples/todo"))
        "todo is hand-maintained — excluding it would hide real drift"))

  (testing "and --update actually consults that, rather than the set merely
            existing: every coordinate is reported outdated and the files it
            would write are recorded instead of written"
    ;; Asserted through cmd-update, because the first attempt to prove this by
    ;; staling a version in examples/shop proved nothing — the coordinate
    ;; appears twice in that file and `coords-from-file` is a map, so the
    ;; fresher entry won and the generated branch was never reached.
    (let [written (atom [])]
      (with-redefs-fn
        {#'deps/fetch-all-latest (fn [coords] (into {} (map (fn [c] [c "999.0.0"]) coords)))
         #'deps/upgrade-file!    (fn [f _ _] (swap! written conj (str f)))}
        (fn [] (with-out-str (deps/cmd-update nil))))

      (is (seq @written) "nothing was upgraded at all — this would pass vacuously")

      (is (not-any? #(str/includes? % "examples/shop") @written)
          "examples/shop was rewritten; the next bb example:regen undoes it")

      (is (some #(str/includes? % "examples/todo") @written)
          "examples/todo is hand-maintained and must still be upgraded"))))
