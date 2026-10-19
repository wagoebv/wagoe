(ns wagoe.audience.core.filter-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.audience.core.filter :as f])
  (:import [java.time LocalDate ZoneOffset]
           [java.sql Timestamp]))

(deftest ^:unit demographics-filter-sql
  (testing ":demographics generates HoneySQL equality clause"
    (let [result (f/filter->sql {:type :demographics :field :plan :op :eq :value "premium"})]
      (is (= [:= :plan "premium"] result))))

  (testing ":demographics :in generates HoneySQL IN clause"
    (let [result (f/filter->sql {:type :demographics :field :role :op :in :value ["admin" "user"]})]
      (is (= [:in :role ["admin" "user"]] result)))))

(deftest ^:unit location-filter-sql
  (testing ":location generates HoneySQL clause"
    (let [result (f/filter->sql {:type :location :field :country :op :in :value ["NL" "BE"]})]
      (is (= [:in :country ["NL" "BE"]] result)))))

(def ^:private today
  "The date the shell supplies through `compile-segment`.

   These filters take it now: the cutoff is a bound parameter rather than
   `CURRENT_DATE - INTERVAL`, which only PostgreSQL parses (BOU-425). Without
   it `filter->sql` answers nil, the way it does for any filter it cannot
   express in SQL."
  (java.time.LocalDate/of 2026 9 10))

(deftest ^:unit account-tenure-filter-sql
  (testing ":account-tenure generates a created_at date comparison"
    (let [result (f/filter->sql {:type :account-tenure :op :gte :value 90 :now today})]
      (is (some? result))
      (is (= :created_at (second result)))))
  (testing "the cutoff is a bound instant, not PostgreSQL-only raw SQL"
    (let [[_ _ cutoff] (f/filter->sql {:type :account-tenure :op :gte :value 30 :now today})]
      (is (instance? java.time.Instant cutoff))
      (is (= (.toInstant (.atStartOfDay (.minusDays today 30) java.time.ZoneOffset/UTC))
             cutoff))))
  (testing "without a date it is not SQL-evaluable"
    (is (nil? (f/filter->sql {:type :account-tenure :op :gte :value 30}))))
  (testing "each op maps to the correct inverted SQL operator"
    ;; tenure comparison inverts against created_at
    (let [op-of (fn [op] (first (f/filter->sql {:type :account-tenure :op op :value 30 :now today})))]
      (is (= :<= (op-of :gte)))
      (is (= :<  (op-of :gt)))
      (is (= :>= (op-of :lte)))
      (is (= :>  (op-of :lt)))
      (is (= :=  (op-of :eq)))
      ;; BOU-189: :neq previously fell through to :<= (silently "tenure ≥ N")
      (is (= :<> (op-of :neq))))))

(deftest ^:unit last-active-filter-sql
  (testing ":last-active :within-days generates date window"
    (let [result (f/filter->sql {:type :last-active :op :within-days :value 30 :now today})]
      (is (some? result))
      (is (= :last_active_at (second result)))
      (is (instance? java.time.Instant (nth result 2)))))
  (testing "without a date it is not SQL-evaluable"
    (is (nil? (f/filter->sql {:type :last-active :op :within-days :value 30})))))

(deftest ^:unit role-filter-sql
  (testing ":role generates equality clause"
    (let [result (f/filter->sql {:type :role :field :role :op :eq :value "admin"})]
      (is (= [:= :role "admin"] result)))))

(deftest ^:unit behavior-filter-returns-nil-sql
  (testing ":behavior filter->sql returns nil (not DB-evaluable)"
    (is (nil? (f/filter->sql {:type :behavior :op :fn :value (constantly true)})))))

(deftest ^:unit behavior-filter-predicate
  (testing ":behavior filter->predicate returns the fn from :value"
    (let [pred-fn (fn [user] (> (:login-count user) 5))
          pred (f/filter->predicate {:type :behavior :op :fn :value pred-fn})]
      (is (true? (pred {:login-count 10})))
      (is (false? (pred {:login-count 2}))))))

