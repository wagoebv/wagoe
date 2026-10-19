(ns wagoe.audience.shell.service-test
  "Unit + integration tests for AudienceService (IAudienceResolver).

   Uses mocked dependencies so no real DB is required, except for the
   cache-miss-then-hit test which uses an atom-backed mock cache."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.audience.core.filter :as filter]
            [wagoe.audience.shell.registry :as registry]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.service :as service])
  (:import [java.util UUID]))

;; =============================================================================
;; Registry cleanup between tests
;; =============================================================================

(defn- registry-fixture [f]
  (registry/clear-registry!)
  (try
    (f)
    (finally
      (registry/clear-registry!))))

(use-fixtures :each registry-fixture)

;; =============================================================================
;; Mock IUserDataSource
;; =============================================================================

(defn- mock-user-data-source
  "Returns an IUserDataSource that serves from a fixed user list.
   query-users-sql returns all user :id values when clause is nil, or
   filters by the first SQL clause (field = value equality only).
   load-users returns the matching user maps."
  [users]
  (reify ports/IUserDataSource
    (query-users-sql [_ clause]
      ;; clause is nil (no filter) or e.g. [:= :plan \"premium\"]
      (if (nil? clause)
        (mapv :id users)
        ;; Naively apply equality clause for test purposes
        (let [[_op field value] clause]
          (mapv :id (filter #(= (get % field) value) users)))))
    (load-users [_ user-ids]
      (filterv #(contains? (set user-ids) (:id %)) users))))

;; =============================================================================
;; Mock IAudienceRepository
;; =============================================================================

(defn- mock-repository
  "Atom-backed in-memory repository."
  []
  (let [store (atom {})]
    (reify ports/IAudienceRepository
      (save-audience [_ definition]
        (swap! store assoc (:id definition) definition)
        definition)
      (create-audience [this definition]
        (ports/save-audience this definition))
      (update-audience [this definition]
        (ports/save-audience this definition))
      (find-audience [_ audience-id]
        (get @store audience-id))
      (list-audiences [_]
        (vals @store))
      (list-audiences [this _filters]
        (ports/list-audiences this))
      (delete-audience [_ audience-id]
        (swap! store dissoc audience-id)
        nil))))

;; =============================================================================
;; Mock IAudienceCache
;; =============================================================================

(defn- mock-cache
  "Atom-backed in-memory cache (ignores TTL — always treats as fresh)."
  []
  (let [store (atom {})]
    (reify ports/IAudienceCache
      (get-cached [_ audience-id]
        (get @store audience-id))
      (put-cached [_ audience-id result _ttl-minutes]
        (swap! store assoc audience-id (assoc result :cached? true))
        result)
      (invalidate [_ audience-id]
        (swap! store dissoc audience-id)
        nil)
      (invalidate-all [_]
        (reset! store {})
        nil))))

;; =============================================================================
;; Fixtures / helpers
;; =============================================================================

(defn- uuid [] (UUID/randomUUID))

(defn- make-service
  ([users] (make-service users nil nil))
  ([users cache] (make-service users cache nil))
  ([users cache repository]
   (service/create-audience-service
    {:user-data-source (mock-user-data-source users)
     :cache            cache
     :repository       repository
     :filter-fields    (filter/allowed-filter-fields #{:plan :churned :region})})))

;; =============================================================================
;; Simple segment resolution — demographics only (SQL path)
;; =============================================================================

(deftest ^:unit simple-demographics-resolution
  (testing "returns user IDs matching a demographics filter"
    (let [premium-1 {:id (uuid) :plan "premium"}
          premium-2 {:id (uuid) :plan "premium"}
          free-1    {:id (uuid) :plan "free"}
          svc       (make-service [premium-1 premium-2 free-1])]

      (registry/register-audience!
       {:id      :premium-users
        :label   "Premium"
        :filters [{:type :demographics :field :plan :op :eq :value "premium"}]})

      (let [result (ports/resolve-audience svc :premium-users)]
        (is (= #{(:id premium-1) (:id premium-2)} (:user-ids result)))
        (is (= 2 (:count result)))
        (is (false? (:cached? result)))
        (is (inst? (:evaluated-at result))))))

  (testing "returns empty set when no users match"
    (let [svc (make-service [{:id (uuid) :plan "free"}])]
      (registry/register-audience!
       {:id      :enterprise-only
        :label   "Enterprise"
        :filters [{:type :demographics :field :plan :op :eq :value "enterprise"}]})
      (let [result (ports/resolve-audience svc :enterprise-only)]
        (is (empty? (:user-ids result)))
        (is (= 0 (:count result)))))))

;; =============================================================================
;; Hybrid resolution — SQL + behavior predicate
;; =============================================================================

(deftest ^:unit hybrid-resolution
  (testing "SQL narrows candidates, then predicate filters in-process"
    (let [active-premium   {:id (uuid) :plan "premium" :active? true}
          inactive-premium {:id (uuid) :plan "premium" :active? false}
          svc              (make-service [active-premium inactive-premium])]

      (registry/register-audience!
       {:id      :active-premium
        :label   "Active premium"
        :filters [{:type :demographics :field :plan :op :eq :value "premium"}
                  {:type :behavior
                   :op   :fn
                   :value (fn [user] (:active? user))}]})

      (let [result (ports/resolve-audience svc :active-premium)]
        (is (= #{(:id active-premium)} (:user-ids result)))
        (is (= 1 (:count result)))))))

;; =============================================================================
;; Cache miss then hit path
;; =============================================================================

(deftest ^:unit cache-miss-then-hit
  (testing "first call evaluates, second call uses mock cache"
    (let [u   {:id (uuid) :plan "premium"}
          c   (mock-cache)
          svc (make-service [u] c)]

      (registry/register-audience!
       {:id           :cached-seg
        :label        "Cached"
        :filters      [{:type :demographics :field :plan :op :eq :value "premium"}]
        :cache-config {:ttl-minutes 60}})

      ;; First call — cache miss, evaluates, stores
      (let [r1 (ports/resolve-audience svc :cached-seg)]
        (is (false? (:cached? r1)))
        (is (= #{(:id u)} (:user-ids r1))))

      ;; Second call — mock cache returns result with :cached? true
      (let [r2 (ports/resolve-audience svc :cached-seg)]
        (is (true? (:cached? r2)))
        (is (= #{(:id u)} (:user-ids r2))))))

  (testing "force-refresh? bypasses the cache"
    (let [u   {:id (uuid) :plan "premium"}
          c   (mock-cache)
          svc (make-service [u] c)]

      (registry/register-audience!
       {:id           :force-refresh-seg
        :label        "Force"
        :filters      [{:type :demographics :field :plan :op :eq :value "premium"}]
        :cache-config {:ttl-minutes 60}})

      ;; Populate cache
      (ports/resolve-audience svc :force-refresh-seg)
      ;; Force refresh — must re-evaluate
      (let [r (ports/resolve-audience svc :force-refresh-seg {:force-refresh? true})]
        (is (false? (:cached? r)))))))

;; =============================================================================
;; member? check
;; =============================================================================

(deftest ^:unit member-check
  (testing "member? returns true for a matching user"
    (let [u   {:id (uuid) :plan "premium"}
          svc (make-service [u])]
      (registry/register-audience!
       {:id      :member-check-seg
        :label   "MC"
        :filters [{:type :demographics :field :plan :op :eq :value "premium"}]})
      (is (true? (ports/member? svc :member-check-seg (:id u))))))

  (testing "member? returns false for a non-matching user"
    (let [u   {:id (uuid) :plan "free"}
          svc (make-service [u])]
      (registry/register-audience!
       {:id      :member-check-seg2
        :label   "MC2"
        :filters [{:type :demographics :field :plan :op :eq :value "premium"}]})
      (is (false? (ports/member? svc :member-check-seg2 (:id u))))))

  (testing "member? returns false for an unknown user ID"
    (let [u   {:id (uuid) :plan "premium"}
          svc (make-service [u])]
      (registry/register-audience!
       {:id      :member-check-seg3
        :label   "MC3"
        :filters [{:type :demographics :field :plan :op :eq :value "premium"}]})
      (is (false? (ports/member? svc :member-check-seg3 (uuid)))))))

;; =============================================================================
;; Unknown audience
;; =============================================================================

(deftest ^:unit unknown-audience-throws
  (testing "resolve-audience throws when audience-id is not found"
    (let [svc (make-service [])]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Audience not found"
           (ports/resolve-audience svc :nonexistent))))))

;; =============================================================================
;; Repository fallback
;; =============================================================================

(deftest ^:unit repository-fallback
  (testing "looks up definition from repository when not in registry"
    (let [u    {:id (uuid) :plan "gold"}
          repo (mock-repository)
          svc  (make-service [u] nil repo)]
      (ports/save-audience repo
                           {:id      :repo-seg
                            :label   "Repo"
                            :filters [{:type :demographics :field :plan :op :eq :value "gold"}]})
      (let [result (ports/resolve-audience svc :repo-seg)]
        (is (= #{(:id u)} (:user-ids result)))))))

;; =============================================================================
;; Composition resolution — AND intersection
;; =============================================================================

(deftest ^:unit composition-resolution
  (testing "composed segment intersects two base segments via :and"
    (let [both     {:id (uuid) :plan "premium" :region "eu"}
          prem-us  {:id (uuid) :plan "premium" :region "us"}
          free-eu  {:id (uuid) :plan "free"    :region "eu"}
          svc      (make-service [both prem-us free-eu])]

      ;; Register two base segments
      (registry/register-audience!
       {:id      :premium-seg
        :label   "Premium"
        :filters [{:type :demographics :field :plan :op :eq :value "premium"}]})

      (registry/register-audience!
       {:id      :eu-seg
        :label   "EU"
        :filters [{:type :demographics :field :region :op :eq :value "eu"}]})

      ;; Register composed segment that intersects both
      (registry/register-audience!
       {:id      :premium-eu
        :label   "Premium EU"
        :filters []
        :compose {:and [{:ref :premium-seg} {:ref :eu-seg}]}})

      (let [result (ports/resolve-audience svc :premium-eu)]
        (is (= #{(:id both)} (:user-ids result))
            "Only the user matching both segments should be in the intersection")
        (is (= 1 (:count result)))
        (is (false? (:cached? result)))))))

(deftest ^:unit nested-composition-or-and-not
  (let [paid    {:id (uuid) :plan "paid"}
        trial   {:id (uuid) :plan "trial"}
        churned {:id (uuid) :plan "paid" :churned "yes"}
        svc     (make-service [paid trial churned])]
    (registry/register-audience! {:id :paid :label "P" :filters [{:type :demographics :field :plan :op :eq :value "paid"}]})
    (registry/register-audience! {:id :trial :label "T" :filters [{:type :demographics :field :plan :op :eq :value "trial"}]})
    (registry/register-audience! {:id :churned :label "C" :filters [{:type :demographics :field :churned :op :eq :value "yes"}]})
    (registry/register-audience! {:id :paying :label "Paying" :filters []
                                  :compose {:and [{:ref :paid} {:not {:ref :churned}}]}})
    (registry/register-audience! {:id :engaged :label "Engaged" :filters []
                                  :compose {:or [{:ref :paying} {:ref :trial}]}})
    (is (= #{(:id paid) (:id trial)} (:user-ids (ports/resolve-audience svc :engaged)))
        "a composed ref inside a composition resolves through its own tree")))

(deftest ^:unit a-composition-that-cannot-resolve-is-a-typed-error
  ;; A -> B -> A recursed through resolve-audience until StackOverflowError,
  ;; which no handler catches: the core's cycle guard never saw the refs.
  (let [svc (make-service [])]
    (registry/register-audience! {:id :cyc-a :label "A" :filters [] :compose {:and [{:ref :cyc-b}]}})
    (registry/register-audience! {:id :cyc-b :label "B" :filters [] :compose {:and [{:ref :cyc-a}]}})
    (registry/register-audience! {:id :self :label "S" :filters [] :compose {:or [{:ref :self}]}})
    (registry/register-audience! {:id :dangling :label "D" :filters [] :compose {:and [{:ref :nowhere}]}})
    (doseq [[id expected] [[:cyc-a :circular-reference]
                           [:self :circular-reference]
                           [:dangling :unknown-segment-ref]]]
      (is (= expected
             (try (ports/resolve-audience svc id) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          (name id)))))

(deftest ^:unit a-composed-ref-is-evaluated-as-of-the-callers-date
  ;; Refs were resolved without the caller's opts, so a leaf's date filter
  ;; used today rather than :as-of.
  (let [used (java.util.Date/from (java.time.Instant/parse "2026-01-05T12:00:00Z"))
        user {:id (uuid) :feature-usage {:export used}}
        svc  (make-service [user])]
    (registry/register-audience! {:id :exporters :label "E"
                                  :filters [{:type :feature-usage :field :export :op :used-within :value 14}]})
    (registry/register-audience! {:id :all-exporters :label "A" :filters [] :compose {:or [{:ref :exporters}]}})
    (is (= #{(:id user)}
           (:user-ids (ports/resolve-audience svc :all-exporters {:as-of (java.time.LocalDate/of 2026 1 10)}))))))

(deftest ^:unit filters-beside-compose-are-refused
  ;; Resolving took the :compose path and dropped the filters, so the audience
  ;; was larger than its definition.
  (let [both {:id :both :label "B" :compose {:or [{:ref :paid}]}
              :filters [{:type :demographics :field :plan :op :eq :value "free"}]}
        type-of (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (testing "registering one throws"
      (is (= :validation-error (type-of #(registry/register-audience! both)))))
    (testing "resolving one stored before the rule throws"
      (let [repo (mock-repository)
            svc  (make-service [{:id (uuid) :plan "paid"}] nil repo)]
        (registry/register-audience! {:id :paid :label "P" :filters []})
        (ports/save-audience repo both)
        (is (= :validation-error (type-of #(ports/resolve-audience svc :both))))
        (testing "and so does composing it"
          (registry/register-audience! {:id :outer :label "O" :filters [] :compose {:or [{:ref :both}]}})
          (is (= :validation-error (type-of #(ports/resolve-audience svc :outer)))))))))

(deftest ^:unit ^:security a-cached-result-does-not-outlive-the-allowlist
  ;; Evaluated and cached before the allowlist existed; a cache hit skipped
  ;; the field check.
  (let [c    (mock-cache)
        repo (mock-repository)
        svc  (make-service [] c repo)]
    (ports/save-audience repo {:id :old :label "Old"
                               :filters [{:type :demographics :field :password-hash
                                          :op :contains :value "$2a$"}]})
    (ports/put-cached c :old {:user-ids #{(uuid)} :count 1} 60)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not allowed"
                          (ports/resolve-audience svc :old)))))
