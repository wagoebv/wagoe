(ns wagoe.user.shell.authz-negative-security-test
  "Dedicated ^:security suite for HTTP authorization negative paths (BOU-168
   item 4). The individual interceptors are already ^:contract-tested; this
   suite consolidates the *deny* cases under a threat framing so the security
   guarantees are addressable via `--focus-meta :security`:

   - RBAC: wrong role / missing identity -> 403
   - IDOR (broken object-level auth): a non-admin cannot reach another user's
     resource by changing the :id path param -> 403
   - Cross-tenant: no / inactive / wrong-role tenant membership -> 403

   All guards short-circuit by assoc-ing a 403 :response onto the context, so a
   deny is `(= 403 (get-in result [:response :status]))` and an allow leaves
   `:response` nil."
  (:require [wagoe.user.shell.http-interceptors :as http-int]
            [wagoe.user.shell.middleware]
            [wagoe.user.shell.web-handlers]
            [clojure.test :refer [deftest is testing]]))

(defn- ctx
  "Build an interceptor context. opts: :user, :path-params, :tenant-membership."
  [{:keys [user path-params tenant-membership]}]
  {:request        (cond-> {:uri "/protected"}
                     user             (assoc :user user)
                     tenant-membership (assoc :tenant-membership tenant-membership))
   :path-params    (or path-params {})
   :system         {:logger nil :metrics-emitter nil}
   :correlation-id "sec-test"})

(defn- denied? [result] (= 403 (get-in result [:response :status])))
(defn- allowed? [result] (nil? (:response result)))

(defn- run [interceptor c] ((:enter interceptor) c))

;; =============================================================================
;; RBAC negative paths
;; =============================================================================

(deftest ^:security ^:unit require-admin-denies-non-admin
  (testing "a plain user is forbidden on an admin-guarded route"
    (is (denied? (run http-int/require-admin (ctx {:user {:id "u1" :role :user}})))))
  (testing "a missing/anonymous identity is forbidden (fails closed)"
    (is (denied? (run http-int/require-admin (ctx {})))))
  (testing "an admin passes (bounds the negative case)"
    (is (allowed? (run http-int/require-admin (ctx {:user {:id "a1" :role :admin}}))))))

(deftest ^:security ^:unit require-role-denies-wrong-role
  (testing "a user without the required role is forbidden"
    (is (denied? (run (http-int/require-role :manager)
                      (ctx {:user {:id "u1" :role :user}})))))
  (testing "the matching role passes"
    (is (allowed? (run (http-int/require-role :manager)
                       (ctx {:user {:id "m1" :role :manager}}))))))

;; =============================================================================
;; IDOR — broken object-level authorization
;; =============================================================================

(deftest ^:security ^:unit require-self-or-admin-blocks-idor
  (testing "a non-admin cannot reach another user's resource by changing the :id"
    (is (denied? (run http-int/require-self-or-admin
                      (ctx {:user        {:id "user-A" :role :user}
                            :path-params {:id "user-B"}})))))
  (testing "the owner reaches their own resource"
    (is (allowed? (run http-int/require-self-or-admin
                       (ctx {:user        {:id "user-A" :role :user}
                             :path-params {:id "user-A"}})))))
  (testing "an admin may reach any user's resource"
    (is (allowed? (run http-int/require-self-or-admin
                       (ctx {:user        {:id "admin-1" :role :admin}
                             :path-params {:id "user-B"}}))))))

;; =============================================================================
;; Cross-tenant access denial (membership filter — H2-compatible path)
;; =============================================================================

(deftest ^:security ^:unit require-tenant-member-denies-non-member
  (testing "no tenant membership on the request is forbidden (fails closed)"
    (is (denied? (run http-int/require-tenant-member (ctx {:user {:id "u1" :role :user}})))))
  (testing "an inactive membership is forbidden"
    (is (denied? (run http-int/require-tenant-member
                      (ctx {:tenant-membership {:status :suspended :role :admin}})))))
  (testing "an active member passes"
    (is (allowed? (run http-int/require-tenant-member
                       (ctx {:tenant-membership {:status :active :role :member}}))))))

(deftest ^:security ^:unit require-tenant-admin-denies-wrong-tenant-role
  (testing "an active member without the tenant :admin role is forbidden"
    (is (denied? (run http-int/require-tenant-admin
                      (ctx {:tenant-membership {:status :active :role :member}})))))
  (testing "a tenant admin passes"
    (is (allowed? (run http-int/require-tenant-admin
                       (ctx {:tenant-membership {:status :active :role :admin}}))))))

;; =============================================================================
;; The role held where the request runs (BOU-1020)
;; =============================================================================

(def ^:private tenant-a #uuid "00000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "00000000-0000-0000-0000-00000000000b")

(defn- in-b
  "A context for a request running in tenant B."
  [user membership-role]
  (assoc-in (ctx {:user user :tenant-membership (when membership-role {:role membership-role :status :active})})
            [:request :tenant] {:id tenant-b}))

(deftest ^:security ^:unit an-admin-elsewhere-is-no-admin-here
  ;; An admin of tenant A is only a member in tenant B: the guards read the
  ;; role held in B, not the home role (BOU-1020)
  (let [a-admin  {:id "a1" :role :admin :tenant-id tenant-a}
        b-member {:id "b1" :role :user :tenant-id tenant-a}]
    (testing "require-admin"
      (is (denied? (run http-int/require-admin (in-b a-admin :member))))
      (is (allowed? (run http-int/require-admin (in-b b-member :admin))) "an admin of B by membership"))
    (testing "require-role"
      (is (denied? (run (http-int/require-role :admin) (in-b a-admin :member))))
      (is (allowed? (run (http-int/require-role :admin) (in-b b-member :admin)))))
    (testing "require-self-or-admin"
      (is (denied? (run http-int/require-self-or-admin
                        (assoc (in-b a-admin :member) :path-params {:id "someone-in-b"})))))
    (testing "require-role-middleware"
      (let [mw    (wagoe.user.shell.middleware/require-admin-middleware (constantly {:status 200}))
            req-b #(:request (in-b %1 %2))]
        (is (= 403 (:status (mw (req-b a-admin :member)))))
        (is (= 200 (:status (mw (req-b b-member :admin)))))))
    (testing "hard-deleting a user"
      (let [h (wagoe.user.shell.web-handlers/hard-delete-user-handler nil {})]
        (is (= 403 (:status (h (assoc (:request (in-b a-admin :member))
                                      :path-params {:id (str (random-uuid))}))))))))) 