(deftest ^:unit feature-usage-filter-sql-returns-nil
  (testing ":feature-usage filter->sql returns nil"
    (is (nil? (f/filter->sql {:type :feature-usage :field :feature-id :op :used-within :value 14})))))

(deftest ^:unit feature-usage-filter-predicate
  (let [today (LocalDate/of 2026 6 15)
        at    #(Timestamp/from (.toInstant (.atStartOfDay (.minusDays today %) ZoneOffset/UTC)))
        pred  (f/filter->predicate {:type :feature-usage :field :export :op :used-within :value 14
                                    :now today})]
    (testing "used inside the window, on its first day, or not at all"
      (is (true? (pred {:feature-usage {:export (at 3)}})))
      (is (true? (pred {:feature-usage {:export (at 14)}})) "the boundary day is inclusive")
      (is (false? (pred {:feature-usage {:export (at 15)}})))
      (is (not (pred {:feature-usage {:import (at 1)}})) "another feature does not count")
      (is (not (pred {}))))

    (testing "without a date there is no predicate, so compile-segment reports it"
      ;; It built one closing over nil, which threw on the first user who had
      ;; used the feature.
      (is (nil? (f/filter->predicate {:type :feature-usage :field :export :op :used-within :value 14}))))))

(deftest ^:unit custom-filter-type-registration
  (testing "apps can register custom filter types via defmethod"
    (defmethod f/filter->sql :subscription-tier [filt]
      [:= :subscriptions.tier (:value filt)])
    (let [result (f/filter->sql {:type :subscription-tier :value "gold"})]
      (is (= [:= :subscriptions.tier "gold"] result)))
    (remove-method f/filter->sql :subscription-tier)))

(deftest ^:unit sql-op-mapping
  (testing "all comparison operators map correctly"
    (is (= [:= :x 1]   (f/filter->sql {:type :demographics :field :x :op :eq  :value 1})))
    (is (= [:<> :x 1]  (f/filter->sql {:type :demographics :field :x :op :neq :value 1})))
    (is (= [:> :x 1]   (f/filter->sql {:type :demographics :field :x :op :gt  :value 1})))
    (is (= [:>= :x 1]  (f/filter->sql {:type :demographics :field :x :op :gte :value 1})))
    (is (= [:< :x 1]   (f/filter->sql {:type :demographics :field :x :op :lt  :value 1})))
    (is (= [:<= :x 1]  (f/filter->sql {:type :demographics :field :x :op :lte :value 1})))
    (is (= [:escape [:like :x "%foo%"] [:inline "!"]]
           (f/filter->sql {:type :demographics :field :x :op :contains :value "foo"}))))
  (testing ":contains escapes % _ and ! (BOU-684)"
    (is (= [:escape [:like :x "%!%!_!!%"] [:inline "!"]]
           (f/filter->sql {:type :demographics :field :x :op :contains :value "%_!"})))))

;; =============================================================================
;; Predicate tests for DB-evaluable filter types
;; =============================================================================

(defn- ->timestamp
  "Create a java.sql.Timestamp from a LocalDate."
  [^LocalDate ld]
  (Timestamp/from (.toInstant (.atStartOfDay ld) ZoneOffset/UTC)))

