(ns wagoe.workflow.shell.http-test
  "The workflow API as it is actually served: the platform's router, the
   platform's middleware, and the authentication middleware that runs
   outermost in a real application.

   Both defects this covers were invisible to a hand-built request map
   (BOU-478). `actor-from-request` read `:identity`, a key nothing has set
   since BOU-373 moved the authenticated user to `:user`, so every request
   arrived with no actor and every permissioned transition was refused. And
   the routes declared no `:parameters`, so `[:parameters :body]` was nil
   whatever the caller sent — the transition came out nil and the rejection
   path answered `(name nil)`, a 500 for a request that is a 4xx."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.platform.shell.http.reitit-router :as reitit]
            [wagoe.user.shell.auth :as auth-shell]
            [wagoe.user.shell.middleware :as user-middleware]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.http :as sut]
            [wagoe.workflow.shell.registry :as registry]
            [wagoe.workflow.shell.service :as service]
            [wagoe.workflow.shell.service-test :as service-test])
  (:import [java.util UUID]))

;; =============================================================================
;; The workflow from the Conj demo: entered -> delivered needs a permission
;; =============================================================================

(def ^:private invoice-def
  {:id            :invoice-workflow
   :initial-state :entered
   :states        #{:entered :delivered :paid}
   :transitions   [{:from :entered   :to :delivered :required-permissions [:admin]}
                   {:from :delivered :to :paid}]})

(def ^:dynamic *handler* nil)
(def ^:dynamic *service* nil)

(defn- with-served-api
  "Compile the workflow API routes through the platform router and put the
   authentication middleware outermost, where `:auth-middleware` puts it."
  [f]
  (registry/clear-registry!)
  (registry/register-workflow! invoice-def)
  (let [store    (service-test/create-memory-store)
        registry (registry/create-workflow-registry)
        svc      (service/create-workflow-service store registry)
        handler  ((user-middleware/authenticate-if-present ::jwt-needs-no-service)
                  (reitit/compile-routes (sut/workflow-routes svc) {}))]
    (binding [*handler* handler *service* svc]
      (f)))
  (registry/clear-registry!))

(use-fixtures :each with-served-api)

;; =============================================================================
;; Request helpers
;; =============================================================================

(defn- admin-token []
  (auth-shell/create-jwt-token
   {:id (UUID/randomUUID) :email "admin@example.com" :role :admin} 1))

(defn- body-data
  "The response body as data. Muuntaja encodes it, so it arrives as a stream."
  [response]
  (let [body (:body response)]
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))))

(defn- post
  "POST `uri` with `body-params`, optionally bearing `token`."
  ([uri body-params] (post uri body-params nil))
  ([uri body-params token]
   (*handler* (cond-> {:request-method :post
                       :uri            uri
                       :headers        {}
                       :body-params    body-params}
                token (assoc-in [:headers "authorization"] (str "Bearer " token))))))

(defn- new-instance []
  (ports/start-workflow! *service*
                         {:workflow-id :invoice-workflow
                          :entity-type :invoice
                          :entity-id   (UUID/randomUUID)}))

;; =============================================================================
;; The actor
;; =============================================================================

(deftest ^:unit a-permissioned-transition-succeeds-for-an-authenticated-caller
  ;; The whole chain, because that is where the defect lived: the unit tests
  ;; handed the handler a request they had built themselves, with the key the
  ;; handler happened to read.
  (let [instance (new-instance)
        response (post (str "/workflow/instances/" (:id instance) "/transition")
                       {:transition "delivered"}
                       (admin-token))]
    (is (= 200 (:status response))
        "an authenticated admin was refused — the actor never reached the engine")
    (let [body (body-data response)]
      (is (true? (:success body)))
      (is (= "delivered" (get-in body [:instance :currentState])))

      (testing "and the audit entry names who did it"
        (is (= ["admin"] (get-in body [:auditEntry :actorRoles])))
        (is (some? (get-in body [:auditEntry :actorId])))))))

(deftest ^:unit a-permissioned-transition-is-refused-for-an-anonymous-caller
  ;; The counterpart, so the test above cannot pass by handing everyone
  ;; every role.
  (let [instance (new-instance)
        response (post (str "/workflow/instances/" (:id instance) "/transition")
                       {:transition "delivered"})]
    (is (= 422 (:status response)))
    (is (= "insufficient-permissions" (get-in (body-data response) [:error :type])))))

;; =============================================================================
;; The body
;; =============================================================================

(deftest ^:unit a-transition-the-workflow-does-not-allow-is-a-422
  (let [instance (new-instance)
        response (post (str "/workflow/instances/" (:id instance) "/transition")
                       {:transition "paid"}
                       (admin-token))
        body     (body-data response)]
    (is (= 422 (:status response)))
    (is (false? (:success body)))
    (is (= "transition-not-found" (get-in body [:error :type])))
    (is (string? (get-in body [:error :message])))))

(deftest ^:unit a-request-with-no-transition-is-a-4xx-not-a-500
  ;; `(name nil)` — the rejection path built its message from the transition it
  ;; was rejecting, which for a missing body is nil. The body schema now
  ;; refuses the request before the handler sees it.
  (doseq [[label body-params] [["no body"          {}]
                               ["a nil transition" {:transition nil}]
                               ["a number"         {:transition 7}]]]
    (let [response (post (str "/workflow/instances/" (:id (new-instance)) "/transition")
                         body-params
                         (admin-token))]
      (is (= 400 (:status response)) (str label " answered " (:status response)))
      (is (= "validation-error" (:error (body-data response))) label))))

(deftest ^:unit starting-a-workflow-reads-the-body-it-was-sent
  (let [entity-id (UUID/randomUUID)
        response  (post "/workflow/instances"
                        {:workflowId "invoice-workflow"
                         :entityType "invoice"
                         :entityId   (str entity-id)
                         ;; The caller's own metadata: the schema has to let it
                         ;; through, whatever is in it.
                         :metadata   {:invoiceNumber "2026-0001" :lines 3}}
                        (admin-token))
        body      (body-data response)]
    (is (= 201 (:status response)))
    (is (= "entered" (:currentState body)))
    (is (= (str entity-id) (:entityId body)))
    (is (= {:invoiceNumber "2026-0001" :lines 3}
           (:metadata (ports/find-instance-by-entity
                       (:store *service*) :invoice entity-id))))))

(deftest ^:unit starting-a-workflow-without-a-body-is-a-4xx-not-a-500
  (let [response (post "/workflow/instances" {} (admin-token))]
    (is (= 400 (:status response)))
    (is (= "validation-error" (:error (body-data response))))))
