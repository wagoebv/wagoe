(ns wagoe.platform.shell.adapters.database.constraint-violation-test
  "A write a unique or foreign key refuses is a 409 `conflict` naming its field,
   on every engine. It was a 500 `internal-error` (BOU-590)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [muuntaja.core :as muuntaja]
            [support.embedded-pg :as epg]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.http.reitit-router :as reitit-router])
  (:import [java.io File]))

(defonce ^:private backends (atom {}))

(defn- create-tables! [ctx]
  (doseq [t ["lines" "invoices"]]
    (db/execute-update! ctx {:raw (str "DROP TABLE IF EXISTS " t)}))
  (db/execute-update! ctx {:raw "CREATE TABLE invoices (id VARCHAR(36) PRIMARY KEY, number VARCHAR(50) NOT NULL UNIQUE)"})
  (db/execute-update! ctx {:raw (str "CREATE TABLE lines (id VARCHAR(36) PRIMARY KEY, "
                                     "invoice_id VARCHAR(36) NOT NULL REFERENCES invoices(id))")}))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "bou590" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou590;DB_CLOSE_DELAY=-1"})
                     :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}]
    (try
      (doseq [ctx (vals ctxs)] (create-tables! ctx))
      (reset! backends ctxs)
      (f)
      (finally
        (doseq [ctx (vals ctxs)] (db-factory/close-db-context! ctx))
        (epg/stop! pg)
        (.delete sqlite-file)
        (reset! backends {})))))

(use-fixtures :once with-backends)

(defn- caller
  "POST /write runs `(f body)` behind the platform's router, as a generated
   API's handler reaches its repository."
  [f]
  (let [handler (reitit-router/compile-routes
                 [["/write" {:post {:handler (fn [req] (f (:body-params req)) {:status 201 :body {}})}}]]
                 {:swagger-enabled false})]
    (fn [body]
      (let [resp (handler {:request-method :post :uri "/write"
                           :headers {"accept" "application/json" "content-type" "application/json"}
                           :body (muuntaja/encode muuntaja/instance "application/json" body)})
            text (let [b (:body resp)] (if (string? b) b (slurp b)))]
        (assoc resp :text text :json (muuntaja/decode muuntaja/instance "application/json" text))))))

(defn- thrown [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest ^:integration a-duplicate-is-a-409-naming-its-field
  (doseq [[backend ctx] @backends]
    (testing (name backend)
      (let [number (str "A-" (name backend))
            insert #(db/execute-update! ctx {:insert-into :invoices
                                             :values [{:id (str (random-uuid)) :number (:number %)}]})
            post   (caller insert)]
        (is (= 201 (:status (post {:number number}))))
        (let [resp (post {:number number})]
          (is (= 409 (:status resp)) (:text resp))
          (is (= "conflict" (get-in resp [:json :error :type])))
          (is (= "number" (get-in resp [:json :error :details :field])) (:text resp))
          (testing "and says nothing of the SQL, the driver or the value"
            ;; The correlation id is a random uuid and could contain any of these.
            (let [text (str/replace (:text resp) #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" "")]
              (is (not (re-find #"(?i)insert|invoices|sqlite|23505|violat|index" text)) (:text resp))
              (is (not (str/includes? text number)) (:text resp)))))
        (testing "an update, and a read-back through execute-one!, too"
          (let [other (str "B-" (name backend))
                id    (str (random-uuid))]
            (db/execute-update! ctx {:insert-into :invoices :values [{:id id :number other}]})
            (is (= {:type :conflict :constraint :unique :field :number}
                   (ex-data (thrown #(db/execute-update! ctx {:update :invoices :set {:number number}
                                                              :where [:= :id id]})))))
            (is (= :conflict
                   (:type (ex-data (thrown #(db/execute-one! ctx {:insert-into :invoices
                                                                  :values [{:id (str (random-uuid)) :number other}]}))))))))))))

(deftest ^:integration a-missing-reference-is-a-409
  (doseq [[backend ctx] @backends]
    (testing (name backend)
      (let [post (caller #(db/execute-update! ctx {:insert-into :lines
                                                   :values [{:id (str (random-uuid)) :invoice-id (:invoice-id %)}]}))
            resp (post {:invoice-id (str (random-uuid))})]
        (is (= 409 (:status resp)) (:text resp))
        (is (= "conflict" (get-in resp [:json :error :type])))
        (is (= "foreign-key" (get-in resp [:json :error :details :constraint])))
        ;; SQLite's refusal names no column.
        (when-not (= :sqlite backend)
          (is (= "invoice-id" (get-in resp [:json :error :details :field])) (:text resp))))
      (testing "and so is deleting a row another still references, without a field"
        (let [invoice (str (random-uuid))]
          (db/execute-update! ctx {:insert-into :invoices :values [{:id invoice :number (str "R-" (name backend))}]})
          (db/execute-update! ctx {:insert-into :lines :values [{:id (str (random-uuid)) :invoice-id invoice}]})
          (is (= {:type :conflict :constraint :foreign-key}
                 (ex-data (thrown #(db/execute-update! ctx {:delete-from :invoices :where [:= :id invoice]})))))
          (testing "through raw SQL too"
            (let [ex (thrown #(db/execute-update! ctx ["DELETE FROM invoices WHERE id = ?" invoice]))]
              (is (= {:type :conflict :constraint :foreign-key} (ex-data ex)))
              (is (= "The record is still referenced by another record" (ex-message ex)))))
          ;; Read from the driver: SQLite does not say which way a key was
          ;; refused, and an UPDATE may be either.
          (when-not (= :sqlite backend)
            (testing "and re-keying it"
              (let [ex (thrown #(db/execute-update! ctx {:update :invoices :set {:id (str (random-uuid))}
                                                         :where [:= :id invoice]}))]
                (is (= {:type :conflict :constraint :foreign-key} (ex-data ex)))
                (is (= "The record is still referenced by another record" (ex-message ex)))))))))))

(deftest ^:integration other-failures-stay-database-errors
  (doseq [[backend ctx] @backends]
    (testing (name backend)
      (is (= :database-error
             (:type (ex-data (thrown #(db/execute-update! ctx {:insert-into :invoices
                                                               :values [{:id (str (random-uuid)) :number nil}]})))))
          "a NOT NULL is not a conflict"))))

(deftest ^:integration a-refused-row-is-not-quoted-back
  ;; PostgreSQL's NOT NULL and CHECK quote the whole row (BOU-626).
  (doseq [[backend ctx] @backends]
    (testing (name backend)
      (let [secret (str "secret-" (subs (str (random-uuid)) 0 8)) ; within VARCHAR(36), so NOT NULL is what refuses it
            ex     (thrown #(db/execute-update! ctx {:insert-into :invoices :values [{:id secret :number nil}]}))
            texts  (cond-> [(ex-message ex) (str (:original-error (ex-data ex)))]
                     ;; The driver's own message too, where the URL can keep values out of it.
                     (= :postgresql backend) (conj (ex-message (ex-cause ex))))]
        (is (some? ex))
        (doseq [t texts]
          (is (not (str/includes? t secret)) t)))
      ;; A bad cast quotes its input on PostgreSQL and H2 whatever the URL says.
      (when-not (= :sqlite backend)
        (let [secret (str "secret-" (random-uuid))
              ex     (thrown #(db/execute-query! ctx {:select [[[:cast secret :uuid] :x]]}))]
          (is (some? ex))
          (is (not (str/includes? (str (:original-error (ex-data ex))) secret))
              (:original-error (ex-data ex))))))))
