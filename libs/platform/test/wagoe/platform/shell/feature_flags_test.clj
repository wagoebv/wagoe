(ns wagoe.platform.shell.feature-flags-test
  "Reading feature flags from the process environment.

   The split exists because `wagoe.core.config.feature-flags` promised no side
   effects in its docstring while five of its functions called
   `(System/getenv)` in a convenience arity (BOU-302). Deciding what a flag
   means is pure; fetching the environment is not."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.config.feature-flags :as flags]
            [wagoe.platform.shell.feature-flags :as sut]))

(deftest ^:unit the-environment-is-read-here-and-nowhere-below
  (testing "env returns the process environment as a map"
    (let [e (sut/env)]
      (is (map? e))
      ;; PATH is set in every environment this runs in, CI included.
      (is (contains? e "PATH"))))

  (testing "and the core namespace no longer offers an arity that reads it"
    ;; Asserted on the arglists rather than by calling the missing arity:
    ;; clj-kondo resolves those calls and reports them as errors, so a test
    ;; written that way fails the lint gate to prove a point the signature
    ;; already makes.
    (doseq [[v expected] {#'flags/enabled?           '([flag-key env-map])
                          #'flags/all-flags          '([env-map])
                          #'flags/flag-info          '([flag-key env-map])
                          #'flags/add-flags-to-config '([config env-map])
                          #'flags/get-env-value      '([env-var env-map])}]
      (is (= expected (:arglists (meta v)))
          (str (:name (meta v)) " must take the environment, not fetch it")))))

(deftest ^:unit the-shell-answers-the-same-question-as-the-core
  ;; The convenience must not become a second implementation: it fetches the
  ;; map and hands it over.
  (let [e (sut/env)]
    (is (= (flags/enabled? :devex-validation e) (sut/enabled? :devex-validation)))
    (is (= (flags/all-flags e) (sut/all-flags)))
    (is (= (flags/flag-info :devex-validation e) (sut/flag-info :devex-validation)))
    (is (= (flags/add-flags-to-config {:a 1} e) (sut/add-flags-to-config {:a 1})))))

(deftest ^:unit an-unset-flag-is-off
  (testing "the default from known-flags applies when the variable is absent"
    (is (false? (flags/enabled? :devex-validation {}))))

  (testing "and an unknown flag is off rather than an error"
    (is (false? (flags/enabled? :no-such-flag {})))))
