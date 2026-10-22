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
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.platform.core.http.nav :as nav]
            [wagoe.platform.shell.http.reitit-router :as reitit]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.shell.auth :as auth-shell]
            [wagoe.user.shell.middleware :as user-middleware]
            [wagoe.workflow.core.transitions :as transitions]
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
(def ^:dynamic *tenancy*
  "What the tenant middleware would add to each request: :tenant, :tenant-membership."
  {})

(def ^:private session-token "a-valid-session")
(def ^:private session-user-id (UUID/randomUUID))

(def ^:private jwt-users
  "The users `token-for` minted a JWT for, by id: the JWT path reads them."
  (atom {}))

(defn- token-for [user]
  (swap! jwt-users assoc (:id user) (assoc user :active true))
  (auth-shell/create-jwt-token user 1))

(def ^:private user-service
  "Knows one session, for one admin, and the JWT users."
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify user-ports/IUserService
    (get-user-by-id [_ id] (get @jwt-users id))
    (validate-session [_ token]
      (when (= session-token token)
        {:user-id session-user-id :session-token token
         :user {:id session-user-id :email "session@example.com" :role :admin :active true}}))))

(def ^:private readers
  "The scope each user's :workflow/read grant has, by id."
  (atom {}))

(def ^:private group-service
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify user-ports/IUserGroupService
    (user-grants [_ user]
      (if-let [scope (@readers (:id user))] [{:permission :workflow/read :scope scope}] []))))

(defn- with-served-api
  "Compile the workflow API routes through the platform router and put the
   authentication middleware outermost, where `:auth-middleware` puts it."
  [f]
  (reset! readers {})
  (registry/clear-registry!)
  (registry/register-workflow! invoice-def)
  (let [store    (service-test/create-memory-store)
        registry (registry/create-workflow-registry)
        svc      (service/create-workflow-service store registry)
        handler  ((user-middleware/authenticate-if-present user-service)
                  (reitit/compile-routes
                   (sut/workflow-routes svc group-service) {}))]
    (binding [*handler* handler *service* svc]
      (f)))
  (registry/clear-registry!))

(use-fixtures :each with-served-api)

;; =============================================================================
;; Request helpers
;; =============================================================================

(defn- admin-token []
  (token-for {:id (UUID/randomUUID) :email "admin@example.com" :role :admin}))

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
   (*handler* (cond-> (merge {:request-method :post
                              :uri            uri
                              :headers        {}
                              :body-params    body-params}
                             *tenancy*)
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
      (is (= #{:instance :audit-entry} (set (keys body)))
          "the body is the result, not a success flag beside it")
      (is (= "delivered" (get-in body [:instance :current-state])))

      (testing "and the audit entry names who did it"
        (is (= ["admin"] (get-in body [:audit-entry :actor-roles])))
        (is (some? (get-in body [:audit-entry :actor-id])))))))

(declare get-uri)

(deftest ^:unit a-transition-answers-what-the-caller-can-do-next
  ;; It answered "available-transitions": null (BOU-589).
  (registry/register-workflow! {:id            :gated
                                :initial-state :a
                                :states        #{:a :b :c :d}
                                :transitions   [{:from :a :to :b}
                                                {:from :b :to :c :required-permissions [:admin]}
                                                {:from :b :to :d :guard :never}]
                                :guards        {:never (constantly false)}})
  (doseq [[role expected] [[:admin [{:id "c" :to "c" :enabled true}
                                    {:id "d" :to "d" :enabled false :reason "guard-rejected"}]]
                           [:user  [{:id "c" :to "c" :enabled false :reason "insufficient-permissions"}
                                    {:id "d" :to "d" :enabled false :reason "guard-rejected"}]]]]
    (testing (name role)
      (let [id       (UUID/randomUUID)
            ;; A reader, so the user may GET what the transition answered.
            token    (do (swap! readers assoc id {})
                         (token-for {:id id :email "c@example.com" :role role}))
            instance (ports/start-workflow! *service* {:workflow-id :gated :entity-type :x
                                                       :entity-id   (UUID/randomUUID)})
            uri      (str "/workflow/instances/" (:id instance))
            moved    (body-data (post (str uri "/transition") {:transition "b"} token))]
        (is (= expected (get-in moved [:instance :available-transitions])))
        (is (= (:available-transitions (body-data (get-uri uri token)))
               (get-in moved [:instance :available-transitions]))
            "what GET /instances/:id answers")))))

