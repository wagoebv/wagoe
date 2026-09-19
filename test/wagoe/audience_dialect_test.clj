(ns wagoe.audience-dialect-test
  "The audience schema and store, against every database adapter reachable here.

   Audience had one fixture, H2, whose columns were TEXT. Its PostgreSQL
   migration declared JSONB and ran nowhere, so `save-audience` had never
   executed against the adapter the framework recommends for production: it
   fails with \"column is of type jsonb but expression is of type character
   varying\". SQLite failed earlier still, on DDL its dialect cannot parse.

   One table of cases across the adapters, because that is what a per-adapter
   fixture cannot tell you (BOU-419 review)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.audience.core.compiler :as compiler]
            [wagoe.audience.core.filter :as audience-filter]
            [wagoe.audience.shell.adapters.user-sql :as user-sql]
            [wagoe.audience.shell.cache :as audience-cache]
            [wagoe.audience.shell.service :as service]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.persistence :as p]))

(def ^:private mysql-port
  "Where the sweep looks for MySQL. CI runs one as a service on 3306; set
   `WAGOE_TEST_MYSQL_PORT` to point at a local container on another port."
  (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306")))

(defn- mysql-spec []
  {:dbtype "mysql" :host "127.0.0.1" :port mysql-port
   :dbname (or (System/getenv "WAGOE_TEST_MYSQL_DB") "audience")
   :user "root" :password (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe")})

(defonce ^:private mysql-up?
  (delay (try
           (with-open [c (jdbc/get-connection (jdbc/get-datasource (mysql-spec)))]
             (some? (.getMetaData c)))
           (catch Exception _ false))))

(defn- backends
  "Each is [label make]; `make` returns [datasource stop!]."
  []
  (cond->
   [["h2"     (fn [] [(jdbc/get-datasource
                       {:dbtype "h2:mem"
                        :dbname (str "aud_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})
                      (fn [] nil)])]
    ["sqlite" (fn [] (let [f (str (System/getProperty "java.io.tmpdir")
                                  "/aud-" (System/nanoTime) ".db")]
                       [(jdbc/get-datasource {:dbtype "sqlite" :dbname f})
                        (fn [] (.delete (java.io.File. f)))]))]
    ["postgresql" (fn [] (let [pg (epg/start!)]
                           [(epg/datasource pg) (fn [] (epg/stop! pg))]))]]
    ;; MySQL needs a server, so it joins when one is reachable — and the case
    ;; below says so loudly when it is not, rather than reporting three
    ;; adapters as if that were the whole set.
    @mysql-up?
    (conj ["mysql"
           (fn [] (let [ds (jdbc/get-datasource (mysql-spec))]
                    (doseq [t ["audience_memberships" "audience_segments"]]
                      (jdbc/execute! ds [(str "DROP TABLE IF EXISTS " t)]))
                    [ds (fn [] nil)]))])))

(deftest ^:integration the-sweep-covers-every-reachable-adapter
  (is (= 4 (count (backends)))
      (str "MySQL is not reachable on 127.0.0.1:" mysql-port
           " — this run compared " (count (backends)) " adapters, not four.\n"
           "  Start it with `bb test:services up`,\n"
           "  or point the sweep elsewhere with WAGOE_TEST_MYSQL_PORT.")))

(deftest ^:integration a-definition-round-trips-on-every-adapter
  (doseq [[label make] (backends)]
    (testing label
      (let [[ds stop!] (make)]
        (try
          (is (= label (name (p/dialect ds))) "the dialect was not detected")
          (p/initialize-audience-schema! ds)

          (let [store (p/create-audience-store ds)
                users [(random-uuid) (random-uuid)]]
            (testing "a stored definition compiles to the same plan it did in memory"
              ;; The promise is not that the JSON bytes match — it is that the
              ;; audience still means what it meant. JSON has no keywords, and a
              ;; definition reloaded as strings compiled to a constant predicate
              ;; instead of a SQL clause: the right count of filters, the wrong
              ;; users (BOU-419 review).
              (let [definition {:id      :premium
                                :label   "Premium"
                                :filters [{:type :demographics :field :plan
                                           :op :eq :value "premium"}]
                                :tags    ["billing"]}]
                (ports/save-audience store definition)
                (let [found (ports/find-audience store :premium)]
                  (is (= :premium (:id found)))
                  (is (= ["billing"] (:tags found)))
                  (is (= (:sql-clauses (compiler/compile-segment definition))
                         (:sql-clauses (compiler/compile-segment found)))
                      "the reloaded definition compiles to a different plan")
                  (is (= [[:= :plan "premium"]]
                         (:sql-clauses (compiler/compile-segment found)))
                      "and not to the plan the filter describes"))))

            (testing "memberships are written and read against its own id type"
              (p/save-memberships! ds :premium users)
              (is (= (set users) (set (p/get-memberships ds :premium)))))

            (testing "a composition still names other audiences by keyword"
              ;; The resolver looks compositions up in a keyword-keyed registry.
              ;; Through JSON `{:ref :premium}` came back `{:ref "premium"}`,
              ;; which matches nothing — a composed audience answered
              ;; :audience-not-found for a segment that was right there
              ;; (BOU-419 review).
              (ports/save-audience store {:id      :combo
                                          :label   "Combo"
                                          :filters []
                                          :compose {:all-of [{:ref :premium}
                                                             {:any-of [{:ref :trial}]}]}})
              (is (= {:all-of [{:ref :premium} {:any-of [{:ref :trial}]}]}
                     (:compose (ports/find-audience store :combo)))
                  "a nested composition ref did not survive as a keyword"))

            (testing "a cached result is read back, TTL and all"
              ;; PostgreSQL returns a JSONB column as a PGobject, and the cache
              ;; had its own decoder that answered nil for one — so the TTL was
              ;; nil, the cache never hit, and every resolve recomputed the
              ;; membership (BOU-419 review).
              (let [c (audience-cache/create-audience-cache ds nil)]
                (ports/put-cached c :premium {:user-ids (set users) :count 2} 60)
                (let [hit (ports/get-cached c :premium)]
                  (is (some? hit) "a result cached one minute ago was not found")
                  (is (true? (:cached? hit)))
                  (is (= (set users) (:user-ids hit))))))

            (testing "and it can be deleted"
              (ports/delete-audience store :premium)
              (is (nil? (ports/find-audience store :premium)))))
          (finally (stop!)))))))

(deftest ^:integration the-user-source-speaks-the-users-table-it-reads
  ;; `filter->sql` for `:last-active` emits `last_active_at`; a Wagoe users
  ;; table records `last_login`. The shipped source read the clause verbatim,
  ;; so the one documented filter that names a column failed with
  ;; "column last_active_at not found" against the very table this module is
  ;; wired to (BOU-419 review).
  ;;
  ;; The clause here is a plain comparison rather than the one `:last-active`
  ;; compiles: that one is `CURRENT_DATE - INTERVAL`, which only PostgreSQL
  ;; parses, and this is a statement about the column name (BOU-425).
  (doseq [[label make] (backends)]
    (testing label
      (let [[ds stop!] (make)]
        (try
          ;; Dropped first: MySQL is a shared server here, so a table left in
          ;; another shape by an earlier run would survive `IF NOT EXISTS` and
          ;; fail this with a missing column.
          (jdbc/execute! ds ["DROP TABLE IF EXISTS users"])
          (jdbc/execute! ds ["CREATE TABLE users (
                                id VARCHAR(64) PRIMARY KEY, name VARCHAR(255),
                                last_login TIMESTAMP NULL)"])
          (let [source (user-sql/create-sql-user-data-source ds)]
            (is (= [] (ports/query-users-sql source [:is :last_active_at nil]))
                "the filter's column name was not translated to the table's")
            (testing "and an unmapped column is passed through untouched"
              (is (= [] (ports/query-users-sql source [:is :name nil])))))
          (finally (stop!)))))))

(deftest ^:integration date-filters-resolve-on-every-adapter
  ;; `:account-tenure` and `:last-active` compiled
  ;; `CURRENT_DATE - INTERVAL '… days'`, which only PostgreSQL parses: on H2 —
  ;; the database every generated project starts with — and on SQLite they
  ;; threw, so two of the seven documented filter types worked on one adapter
  ;; (BOU-425).
  ;;
  ;; Asserted on which users come back, not on the SQL. A LocalDate cutoff runs
  ;; without error on all four and answers *wrongly* on SQLite, which stores
  ;; these columns as epoch millis and sorts every number before every string:
  ;; no rows for `>=`, every row for `<=`. A test that only checked for an
  ;; exception would have called that a pass.
  (let [today  (java.time.LocalDate/now)
        recent (java.sql.Timestamp/from (java.time.Instant/now))
        stale  (java.sql.Timestamp/from (.minus (java.time.Instant/now)
                                                60 java.time.temporal.ChronoUnit/DAYS))]
    (doseq [[label make] (backends)]
      (testing label
        (let [[ds stop!] (make)]
          (try
            ;; The column type and the value this adapter really stores, not a
            ;; shape invented here. SQLite keeps timestamps as TEXT in ISO-8601
            ;; — its own platform adapter says so — and writing a
            ;; java.sql.Timestamp into a TIMESTAMP column there stores an
            ;; integer instead, which is a table the framework never creates.
            ;; The first version of this test did exactly that and so could not
            ;; see the bug it was written for (BOU-425 review).
            ;;
            ;; Its own table, too: MySQL is a shared server here, and reshaping
            ;; `users` under the test above is how this first ran red.
            (let [sqlite?   (= :sqlite (p/dialect ds))
                  ts-type   (if sqlite? "TEXT" "TIMESTAMP NULL")
                  ->stored  (fn [^java.sql.Timestamp t]
                              (if sqlite? (str (.toInstant t)) t))]
              (jdbc/execute! ds ["DROP TABLE IF EXISTS date_filter_users"])
              (jdbc/execute! ds [(str "CREATE TABLE date_filter_users (id VARCHAR(64) PRIMARY KEY,
                                    last_login " ts-type ", created_at " ts-type ")")])
              (jdbc/execute! ds ["INSERT INTO date_filter_users (id, last_login, created_at)
                                    VALUES (?,?,?)" "recent" (->stored recent) (->stored recent)])
              (jdbc/execute! ds ["INSERT INTO date_filter_users (id, last_login, created_at)
                                    VALUES (?,?,?)" "stale" (->stored stale) (->stored stale)]))
            (let [source (user-sql/create-sql-user-data-source ds :date_filter_users)
                  ids    (fn [filt]
                           (set (ports/query-users-sql
                                 source
                                 (audience-filter/filter->sql (assoc filt :now today)))))]
              (testing ":last-active within 30 days finds the recent user only"
                (is (= #{"recent"} (ids {:type :last-active :op :within-days :value 30}))))

              (testing ":account-tenure over 30 days finds the old account only"
                (is (= #{"stale"} (ids {:type :account-tenure :op :gte :value 30})))))
            (finally (stop!))))))))

(deftest ^:integration an-audience-it-cannot-evaluate-is-refused
  ;; The compiler reports a filter it can express neither way; the service must
  ;; refuse rather than evaluate what is left. A definition whose only filter is
  ;; dropped compiles to an empty plan, and an empty plan is the universe — so
  ;; the quiet failure is "every user", not "no users" (BOU-425 review).
  ;;
  ;; Driven through `with-redefs` because the service always supplies `:now`,
  ;; which is the only way a filter reaches this state today. The branch is a
  ;; net under the library path — `compile-segment`'s one-argument arity — and
  ;; a net nobody has pulled on is one nobody knows the shape of.
  (let [[ds stop!] ((second (first (backends))))]
    (try
      (p/initialize-audience-schema! ds)
      (jdbc/execute! ds ["DROP TABLE IF EXISTS users"])
      (jdbc/execute! ds ["CREATE TABLE users (id VARCHAR(64) PRIMARY KEY, last_login TIMESTAMP NULL)"])
      (jdbc/execute! ds ["INSERT INTO users (id, last_login) VALUES ('someone', NULL)"])
      (let [store    (p/create-audience-store ds)
            resolver (service/create-audience-service
                      {:repository       store
                       :cache            (audience-cache/create-audience-cache ds nil)
                       :user-data-source (user-sql/create-sql-user-data-source ds)})
            filt     {:type :last-active :op :within-days :value 30}]
        (ports/save-audience store {:id :undecidable :label "U" :filters [filt]})

        (testing "it resolves when the plan is complete"
          (is (some? (ports/resolve-audience resolver :undecidable {:force-refresh? true}))))

        (testing "and refuses when the compiler could not express a filter"
          (with-redefs [compiler/compile-segment
                        (fn [& _] {:sql-clauses [] :predicates [] :unsupported [filt]})]
            (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                  (ports/resolve-audience resolver :undecidable
                                                          {:force-refresh? true})))]
              (is (= :configuration-error (:type (ex-data ex)))
                  (str "refused with the wrong error: " (pr-str (ex-data ex))))
              (is (= [filt] (:filters (ex-data ex)))
                  "the refusal does not name the filter it could not evaluate")))))
      (finally (stop!)))))

(deftest ^:integration the-cutoff-second-is-not-a-cliff
  ;; A user active one millisecond after the cutoff belongs in the audience.
  ;; On SQLite they did not: the framework stores timestamps as ISO-8601 text,
  ;; `Instant/toString` omits the fraction when it is zero, and `.` sorts
  ;; before `Z` — so `…T00:00:00.001Z` sorted before the `…T00:00:00Z` cutoff
  ;; and dropped out (BOU-425 review).
  ;;
  ;; Run on every adapter, because "the encoding sorts correctly" is a claim
  ;; about each one's storage, and I have now been wrong about SQLite's twice.
  (let [today  (java.time.LocalDate/now)
        cutoff (.toInstant (.atStartOfDay (.minusDays today 30) java.time.ZoneOffset/UTC))
        cases  {"exact"      cutoff
                "one-ms"     (.plusMillis cutoff 1)
                "one-second" (.plusSeconds cutoff 1)}]
    (doseq [[label make] (backends)]
      (testing label
        (let [[ds stop!] (make)]
          (try
            (let [sqlite?  (= :sqlite (p/dialect ds))
                  ts-type  (if sqlite? "TEXT" "TIMESTAMP NULL")
                  ->stored (fn [^java.time.Instant i]
                             (if sqlite? (str i) (java.sql.Timestamp/from i)))]
              (jdbc/execute! ds ["DROP TABLE IF EXISTS boundary_users"])
              (jdbc/execute! ds [(str "CREATE TABLE boundary_users (id VARCHAR(64) PRIMARY KEY,
                                     last_login " ts-type ")")])
              (doseq [[id instant] cases]
                (jdbc/execute! ds ["INSERT INTO boundary_users (id, last_login) VALUES (?,?)"
                                   id (->stored instant)]))

              (let [source (user-sql/create-sql-user-data-source ds :boundary_users)
                    found  (set (ports/query-users-sql
                                 source
                                 (audience-filter/filter->sql
                                  {:type :last-active :op :within-days :value 30 :now today})))]
                (is (= (set (keys cases)) found)
                    (str "a user at or just after the cutoff was excluded; found "
                         (pr-str (sort found))))))
            (finally (stop!))))))))

(deftest ^:integration a-custom-clause-with-instants-is-converted-too
  ;; `filter->sql` is a multimethod applications extend, and a custom method may
  ;; return any HoneySQL date form — `[:between :col from to]` among them. The
  ;; first version of this conversion matched three-element comparisons only,
  ;; so a `:between`'s instants were left raw and hit the same fractional-second
  ;; cliff on SQLite (BOU-425 review).
  ;;
  ;; The rows straddle the window's lower edge by a millisecond, because a
  ;; coarse window passes even when the comparison is wrong — the first probe
  ;; for this said everything was fine.
  (let [start (java.time.Instant/parse "2026-08-11T00:00:00Z")
        end   (java.time.Instant/parse "2026-09-11T00:00:00Z")
        cases {"at-start"      start
               "start-plus-ms" (.plusMillis start 1)
               "mid"           (java.time.Instant/parse "2026-08-20T12:00:00Z")
               "after-end"     (.plusSeconds end 60)}]
    (doseq [[label make] (backends)]
      (testing label
        (let [[ds stop!] (make)]
          (try
            (let [sqlite?  (= :sqlite (p/dialect ds))
                  ts-type  (if sqlite? "TEXT" "TIMESTAMP NULL")
                  ->stored (fn [^java.time.Instant i]
                             (if sqlite? (str i) (java.sql.Timestamp/from i)))]
              (jdbc/execute! ds ["DROP TABLE IF EXISTS custom_clause_users"])
              (jdbc/execute! ds [(str "CREATE TABLE custom_clause_users (id VARCHAR(64) PRIMARY KEY,
                                     expires_at " ts-type ")")])
              (doseq [[id instant] cases]
                (jdbc/execute! ds ["INSERT INTO custom_clause_users (id, expires_at) VALUES (?,?)"
                                   id (->stored instant)]))

              (let [source (user-sql/create-sql-user-data-source ds :custom_clause_users)
                    found  (set (ports/query-users-sql source [:between :expires_at start end]))]
                (is (= #{"at-start" "start-plus-ms" "mid"} found)
                    (str ":between left an instant unconverted; found " (pr-str (sort found))))))
            (finally (stop!))))))))
