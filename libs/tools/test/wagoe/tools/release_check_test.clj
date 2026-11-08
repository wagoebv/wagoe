(ns wagoe.tools.release-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.release-check :as sut]))

(def ^:private changelog
  "# Changelog

## [Unreleased]

### Security

- Something unreleased.

## [1.2.0] — 2026-12-01

### Added

- A feature.

### Security

None.

## [1.1.9] — 2026-11-20

### Added

- Another.

### Security, not quite

## [1.1.8] — 2026-11-10

### Fixed

- Last.
")

(deftest ^:unit a-release-section-needs-a-security-heading
  (testing "present, even when it says none"
    (is (sut/security-section? changelog "1.2.0")))
  (testing "absent: the next release's heading does not count for this one"
    (is (not (sut/security-section? changelog "1.1.9")))
    (is (not (sut/security-section? changelog "1.1.8"))))
  (testing "[Unreleased] has one, a version that is not there has none"
    (is (sut/security-section? changelog "Unreleased"))
    (is (not (sut/security-section? changelog "9.9.9")))))

(deftest ^:unit a-section-ends-at-the-next-release
  (is (= "\n### Fixed\n\n- Last.\n" (sut/changelog-section changelog "1.1.8")))
  (is (not (re-find #"1\.1\.8" (sut/changelog-section changelog "1.1.9")))))