(defn- get-uri
  "GET `uri`, which may carry a query string."
  ([uri] (get-uri uri nil))
  ([uri token]
   (let [[path query] (str/split uri #"\?" 2)]
     (*handler* (cond-> (merge {:request-method :get :uri path :headers {}} *tenancy*)
                  query (assoc :query-string query)
                  token (assoc-in [:headers "authorization"] (str "Bearer " token)))))))

(def ^:private start-body
  {:workflow-id "invoice-workflow" :entity-type "invoice"})

(defn- call-route
  "Each API route, called with `token` (nil for none)."
  [route token]
  (let [id (:id (new-instance))]
    (case route
      :start      (post "/workflow/instances"
                        (assoc start-body :entity-id (str (UUID/randomUUID)))
                        token)
      :get        (get-uri (str "/workflow/instances/" id) token)
      :lookup     (get-uri (str "/workflow/instances?entity-type=invoice&entity-id=" (UUID/randomUUID)) token)
      :audit      (get-uri (str "/workflow/instances/" id "/audit") token)
      :transition (post (str "/workflow/instances/" id "/transition")
                        {:transition "delivered"}
                        token))))

(def ^:private route-success
  {:start 201 :get 200 :lookup 200 :audit 200 :transition 200})

(deftest ^:unit every-api-route-refuses-an-anonymous-caller
  ;; BOU-561: the web routes mounted auth, the API routes did not.
  (doseq [route (keys route-success)]
    (testing (name route)
      (let [response (call-route route nil)]
        (is (= 401 (:status response)))
        (is (= {:type "unauthorized" :message "Authentication required"}
               (select-keys (:error (body-data response)) [:type :message])))))))

(deftest ^:unit every-api-route-serves-an-authenticated-caller
  (doseq [[route status] route-success]
    (testing (name route)
      (is (= status (:status (call-route route (admin-token))))))))

(deftest ^:unit a-session-cookie-authenticates
  (let [response (*handler* {:request-method :get
                             :uri            (str "/workflow/instances/" (:id (new-instance)))
                             :headers        {}
                             :cookies        {"session-token" {:value session-token}}})]
    (is (= 200 (:status response)))))

(deftest ^:unit a-permissioned-transition-is-refused-for-a-caller-without-the-role
  ;; The counterpart, so the admin test cannot pass by handing everyone
  ;; every role.
  (let [token    (token-for {:id (UUID/randomUUID) :email "u@example.com" :role :user})
        response (post (str "/workflow/instances/" (:id (new-instance)) "/transition")
                       {:transition "delivered"}
                       token)]
    (is (= 422 (:status response)))
    (is (= "insufficient-permissions" (get-in (body-data response) [:error :type])))))

;; =============================================================================
;; Who may read, and in which tenant
;; =============================================================================

(def ^:private reads #{:get :lookup :audit})

(defn- statuses
  "Each route's status for `user`, signed in with a token."
  [user]
  (let [token (token-for (merge {:id (UUID/randomUUID) :email "x@example.com"} user))]
    (into {} (for [route (keys route-success)] [route (:status (call-route route token))]))))

(deftest ^:unit the-reads-need-the-read-permission
  (testing "a plain user is refused the reads and the start, and may still transition"
    (let [seen (statuses {:role :user})]
      (is (= {:get 403 :lookup 403 :audit 403 :start 403} (select-keys seen (conj reads :start))))
      (is (= 422 (:transition seen)) "refused by the transition's own permission")))
  (testing "a user whose group grants :workflow/read reads"
    (let [id (UUID/randomUUID)]
      (swap! readers assoc id {})
      (is (= {:get 200 :lookup 200 :audit 200 :start 201}
             (select-keys (statuses {:id id :role :user}) (conj reads :start))))))
  (testing "a platform admin reads"
    (is (= route-success (statuses {:role :admin}))))
  (testing "the refusal names the permission"
    (let [response (get-uri (str "/workflow/instances/" (:id (new-instance)))
                            (token-for {:id (UUID/randomUUID) :email "u@example.com" :role :user}))]
      (is (= {:type "forbidden" :message "Reading workflow instances needs the workflow/read permission"}
             (select-keys (:error (body-data response)) [:type :message]))))))

(deftest ^:unit a-signed-in-browser-refused-gets-the-403-not-the-login-form
  ;; Signing in again would only lead back here.
  (let [handler  ((user-middleware/authenticate-if-present user-service)
                  (reitit/compile-routes [["/web" (sut/workflow-routes *service* group-service)]] {}))
        token    (token-for {:id (UUID/randomUUID) :email "u@example.com" :role :user})
        response (handler {:request-method :get
                           :uri            (str "/web/workflow/instances/" (:id (new-instance)))
                           :headers        {"authorization" (str "Bearer " token)}})]
    (is (= 403 (:status response)))
    (is (nil? (get-in response [:headers "Location"])))))

(deftest ^:unit every-api-route-refuses-a-caller-outside-the-tenant
  (let [tenant-a (UUID/randomUUID)
        tenant-b (UUID/randomUUID)
        refused  (zipmap (keys route-success) (repeat 403))]
    (testing "another tenant's admin, the request resolved to this tenant"
      (binding [*tenancy* {:tenant {:id tenant-a}}]
        (is (= refused (statuses {:role :admin :tenant-id tenant-b})))))
    (testing "a tenant's admin on a request with no tenant, which runs in public"
      (is (= refused (statuses {:role :admin :tenant-id tenant-a}))))
    (testing "the tenant's own admin"
      (binding [*tenancy* {:tenant {:id tenant-a}}]
        (is (= route-success (statuses {:role :admin :tenant-id tenant-a})))))
    (testing "an active member, whatever tenant the user record names"
      (binding [*tenancy* {:tenant {:id tenant-a} :tenant-membership {:status :active}}]
        (is (= route-success (statuses {:role :admin})))))
    (testing "a platform admin in any tenant"
      (binding [*tenancy* {:tenant {:id tenant-b}}]
        (is (= route-success (statuses {:role :admin})))))))

(deftest ^:unit in-a-tenant-the-tenants-admins-and-grants-decide
  (let [tenant-a (UUID/randomUUID)
        tenant-b (UUID/randomUUID)
        reads-of #(select-keys % reads)
        refused  (zipmap reads (repeat 403))
        allowed  {:get 200 :lookup 200 :audit 200}
        reader   (fn [scope] (let [id (UUID/randomUUID)]
                               (swap! readers assoc id scope)
                               {:id id :role :user :tenant-id tenant-b}))]
    (binding [*tenancy* {:tenant {:id tenant-a} :tenant-membership {:status :active :role :member}}]
      (testing "another tenant's admin, a plain member here, may not read"
        (is (= refused (reads-of (statuses {:role :admin :tenant-id tenant-b})))))
      (testing "nor may a grant scoped to no tenant or another one"
        (is (= refused (reads-of (statuses (reader {})))))
        (is (= refused (reads-of (statuses (reader {:tenant-id (str tenant-b)}))))))
      (testing "a grant scoped to this tenant reads"
        (is (= allowed (reads-of (statuses (reader {:tenant-id (str tenant-a)})))))))
    (binding [*tenancy* {:tenant {:id tenant-a} :tenant-membership {:status :active :role :admin}}]
      (testing "an admin by membership reads"
        (is (= allowed (reads-of (statuses {:role :user :tenant-id tenant-b}))))))))

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
    (is (= [:error] (keys body)) "the scaffolded APIs' 422: an error, no success flag")
    (is (= "transition-not-found" (get-in body [:error :type])))
    (is (string? (get-in body [:error :message])))
    (testing "the correlation id the response header carries (BOU-592)"
      (is (string? (get-in body [:error :correlation-id])))
      (is (= (get-in response [:headers "X-Correlation-ID"])
             (get-in body [:error :correlation-id]))))))

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
      (is (= "validation-error" (get-in (body-data response) [:error :type])) label))))