(deftest ^:unit demographics-predicate
  (testing "equality predicate matches correct field value"
    (let [pred (f/filter->predicate {:type :demographics :field :plan :op :eq :value "premium"})]
      (is (true? (pred {:plan "premium"})))
      (is (false? (pred {:plan "free"})))))

  (testing "inequality predicate rejects matching value"
    (let [pred (f/filter->predicate {:type :demographics :field :plan :op :neq :value "free"})]
      (is (true? (pred {:plan "premium"})))
      (is (false? (pred {:plan "free"})))))

  (testing "ordering operators"
    (let [ages (fn [op v] (filterv (f/filter->predicate {:type :demographics :field :age :op op :value v})
                                   (map #(hash-map :age %) [17 18 19])))]
      (is (= [{:age 19}] (ages :gt 18)))
      (is (= [{:age 18} {:age 19}] (ages :gte 18)))
      (is (= [{:age 17}] (ages :lt 18)))
      (is (= [{:age 17} {:age 18}] (ages :lte 18)))))

  (testing "a user without the field matches no ordering, as NULL does in SQL"
    (doseq [op [:gt :gte :lt :lte]]
      (is (false? ((f/filter->predicate {:type :demographics :field :age :op op :value 18}) {}))
          (name op))))

  (testing ":in and :contains"
    (let [in       (f/filter->predicate {:type :role :field :role :op :in :value ["admin" "owner"]})
          contains (f/filter->predicate {:type :location :field :city :op :contains :value "dam"})]
      (is (true? (in {:role "owner"})))
      (is (false? (in {:role "guest"})))
      (is (true? (contains {:city "Rotterdam"})))
      (is (true? (contains {:city "DAMASCUS"})) "case-insensitive")
      (is (false? (contains {:city "Utrecht"})))))

  (testing ":contains treats regex characters literally"
    (let [pred (f/filter->predicate {:type :demographics :field :email :op :contains :value "a.b"})]
      (is (true? (pred {:email "x-a.b@y"})))
      (is (false? (pred {:email "axb@y"}))))))

(deftest ^:unit account-tenure-predicate
  (testing "gte predicate correctly computes days since creation"
    (let [today   (LocalDate/now)
          pred    (f/filter->predicate {:type :account-tenure :op :gte :value 30 :now today})
          old-ts  (->timestamp (.minusDays today 60))
          new-ts  (->timestamp (.minusDays today 5))]
      (is (true? (pred {:created-at old-ts}))
          "User created 60 days ago should match >= 30 days tenure")
      (is (false? (pred {:created-at new-ts}))
          "User created 5 days ago should not match >= 30 days tenure")))

  (testing "exact boundary day matches with eq"
    (let [today     (LocalDate/now)
          pred      (f/filter->predicate {:type :account-tenure :op :eq :value 10 :now today})
          exact-ts  (->timestamp (.minusDays today 10))]
      (is (true? (pred {:created-at exact-ts}))))))

(deftest ^:unit last-active-predicate
  (testing "within-days predicate matches user active within window"
    (let [today      (LocalDate/now)
          pred       (f/filter->predicate {:type :last-active :op :within-days :value 7 :now today})
          recent-ts  (->timestamp (.minusDays today 3))
          old-ts     (->timestamp (.minusDays today 14))]
      (is (true? (pred {:last-active-at recent-ts}))
          "User active 3 days ago should be within 7-day window")
      (is (false? (pred {:last-active-at old-ts}))
          "User active 14 days ago should not be within 7-day window")))

  (testing "boundary day is inclusive (>= semantics)"
    (let [today       (LocalDate/now)
          pred        (f/filter->predicate {:type :last-active :op :within-days :value 7 :now today})
          boundary-ts (->timestamp (.minusDays today 7))]
      (is (true? (pred {:last-active-at boundary-ts}))
          "User active exactly 7 days ago should be included (inclusive boundary)"))))

(deftest ^:unit explain-filter-validation
  (testing "valid built-in filter returns nil"
    (is (nil? (f/explain-filter {:type :demographics :field :plan :op :eq :value "free"}))))
  (testing "behavior filter (no :op) is valid"
    (is (nil? (f/explain-filter {:type :behavior :value (constantly true)}))))
  (testing "unknown filter type"
    (is (= :unknown-filter-type
           (get-in (f/explain-filter {:type :nope :op :eq}) [:error :type]))))
  (testing ":default is the multimethod fallback, not a real type — rejected"
    (is (false? (f/known-type? :default)))
    (is (= :unknown-filter-type
           (get-in (f/explain-filter {:type :default :op :eq}) [:error :type]))))
  (testing "unsupported operator for a built-in type"
    (is (= :unsupported-filter-op
           (get-in (f/explain-filter {:type :last-active :op :eq :value 7}) [:error :type]))))
  (testing "explain-filters returns first error across a collection"
    (is (nil? (f/explain-filters [{:type :demographics :op :eq :value 1}
                                  {:type :role :op :in :value [:admin]}])))
    (is (= :unknown-filter-type
           (get-in (f/explain-filters [{:type :demographics :op :eq :value 1}
                                       {:type :bogus :op :eq}]) [:error :type])))))
