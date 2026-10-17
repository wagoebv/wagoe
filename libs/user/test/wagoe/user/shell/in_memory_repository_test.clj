(ns wagoe.user.shell.in-memory-repository-test
  "A complete non-JDBC implementation of the three user repository ports.

   BOU-366 narrowed those ports so that implementing them is an afternoon rather
   than a fortnight. This is what that claim costs, in full, and the test below
   drives the real UserService through it — so a method added to a port that the
   service starts calling fails here instead of at someone else's runtime."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.service :as service])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- active? [user] (nil? (:deleted-at user)))

(defrecord MemoryUserRepository [state]
  ports/IUserRepository

  (find-user-by-id [_ user-id]
    (let [user (get @state user-id)]
      (when (and user (active? user)) user)))

  (find-user-by-email [_ email]
    (first (filter #(and (active? %) (= email (:email %))) (vals @state))))

  (find-users [_ {:keys [limit offset include-deleted?]}]
    (let [all (cond->> (sort-by :created-at (vals @state))
                (not include-deleted?) (filter active?))]
      {:users (->> all (drop (or offset 0)) (take (or limit 20)) vec)
       :total-count (count all)}))

  (create-user [_ user-entity]
    (let [user (assoc user-entity
                      :id (or (:id user-entity) (UUID/randomUUID))
                      :created-at (or (:created-at user-entity) (Instant/now)))]
      (when (some #(= (:email user) (:email %)) (vals @state))
        (throw (ex-info "Email already registered"
                        {:type :user-exists :email (:email user)})))
      (swap! state assoc (:id user) user)
      user))

  (update-user [_ user-entity]
    (when-not (get @state (:id user-entity))
      (throw (ex-info "User not found"
                      {:type :user-not-found :user-id (:id user-entity)})))
    ;; Merged, as the database writes only the columns it is given.
    (let [user (assoc user-entity :updated-at (Instant/now))]
      (get (swap! state update (:id user) merge user) (:id user))))

  (increment-failed-login-count [_ user-id {:keys [max-attempts until]}]
    (when (get @state user-id)
      (get-in (swap! state update user-id
                     #(let [n (inc (or (:failed-login-count %) 0))]
                        (cond-> (assoc % :failed-login-count n)
                          (>= n max-attempts) (assoc :lockout-until until))))
              [user-id :failed-login-count])))

  (consume-backup-code [_ user code-hash]
    (let [seen #(select-keys % [:mfa-backup-codes :mfa-backup-codes-used])
          [old _] (swap-vals! state (fn [s]
                                      (cond-> s
                                        (= (seen user) (seen (get s (:id user))))
                                        (update-in [(:id user) :mfa-backup-codes-used] (fnil conj []) code-hash))))]
      (= (seen user) (seen (get old (:id user))))))

  (soft-delete-user [_ user-id]
    (boolean (when (get @state user-id)
               (swap! state assoc-in [user-id :deleted-at] (Instant/now))
               true)))

  (hard-delete-user [_ user-id]
    (boolean (when (get @state user-id)
               (swap! state dissoc user-id)
               true)))

  (with-admins-locked [_ f] (f)))

(defrecord MemorySessionRepository [state]
  ports/IUserSessionRepository

  (create-session [_ session-entity]
    (let [session (assoc session-entity
                         :id (or (:id session-entity) (UUID/randomUUID))
                         :session-token (or (:session-token session-entity)
                                            (str (UUID/randomUUID))))]
      (swap! state assoc (:id session) session)
      session))

  (find-session-by-token [_ token]
    (when-let [session (first (filter #(and (= token (:session-token %))
                                            (nil? (:revoked-at %))
                                            (.isAfter ^Instant (:expires-at %) (Instant/now)))
                                      (vals @state)))]
      (let [touched (assoc session :last-accessed-at (Instant/now))]
        (swap! state assoc (:id session) touched)
        touched)))

  (find-sessions-by-user [_ user-id]
    (->> (vals @state)
         (filter #(and (= user-id (:user-id %))
                       (nil? (:revoked-at %))
                       (.isAfter ^Instant (:expires-at %) (Instant/now))))
         (sort-by :created-at)
         reverse
         vec))

  (find-all-sessions [_]
    (vec (reverse (sort-by :created-at (vals @state)))))

  (update-session [_ session-entity]
    (when (get @state (:id session-entity))
      (swap! state assoc (:id session-entity) session-entity)
      session-entity))

  (invalidate-session [_ token]
    (boolean (when-let [session (first (filter #(= token (:session-token %)) (vals @state)))]
               (swap! state assoc-in [(:id session) :revoked-at] (Instant/now))
               true)))

  (invalidate-user-session [_ user-id session-id]
    (let [s (get @state session-id)]
      (boolean (when (and s (= user-id (:user-id s)) (nil? (:revoked-at s)))
                 (swap! state assoc-in [session-id :revoked-at] (Instant/now))
                 true))))

  (invalidate-all-user-sessions [_ user-id]
    (let [live (filter #(and (= user-id (:user-id %)) (nil? (:revoked-at %)))
                       (vals @state))]
      (doseq [session live]
        (swap! state assoc-in [(:id session) :revoked-at] (Instant/now)))
      (count live)))

  (delete-sessions-expired-before [_ cutoff]
    (let [stale (filter #(.isBefore ^Instant (:expires-at %) ^Instant cutoff)
                        (vals @state))]
      (doseq [session stale]
        (swap! state dissoc (:id session)))
      (count stale))))

(defrecord MemoryAuditRepository [state]
  ports/IUserAuditRepository

  (create-audit-log [_ audit-entity]
    (let [entry (assoc audit-entity
                       :id (UUID/randomUUID)
                       :created-at (Instant/now))]
      (swap! state conj entry)
      entry))

  (find-audit-logs [_ {:keys [limit offset filter-action]}]
    (let [all (cond->> (reverse @state)
                filter-action (filter #(= filter-action (:action %))))]
      {:audit-logs (->> all (drop (or offset 0)) (take (or limit 50)) vec)
       :total-count (count all)}))

  (find-audit-logs-by-user [_ user-id {:keys [limit]}]
    (->> (reverse @state)
         (filter #(= user-id (:target-user-id %)))
         (take (or limit 50))
         vec)))

(defn- memory-service []
  (service/create-user-service (->MemoryUserRepository (atom {}))
                               (->MemorySessionRepository (atom {}))
                               (->MemoryAuditRepository (atom []))
                               {:password-policy {:min-length 12}}
                               nil))

(deftest ^:unit the-user-service-runs-on-a-repository-that-is-not-a-database
  (let [svc (memory-service)
        created (ports/register-user svc {:email "ada@example.com"
                                          :name "Ada Lovelace"
                                          :role :user
                                          :password "Correct-horse-battery-9"})
        user-id (:id created)]

    (testing "register-user persists and strips the hash"
      (is (some? user-id))
      (is (nil? (:password-hash created)))
      (is (= "ada@example.com" (:email created))))

    (testing "the user is readable back by id and by email"
      (is (= user-id (:id (ports/get-user-by-id svc user-id))))
      (is (= user-id (:id (ports/get-user-by-email svc "ada@example.com")))))

    (testing "list-users reports the page and the total"
      (is (= {:users [user-id] :total-count 1}
             (-> (ports/list-users svc {:limit 10 :offset 0})
                 (update :users #(mapv :id %))))))

    (testing "a second registration on the same email is refused"
      (is (= :user-exists
             (:type (ex-data (try (ports/register-user svc {:email "ada@example.com"
                                                            :name "Impostor"
                                                            :role :user
                                                            :password "Correct-horse-battery-9"})
                                  (catch clojure.lang.ExceptionInfo e e)))))))

    (testing "update-user-profile writes through"
      (is (= "Ada King" (:name (ports/update-user-profile
                                svc (assoc (ports/get-user-by-id svc user-id)
                                           :name "Ada King"))))))

    (testing "the audit trail recorded the registration and the update"
      (is (<= 2 (:total-count (ports/list-audit-logs svc {})))))

    (testing "deactivate-user keeps the user, inactive (BOU-681)"
      (is (true? (ports/deactivate-user svc user-id)))
      (is (false? (:active (ports/get-user-by-email svc "ada@example.com"))))
      (is (= 1 (:total-count (ports/list-users svc {})))))))

(deftest ^:unit sessions-survive-a-non-database-session-store
  (let [svc (memory-service)
        user (ports/register-user svc {:email "grace@example.com"
                                       :name "Grace Hopper"
                                       :role :user
                                       :password "Correct-horse-battery-9"})
        sessions (:session-repository svc)
        token (str (UUID/randomUUID))]

    (ports/create-session sessions {:user-id (:id user)
                                    :session-token token
                                    :created-at (Instant/now)
                                    :expires-at (.plusSeconds (Instant/now) 3600)})

    (testing "validate-session returns the session and refreshes access time"
      (let [session (ports/validate-session svc token)]
        (is (= (:id user) (:user-id session)))
        (is (some? (:last-accessed-at session)))))

    (testing "get-user-sessions lists it"
      (is (= 1 (count (ports/get-user-sessions svc (:id user))))))

    (testing "logout-user revokes it"
      (is (true? (:invalidated (ports/logout-user svc token))))
      (is (nil? (ports/validate-session svc token)))
      (is (empty? (ports/get-user-sessions svc (:id user)))))

    (testing "an expired session never validates"
      (let [stale (str (UUID/randomUUID))]
        (ports/create-session sessions {:user-id (:id user)
                                        :session-token stale
                                        :created-at (Instant/now)
                                        :expires-at (.minusSeconds (Instant/now) 1)})
        (is (nil? (ports/validate-session svc stale)))))))