(deftest ^:unit starting-a-workflow-reads-the-body-it-was-sent
  (let [entity-id (UUID/randomUUID)
        response  (post "/workflow/instances"
                        {:workflow-id "invoice-workflow"
                         :entity-type "invoice"
                         :entity-id   (str entity-id)
                         ;; The caller's own metadata: the schema has to let it
                         ;; through, whatever is in it.
                         :metadata   {:invoiceNumber "2026-0001" :lines 3}}
                        (admin-token))
        body      (body-data response)]
    (is (= 201 (:status response)))
    (is (= "entered" (:current-state body)))
    (is (= (str entity-id) (:entity-id body)))
    (is (= {:invoiceNumber "2026-0001" :lines 3}
           (:metadata (ports/find-instance-by-entity
                       (:store *service*) :invoice entity-id))))))

(deftest ^:unit starting-a-workflow-without-a-body-is-a-4xx-not-a-500
  (let [response (post "/workflow/instances" {} (admin-token))]
    (is (= 400 (:status response)))
    (is (= "validation-error" (get-in (body-data response) [:error :type])))))

;; =============================================================================
;; Finding an entity's instance (BOU-581)
;; =============================================================================

(deftest ^:unit an-entitys-instance-is-found-by-its-type-and-id
  (let [instance (new-instance)
        lookup   #(get-uri (str "/workflow/instances?" %) (admin-token))
        response (lookup (str "entity-type=invoice&entity-id=" (:entity-id instance)))
        body     (body-data response)]
    (is (= 200 (:status response)))
    (is (= [(str (:id instance))] (mapv :id body)) "the entity's instance, and no other")
    (is (= {:workflow-id "invoice-workflow" :entity-type "invoice"
            :entity-id (str (:entity-id instance)) :current-state "entered"}
           (select-keys (first body) [:workflow-id :entity-type :entity-id :current-state])))
    (testing "another type with the same id has none"
      (is (= [] (body-data (lookup (str "entity-type=order&entity-id=" (:entity-id instance)))))))
    (testing "an entity with none is an empty list, not a 404"
      (is (= [] (body-data (lookup (str "entity-type=invoice&entity-id=" (UUID/randomUUID)))))))
    (testing "both are required, and the id is a UUID"
      (doseq [query ["entity-type=invoice" (str "entity-id=" (UUID/randomUUID))
                     "entity-type=invoice&entity-id=nope"]]
        (let [response (lookup query)]
          (is (= 400 (:status response)) query)
          (is (= "validation-error" (get-in (body-data response) [:error :type])) query))))))

