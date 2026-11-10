(ns wagoe.user.shell.account-audit-test
  "A user's role and active state are audited in the change's transaction, for
   the rights timeline; their other fields best-effort (BOU-960)."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.test.logging :refer [with-silent-logging]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as group-service]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]))

(defn- failing-audit
  "`audit`, refusing every entry while `@down?`."
  [audit down?]
  (reify
    ports/IUserAuditRepository
    (create-audit-log [_ entry]
      (when @down? (throw (ex-info "audit down" {:type :internal-error})))
      (ports/create-audit-log audit entry))
    (find-audit-logs [_ opts] (ports/find-audit-logs audit opts))
    (find-audit-logs-by-user [_ id opts] (ports/find-audit-logs-by-user audit id opts))))

(defn- with-services [f]
  (test-db/with-db "user_account_audit"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users (persistence/create-user-repository ctx)
            audit (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
            down? (atom false)
            sessions (persistence/create-session-repository ctx)
            svc   (user-service/create-user-service users sessions (failing-audit audit down?) {} nil)
            groups (group-service/create-group-service (group-persistence/create-group-repository ctx)
                                                       users audit (group-service/prepare-settings nil) {})
            user! #(ports/create-user users {:email % :name "Member" :role :user :active true
                                             :password-hash "x"})]
        (f {:svc svc :groups groups :users users :sessions sessions :audit audit :down? down? :user! user!})))))

(deftest ^:integration a-failed-rights-audit-rolls-the-change-back
  (with-services
    (fn [{:keys [svc users down? user!]}]
      (let [ada    (user! "ada@example.com")
            access #(select-keys (ports/find-user-by-id users (:id ada)) [:role :active])
            stored (access)]
        (reset! down? true)
        (doseq [[what change] {"role"       #(ports/update-user-profile svc {:id (:id ada) :role :admin})
                               "active"     #(ports/update-user-profile svc {:id (:id ada) :active false})
                               "deactivate" #(ports/deactivate-user svc (:id ada))}]
          (testing what
            (is (thrown-with-msg? Exception #"audit down" (with-silent-logging (change))))
            (is (= stored (access)))))))))

(deftest ^:integration a-failed-profile-audit-keeps-the-change
  (with-services
    (fn [{:keys [svc users down? user!]}]
      (let [ada (user! "ada@example.com")]
        (reset! down? true)
        (is (= "Ada" (:name (with-silent-logging (ports/update-user-profile svc {:id (:id ada) :name "Ada"})))))
        (is (= "Ada" (:name (ports/find-user-by-id users (:id ada)))))))))

(deftest ^:integration role-and-active-changes-are-in-the-rights-timeline
  (with-services
    (fn [{:keys [svc groups user!]}]
      (let [ada (user! "ada@example.com")]
        ;; Another admin, so ada's deactivation is not refused
        (ports/update-user-profile svc {:id (:id (user! "boss@example.com")) :role :admin})
        (ports/update-user-profile svc {:id (:id ada) :role :admin})
        (Thread/sleep 5)
        (ports/update-user-profile svc {:id (:id ada) :active false})
        (let [changes (ports/rights-changes groups ada 10)]
          (is (= [:deactivate :role-change] (map :action changes)))
          (is (= {:old nil :new "admin"} (:user-role (second changes)))))))))

(deftest ^:integration a-failed-deactivation-audit-still-ends-the-sessions
  ;; A store whose admin lock did not roll back must not leave the user signed in
  (with-services
    (fn [{:keys [svc sessions down? user!]}]
      (let [ada (user! "ada@example.com")]
        (ports/create-session sessions {:user-id       (:id ada)
                                        :session-token (str "token-" (random-uuid))
                                        :expires-at    (.plusSeconds (java.time.Instant/now) 3600)})
        (reset! down? true)
        (is (thrown-with-msg? Exception #"audit down" (with-silent-logging (ports/deactivate-user svc (:id ada)))))
        (is (empty? (ports/find-sessions-by-user sessions (:id ada))))))))

(deftest ^:integration without-a-bound-actor-the-system-changed-the-role
  ;; Not the user: a promotion must not read as their own
  (with-services
    (fn [{:keys [svc audit user!]}]
      (let [ada (user! "ada@example.com")]
        (ports/update-user-profile svc {:id (:id ada) :role :admin :name "Ada"})
        (is (= #{[nil "system"]}
               (set (map (juxt :actor-id :actor-email)
                         (ports/find-audit-logs-by-user audit (:id ada) {:limit 10})))))))))
