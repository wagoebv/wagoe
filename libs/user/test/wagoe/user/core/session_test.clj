(ns wagoe.user.core.session-test
  "Unit tests for pure session core functions."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.user.core.session :as session])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Fixed test values
;; =============================================================================

(def ^:private now   (Instant/parse "2026-01-15T12:00:00Z"))
(def ^:private later (Instant/parse "2026-01-15T14:00:00Z"))  ; +2h
(def ^:private far   (Instant/parse "2026-01-16T12:00:00Z"))  ; +24h

(def ^:private session-id #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private user-id    #uuid "00000000-0000-0000-0000-000000000002")

(defn- make-session
  ([] (make-session {}))
  ([overrides]
   (merge {:id session-id
           :user-id user-id
           :session-token "token-123"
           :created-at now
           :last-accessed-at now
           :expires-at far
           :ip-address "1.2.3.4"
           :user-agent "Chrome/120 Desktop"
           :revoked-at nil}
          overrides)))

;; =============================================================================
;; validate-session-creation-request
;; =============================================================================

(deftest ^:unit validate-session-creation-request-test
  (testing "valid request with user-id"
    (let [result (session/validate-session-creation-request {:user-id user-id})]
      (is (true? (:valid? result)))))

  (testing "invalid when missing user-id"
    (let [result (session/validate-session-creation-request {})]
      (is (false? (:valid? result)))))

  (testing "invalid for non-map input"
    (let [result (session/validate-session-creation-request nil)]
      (is (false? (:valid? result))))))

;; =============================================================================
;; is-session-valid?
;; =============================================================================

(deftest ^:unit is-session-valid?-test
  (testing "nil session is not valid"
    (let [result (session/is-session-valid? nil now)]
      (is (false? (:valid? result)))
      (is (= :not-found (:reason result)))))

  (testing "revoked session is not valid"
    (let [sess (make-session {:revoked-at now})
          result (session/is-session-valid? sess later)]
      (is (false? (:valid? result)))
      (is (= :inactive (:reason result)))))

  (testing "expired session is not valid"
    (let [sess (make-session {:expires-at now})
          result (session/is-session-valid? sess later)]
      (is (false? (:valid? result)))
      (is (= :expired (:reason result)))))

  (testing "valid non-expired, non-revoked session"
    (let [sess (make-session)
          result (session/is-session-valid? sess now)]
      (is (true? (:valid? result))))))

;; =============================================================================
;; generate-session-token
;; =============================================================================

(deftest ^:unit generate-session-token-test
  (testing "produces deterministic token string"
    (let [token (session/generate-session-token 1000 42)]
      (is (= "token-1000-42" token))))

  (testing "produces different tokens for different inputs"
    (let [t1 (session/generate-session-token 1000 1)
          t2 (session/generate-session-token 1000 2)
          t3 (session/generate-session-token 2000 1)]
      (is (not= t1 t2))
      (is (not= t1 t3)))))

;; =============================================================================
;; calculate-session-expiry
;; =============================================================================

(deftest ^:unit calculate-session-expiry-test
  (testing "standard session expires in 24 hours"
    (let [expiry (session/calculate-session-expiry now false)]
      (is (= (.plusSeconds now (* 24 3600)) expiry))))

  (testing "remember-me session expires in 30 days"
    (let [expiry (session/calculate-session-expiry now true)]
      (is (= (.plusSeconds now (* 30 24 3600)) expiry))))

  (testing "custom hours override remember-me"
    (let [expiry (session/calculate-session-expiry now false 48)]
      (is (= (.plusSeconds now (* 48 3600)) expiry)))))

;; =============================================================================
;; prepare-session-for-creation
;; =============================================================================

(deftest ^:unit prepare-session-for-creation-test
  (testing "sets all required fields"
    (let [data {:user-id user-id
                :remember-me false
                :device-info {:ip-address "5.6.7.8" :user-agent "Firefox/100"}}
          sess (session/prepare-session-for-creation data now session-id "tok-1")]
      (is (= session-id (:id sess)))
      (is (= "tok-1" (:session-token sess)))
      (is (= now (:created-at sess)))
      (is (= now (:last-accessed-at sess)))
      (is (= "5.6.7.8" (:ip-address sess)))
      (is (= "Firefox/100" (:user-agent sess)))
      (is (some? (:expires-at sess)))))

  (testing "with session policy uses custom duration"
    (let [data {:user-id user-id :device-info {}}
          policy {:duration-hours 8}
          sess (session/prepare-session-for-creation data now session-id "tok-1" policy)]
      (is (= (.plusSeconds now (* 8 3600)) (:expires-at sess))))))

;; =============================================================================
;; should-extend-session?
;; =============================================================================

(deftest ^:unit should-extend-session?-test
  (testing "session near expiry should be extended"
    ;; expires in 1 hour, threshold is 2 hours → should extend
    (let [expires-soon (Instant/parse "2026-01-15T13:00:00Z")  ; 1h from now
          sess (make-session {:expires-at expires-soon})
          policy {:extend-threshold-hours 2}]
      (is (true? (session/should-extend-session? sess now policy)))))

  (testing "session not near expiry should not be extended"
    ;; expires in 10 hours, threshold is 2 hours → don't extend
    (let [expires-later (Instant/parse "2026-01-15T22:00:00Z")  ; 10h from now
          sess (make-session {:expires-at expires-later})
          policy {:extend-threshold-hours 2}]
      (is (false? (session/should-extend-session? sess now policy))))))

;; =============================================================================
;; update-session-access
;; =============================================================================

(deftest ^:unit update-session-access-test
  (testing "updates last-accessed-at"
    (let [sess (make-session)
          updated (session/update-session-access sess later)]
      (is (= later (:last-accessed-at updated)))))

  (testing "extends expiry when session is near expiry"
    (let [expires-in-1h (Instant/parse "2026-01-15T13:00:00Z")
          sess (make-session {:expires-at expires-in-1h})
          updated (session/update-session-access sess now)]
      ;; should-extend-session? fires → expires-at is recalculated
      (is (.isAfter (:expires-at updated) expires-in-1h)))))

;; =============================================================================
;; should-cleanup-session?
;; =============================================================================

(deftest ^:unit should-cleanup-session?-test
  (testing "session expired beyond grace period should be cleaned up"
    ;; now is Jan 15, expires-at is Jan 1 (14 days ago > 7-day grace)
    (let [old-expiry (Instant/parse "2026-01-01T00:00:00Z")
          sess (make-session {:expires-at old-expiry})
          policy {:cleanup-grace-period-days 7}]
      (is (true? (session/should-cleanup-session? sess now policy)))))

  (testing "session expired within grace period should not be cleaned up"
    ;; expires-at is Jan 13 (2 days ago < 7-day grace)
    (let [recent-expiry (Instant/parse "2026-01-13T12:00:00Z")
          sess (make-session {:expires-at recent-expiry})
          policy {:cleanup-grace-period-days 7}]
      (is (false? (session/should-cleanup-session? sess now policy))))))

;; =============================================================================
;; mark-session-for-cleanup / filter-sessions-for-cleanup
;; =============================================================================

(deftest ^:unit mark-session-for-cleanup-test
  (testing "sets revoked-at to current-time"
    (let [sess (make-session)
          marked (session/mark-session-for-cleanup sess now)]
      (is (= now (:revoked-at marked))))))

(deftest ^:unit filter-sessions-for-cleanup-test
  (testing "returns only sessions past grace period"
    (let [old-expiry (Instant/parse "2026-01-01T00:00:00Z")
          new-expiry (Instant/parse "2026-01-13T12:00:00Z")
          old-sess (make-session {:expires-at old-expiry})
          new-sess (make-session {:expires-at new-expiry :id (UUID/randomUUID)})
          result (session/filter-sessions-for-cleanup [old-sess new-sess] now)]
      (is (= 1 (count result)))
      (is (= session-id (:id (first result)))))))

;; =============================================================================
;; detect-suspicious-activity?
;; =============================================================================

(deftest ^:unit detect-suspicious-activity?-test
  (testing "IP change is suspicious"
    (let [sess (make-session {:ip-address "9.9.9.9"})
          prev [(make-session {:ip-address "1.2.3.4"})]
          result (session/detect-suspicious-activity? sess prev)]
      (is (seq (:suspicious? result)))
      (is (contains? (set (:reasons result)) :ip-change))))

  (testing "same IP is not suspicious by IP criterion"
    (let [sess (make-session {:ip-address "1.2.3.4"})
          prev [(make-session {:ip-address "1.2.3.4"})]
          result (session/detect-suspicious-activity? sess prev)]
      (is (not (contains? (set (:reasons result)) :ip-change))))))

;; =============================================================================
;; calculate-session-risk-score
;; =============================================================================

(deftest ^:unit calculate-session-risk-score-test
  (testing "base score is present"
    (let [sess (make-session)
          score (session/calculate-session-risk-score sess [])]
      (is (number? score))
      (is (>= score 0.0))))

  (testing "IP change increases risk score"
    (let [sess (make-session {:ip-address "9.9.9.9"})
          prev [(make-session {:ip-address "1.2.3.4"})]
          score (session/calculate-session-risk-score sess prev)]
      (is (> score 0.4)))))

;; =============================================================================
;; should-require-additional-verification?
;; =============================================================================

(deftest ^:unit should-require-additional-verification?-test
  (testing "returns false for low-risk session"
    (let [sess (make-session)
          prev [(make-session)]]
      (is (false? (session/should-require-additional-verification? sess prev)))))

  (testing "returns true when risk score > 0.5"
    (let [sess (make-session {:ip-address "9.9.9.9"})
          prev [(make-session {:ip-address "1.2.3.4"})]]
      (is (true? (session/should-require-additional-verification? sess prev))))))

;; =============================================================================
;; analyze-session-duration
;; =============================================================================

(deftest ^:unit analyze-session-duration-test
  (testing "returns session-id and duration in hours"
    (let [sess (make-session {:created-at (Instant/parse "2026-01-15T10:00:00Z")
                              :last-accessed-at (Instant/parse "2026-01-15T12:00:00Z")})
          result (session/analyze-session-duration sess now)]
      (is (= session-id (:session-id result)))
      (is (= 2.0 (:duration-hours result))))))

;; =============================================================================
;; group-sessions-by-device-type
;; =============================================================================

(deftest ^:unit group-sessions-by-device-type-test
  (let [mobile-sess (make-session {:user-agent "iPhone Mobile/15"})
        desktop-sess (make-session {:user-agent "Chrome/120 Desktop" :id (UUID/randomUUID)})
        other-sess (make-session {:user-agent "curl/7.0" :id (UUID/randomUUID)})
        grouped (session/group-sessions-by-device-type [mobile-sess desktop-sess other-sess])]

    (testing "groups mobile sessions"
      (is (= 1 (count (:mobile grouped)))))

    (testing "groups desktop sessions"
      (is (= 1 (count (:desktop grouped)))))

    (testing "groups other sessions"
      (is (= 1 (count (:other grouped)))))))

;; =============================================================================
;; calculate-user-session-stats
;; =============================================================================

(deftest ^:unit calculate-user-session-stats-test
  (let [id2 (UUID/randomUUID)
        id3 (UUID/randomUUID)
        other-user (UUID/randomUUID)
        s1 (make-session {:id session-id :user-id user-id})
        s2 (make-session {:id id2 :user-id user-id :revoked-at now})
        s3 (make-session {:id id3 :user-id other-user})
        stats (session/calculate-user-session-stats [s1 s2 s3] user-id)]

    (testing "counts all sessions for user"
      (is (= 2 (:total-sessions stats))))

    (testing "counts active sessions"
      (is (= 1 (:active-sessions stats))))

    (testing "counts inactive sessions"
      (is (= 1 (:inactive-sessions stats))))))

;; =============================================================================
;; extract-device-info
;; =============================================================================

(deftest ^:unit extract-device-info-test
  (testing "detects iPhone as mobile"
    (let [info (session/extract-device-info "iPhone/15" "1.2.3.4")]
      (is (= :mobile (:device-type info)))))

  (testing "detects other Mobile agent as mobile"
    (let [info (session/extract-device-info "Android Mobile/10" "1.2.3.4")]
      (is (= :mobile (:device-type info)))))

  (testing "detects desktop by default"
    (let [info (session/extract-device-info "Chrome/120" "1.2.3.4")]
      (is (= :desktop (:device-type info)))))

  (testing "stores ip-address and user-agent"
    (let [info (session/extract-device-info "Chrome/120" "5.5.5.5")]
      (is (= "5.5.5.5" (:ip-address info)))
      (is (= "Chrome/120" (:user-agent info))))))

;; =============================================================================
;; is-same-device?
;; =============================================================================

(deftest ^:unit is-same-device?-test
  (testing "same IP and user-agent is same device"
    (let [d1 {:ip-address "1.1.1.1" :user-agent "Chrome/120"}
          d2 {:ip-address "1.1.1.1" :user-agent "Chrome/120"}]
      (is (true? (session/is-same-device? d1 d2)))))

  (testing "different IP is different device"
    (let [d1 {:ip-address "1.1.1.1" :user-agent "Chrome/120"}
          d2 {:ip-address "9.9.9.9" :user-agent "Chrome/120"}]
      (is (false? (session/is-same-device? d1 d2)))))

  (testing "minor version difference in user-agent still matches"
    (let [d1 {:ip-address "1.1.1.1" :user-agent "Chrome/120.0.1"}
          d2 {:ip-address "1.1.1.1" :user-agent "Chrome/121.0.0"}]
      (is (true? (session/is-same-device? d1 d2))))))

;; =============================================================================
;; should-update-access-time?
;; =============================================================================

(deftest ^:unit should-update-access-time?-test
  (testing "returns true when enough time has passed"
    (let [last-access (Instant/parse "2026-01-15T11:50:00Z") ; 10 min ago
          sess (make-session {:last-accessed-at last-access})
          policy {:access-update-threshold-minutes 5}]
      (is (true? (session/should-update-access-time? sess now policy)))))

  (testing "returns false when not enough time has passed"
    (let [last-access (Instant/parse "2026-01-15T11:58:00Z") ; 2 min ago
          sess (make-session {:last-accessed-at last-access})
          policy {:access-update-threshold-minutes 5}]
      (is (false? (session/should-update-access-time? sess now policy))))))

;; =============================================================================
;; prepare-session-for-access-update / prepare-session-for-invalidation
;; =============================================================================

(deftest ^:unit prepare-session-for-access-update-test
  (testing "sets last-accessed-at to current-time"
    (let [sess (make-session)
          updated (session/prepare-session-for-access-update sess later)]
      (is (= later (:last-accessed-at updated))))))

(deftest ^:unit prepare-session-for-invalidation-test
  (testing "sets revoked-at to current-time"
    (let [sess (make-session)
          invalidated (session/prepare-session-for-invalidation sess later)]
      (is (= later (:revoked-at invalidated))))))