;; =============================================================================
;; The JSON shape: kebab-case, as the scaffolded APIs answer (BOU-579)
;; =============================================================================

(defn- all-keys
  "Every map key anywhere in `body`, as strings."
  [body]
  (->> (tree-seq coll? seq body)
       (filter map?)
       (mapcat keys)
       (map name)
       set))

(deftest ^:unit the-api-answers-kebab-case
  (let [instance (new-instance)
        token    (admin-token)
        _        (post (str "/workflow/instances/" (:id instance) "/transition")
                       {:transition "delivered"} token)
        state    (body-data (get-uri (str "/workflow/instances/" (:id instance)) token))
        audit    (body-data (get-uri (str "/workflow/instances/" (:id instance) "/audit") token))]
    (is (= "delivered" (:current-state state)))
    (is (= [{:id "paid" :to "paid" :enabled true}]
           (:available-transitions state)))
    (is (= (str (:id instance)) (:instance-id audit)))
    (is (= "entered" (get-in audit [:entries 0 :from-state])))
    (doseq [[label body] [["state" state] ["audit" audit]]]
      (is (empty? (filter #(re-find #"[A-Z]" %) (all-keys body)))
          (str label " has a camelCase key")))))

(deftest ^:unit a-camel-case-start-body-is-refused
  (let [response (post "/workflow/instances"
                       {:workflowId "invoice-workflow" :entityType "invoice"
                        :entityId (str (UUID/randomUUID))}
                       (admin-token))]
    (is (= 400 (:status response)))
    (is (= "validation-error" (get-in (body-data response) [:error :type])))))

(deftest ^:unit an-unknown-instance-is-the-platforms-404
  (let [response (get-uri (str "/workflow/instances/" (UUID/randomUUID)) (admin-token))
        body     (body-data response)]
    (is (= 404 (:status response)))
    (is (= "not-found" (get-in body [:error :type])))
    (is (string? (get-in body [:error :message])))))

;; =============================================================================
;; The documented bodies are what the API takes and answers (BOU-579)
;; =============================================================================

(defn- documented-json
  "The JSON blocks under `== HTTP API` in the workflow docs, parsed."
  []
  (let [doc  (slurp (io/file (-> (io/resource "wagoe/workflow/shell/http_test.clj")
                                 io/file .getParentFile .getParentFile .getParentFile
                                 .getParentFile .getParentFile)
                             "../../docs/modules/libraries/pages/workflow.adoc"))
        from (subs doc (.indexOf ^String doc "== HTTP API"))]
    (mapv #(json/parse-string (second %) true)
          (re-seq #"(?s)\[source,json\]\n----\n(.*?)\n----" from))))

(defn- documented-lookup
  "The lookup request under `== HTTP API` in the workflow docs, without /api/v1."
  []
  (let [doc (slurp (io/file (-> (io/resource "wagoe/workflow/shell/http_test.clj")
                                io/file .getParentFile .getParentFile .getParentFile
                                .getParentFile .getParentFile)
                            "../../docs/modules/libraries/pages/workflow.adoc"))]
    (second (re-find #"(?m)^GET /api/v1(/workflow/instances\?\S+)" doc))))

(deftest ^:unit the-documented-bodies-are-the-apis
  (let [[start transitioned rejected found] (documented-json)]
    (is (some? found) "the docs show a start body, a transition, a 422 and a lookup")
    (registry/register-workflow! (assoc invoice-def :id (keyword (:workflow-id start))))
    (let [response (post "/workflow/instances" start (admin-token))
          instance (body-data response)]
      (is (= 201 (:status response)) "the documented start body was refused")
      (testing "a transition from where it stands that the workflow does not make"
        ;; The correlation id is the request's own (BOU-592).
        (let [sans-cid #(update % :error dissoc :correlation-id)]
          (is (= (sans-cid rejected)
                 (sans-cid (body-data (post (str "/workflow/instances/" (:id instance) "/transition")
                                            {:transition "paid"}
                                            (admin-token)))))))
        (is (contains? (:error rejected) :correlation-id) "the docs show it"))
      (testing "the documented lookup finds the instance, in the documented shape"
        (let [uri  (documented-lookup)
              body (body-data (get-uri uri (admin-token)))
              pick #(select-keys % [:workflow-id :entity-type :entity-id :current-state])]
          (is (some? uri) "the docs show the lookup request")
          (is (str/includes? (str uri) (:entity-id start)) "it looks up the entity started above")
          (is (= [(:id instance)] (mapv :id body)))
          (is (= (map (comp set keys) found) (map (comp set keys) body)))
          (is (= (map pick found) (map pick body)))))
      (testing "a transition answers the documented shape, with what may follow"
        (let [body (body-data (post (str "/workflow/instances/" (:id instance) "/transition")
                                    {:transition "delivered"}
                                    (admin-token)))
              shape (fn [b] (into {} (map (fn [[k v]] [k (set (keys v))])) b))]
          (is (= (shape transitioned) (shape body)))
          (is (= (select-keys (:instance transitioned) [:entity-id :current-state :available-transitions])
                 (select-keys (:instance body) [:entity-id :current-state :available-transitions])))
          (is (= (select-keys (:audit-entry transitioned) [:transition :from-state :to-state :actor-roles])
                 (select-keys (:audit-entry body) [:transition :from-state :to-state :actor-roles]))))))))

(deftest ^:unit the-documented-transition-items-are-the-one-shape
  ;; The entity's `workflow` and the instance list them alike (BOU-590); both
  ;; build each item with transition-view.
  (let [entity (nth (documented-json) 4 nil)]
    (is (some? entity) "the docs show a scaffolded entity's transition")
    (is (= [(transitions/transition-view {:id :paid :to :paid :label "Paid" :enabled? true})
            (transitions/transition-view {:id :void :to :void :enabled? false
                                          :reason :insufficient-permissions})]
           (get-in entity [:workflow :available-transitions])))))

(defn- listing-store
  "A store whose list-instances is `f`; nothing else answers."
  [f]
  (reify ports/IWorkflowStore
    (save-instance! [_ _] nil)
    (find-instance [_ _] nil)
    (find-instance-by-entity [_ _ _] nil)
    (update-instance-state! [_ _ _] nil)
    (save-audit-entry! [_ _] nil)
    (find-audit-log [_ _] nil)
    (delete-instance! [_ _] nil)
    (list-instances [_ opts] (f opts))))

(deftest ^:unit the-admin-list-page-hides-the-exception
  ;; The message can carry driver or config detail (BOU-555).
  (let [store    (listing-store
                  (fn [_] (throw (RuntimeException. "jdbc:postgresql://db password=hunter2"))))
        response (sut/handle-list-instances-web store (registry/create-workflow-registry) nil {})]
    (is (= 500 (:status response)))
    (is (not (re-find #"hunter2" (:body response))))
    (is (re-find #"error-generic" (:body response)))))

(deftest ^:unit the-admin-detail-page-answers-a-bad-or-unknown-id-with-404
  ;; A non-UUID id threw a :validation-error inside the handler's catch-all,
  ;; which answered 500 (BOU-586).
  (let [store    (service-test/create-memory-store)
        registry (registry/create-workflow-registry)]
    (doseq [id ["not-a-uuid" (str (UUID/randomUUID))]]
      (let [response (sut/handle-get-instance-web store registry nil {:path-params {:id id}})]
        (is (= 404 (:status response)) id)
        (is (re-find #"not found" (:body response)) id)))))

(deftest ^:unit the-admin-pages-sit-in-the-admin-shell
  ;; With the sidebar the platform binds, like every other admin page.
  (let [store  (service-test/create-memory-store)
        reg    (registry/create-workflow-registry)
        groups (nav/collect [{:admin {:items [{:entity :invoices :href "/web/admin/invoices"
                                               :label "Label invoices" :access :admin}]}}
                             {:workflow {:label "Workflows"
                                         :items [{:href "/web/admin/workflows" :label "Instances"
                                                  :access :admin}]}}]
                            nil (constantly true))
        page   (fn [admin user]
                 (binding [nav/*nav* {:groups groups :uri "/web/admin/workflows"}]
                   (:body (sut/handle-list-instances-web store reg admin {:user user}))))
        body   (page {:logo-url "/logo.png"} {:role :admin})]
    (is (str/includes? body "admin-sidebar"))
    (is (str/includes? body "href=\"/web/admin/invoices\""))
    (is (str/includes? body "Label invoices"))
    (is (str/includes? body "/logo.png"))
    (testing "the workflow item is the active one"
      (is (re-find #"<li class=\"active\"><a [^>]*href=\"/web/admin/workflows\"" body)))
    (testing "nor to a user the admin would not admit"
      (is (not (str/includes? (page nil {:role :user}) "/web/admin/invoices"))))))

(deftest ^:unit the-state-filter-takes-the-badge-label
  ;; The badge shows :on-hold as "On hold"; the filter took only the id.
  (let [seen  (atom nil)
        store (listing-store (fn [opts] (reset! seen opts) []))
        reg   (registry/create-workflow-registry)]
    (doseq [[typed expected] [["On hold" :on-hold] ["on-hold" :on-hold] ["  Paid " :paid]]]
      (sut/handle-list-instances-web store reg nil {:query-params {"state" typed}})
      (is (= expected (:current-state @seen)) typed))
    (sut/handle-list-instances-web store reg nil {:query-params {"state" "  "}})
    (is (not (contains? @seen :current-state)))))

(deftest ^:unit the-state-filter-offers-the-registered-states
  (let [reg (registry/create-workflow-registry)]
    (ports/register-workflow! reg {:id :state-filter-test :initial-state :pending
                                   :states #{:pending :on-hold}
                                   :transitions [{:from :pending :to :on-hold}]})
    (let [body (:body (sut/handle-list-instances-web (listing-store (constantly [])) reg nil
                                                     {:query-params {"state" "on-hold"}}))]
      (is (str/includes? body "<option selected=\"selected\" value=\"on-hold\">On hold</option>"))
      (is (str/includes? body "<option value=\"pending\">Pending</option>")))
    (testing "a filter typed as the label selects that state"
      (let [body (:body (sut/handle-list-instances-web (listing-store (constantly [])) reg nil
                                                       {:query-params {"state" "On hold"}}))]
        (is (str/includes? body "<option selected=\"selected\" value=\"on-hold\">On hold</option>"))))))

(deftest ^:unit the-admin-list-sorts-and-answers-its-controls-with-the-table
  (let [seen     (atom nil)
        instance {:id (UUID/randomUUID) :workflow-id :order :entity-type :order :entity-id (UUID/randomUUID)
                  :current-state :paid :updated-at "2026-10-22T10:00:00Z"}
        store    (listing-store (fn [opts] (reset! seen opts) [instance]))
        reg      (registry/create-workflow-registry)
        list!    (fn [qp headers]
                   (sut/handle-list-instances-web store reg nil {:query-params qp :headers headers}))]
    (testing "newest first unless a column is asked for"
      (list! {} {})
      (is (= [:updated-at :desc] ((juxt :sort :dir) @seen)))
      (list! {"sort" "entity-id"} {})
      (is (= [:updated-at :desc] ((juxt :sort :dir) @seen)) "not a column the store sorts by")
      (list! {"sort" "current-state"} {})
      (is (= [:current-state :asc] ((juxt :sort :dir) @seen))))
    (let [page     (list! {"sort" "workflow-id" "dir" "desc" "entity-type" "order"} {})
          fragment (list! {"sort" "workflow-id" "dir" "desc" "entity-type" "order"}
                          {"hx-request" "true" "hx-target" "workflows-table-container"})]
      (testing "the page: the filters live, no Search button"
        (is (str/includes? (:body page) "id=\"workflow-filter-entity-type\""))
        (is (not (str/includes? (:body page) "workflow-filter-form")))
        (is (not (str/includes? (:body page) "button-search"))))
      (testing "its controls get the table alone, sorted, keeping the filters"
        (is (str/starts-with? (:body fragment) "<div id=\"workflows-table-container\""))
        (is (str/includes? (:body fragment) "aria-sort=\"descending\""))
        (is (str/includes? (:body fragment) "entity-type=order"))
        (is (= "HX-Request, HX-Target" (get-in fragment [:headers "Vary"])))))))

(deftest ^:unit the-state-filter-keeps-a-registered-id-as-it-is
  ;; Lower-casing every value turned :inReview into :inreview, which matches nothing.
  (let [seen  (atom nil)
        store (listing-store (fn [opts] (reset! seen opts) []))
        reg   (registry/create-workflow-registry)]
    (ports/register-workflow! reg {:id :state-case-test :initial-state :draft
                                   :states #{:draft :inReview}
                                   :transitions [{:from :draft :to :inReview}]})
    (doseq [typed ["inReview" "Inreview" " inreview "]]
      (sut/handle-list-instances-web store reg nil {:query-params {"state" typed}})
      (is (= :inReview (:current-state @seen)) typed))))

;; =============================================================================
;; Who may see the admin pages
;; =============================================================================

(defn- served-web-pages
  "The admin pages as mounted, with authentication outermost."
  []
  ((user-middleware/authenticate-if-present user-service)
   (reitit/compile-routes
    [["/web/admin" (sut/workflow-web-routes (service-test/create-memory-store)
                                            (registry/create-workflow-registry)
                                            user-service
                                            nil)]]
    {})))

(deftest ^:unit the-admin-pages-admit-an-admin-in-the-requests-tenant
  ;; The pages list the instances of the schema the request runs in, so an
  ;; admin is admitted only to their own tenant's.
  (let [handler      (served-web-pages)
        tenant-a     (UUID/randomUUID)
        tenant-admin {:email "t@example.com" :role :admin :tenant-id tenant-a}
        get-as       (fn [uri user tenancy]
                       (handler (cond-> (merge {:request-method :get :uri uri :headers {}} tenancy)
                                  user (assoc-in [:headers "authorization"]
                                                 (str "Bearer " (token-for (assoc user :id (UUID/randomUUID))))))))
        uris         ["/web/admin/workflows" (str "/web/admin/workflows/" (UUID/randomUUID))]]
    (doseq [uri uris
            ;; The detail id is unknown: 404, past the guard.
            :let [admitted (if (str/ends-with? uri "workflows") 200 404)]]
      (testing uri
        (doseq [[who user tenancy] [["a plain user" {:email "u@example.com" :role :user} {}]
                                    ["a plain user of the tenant"
                                     {:email "u@example.com" :role :user :tenant-id tenant-a}
                                     {:tenant {:id tenant-a}}]
                                    ["another tenant's admin" tenant-admin {:tenant {:id (UUID/randomUUID)}}]
                                    ["a tenant admin with no tenant resolved" tenant-admin {}]]]
          (testing (str who " is refused")
            (let [response (get-as uri user tenancy)]
              (is (= 403 (:status response)))
              (is (str/includes? (:body response) "403")))))
        (testing "a tenant admin in their own tenant is admitted"
          (is (= admitted (:status (get-as uri tenant-admin {:tenant {:id tenant-a}})))))
        (testing "a platform admin is admitted"
          (is (= admitted (:status (get-as uri {:email "a@example.com" :role :admin} {})))))
        (testing "a signed-out caller is still sent to the login form"
          (let [response (get-as uri nil {})]
            (is (= 302 (:status response)))
            (is (str/starts-with? (get-in response [:headers "Location"]) "/web/login"))))))))
