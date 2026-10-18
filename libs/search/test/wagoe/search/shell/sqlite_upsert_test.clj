(ns wagoe.search.shell.sqlite-upsert-test
  "On SQLite, which claims :on-conflict, the upsert is one INSERT … ON CONFLICT
   rather than a delete and an insert (ADR-039). Indexing a document twice
   leaves one row holding the second version, in every column but the key.
   Also the LIKE fallback under a Turkish locale: SQLite lowers with the root
   locale, so a query lowered by the JVM's missed (BOU-608)."
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.persistence :as persistence]
            [wagoe.search.test.support :refer [create-search-documents-table! make-doc]])
  (:import [java.io File]
           [java.util Locale UUID]))

(defn- with-sqlite-store
  "Call `f` with a datasource and a SearchStore on a fresh SQLite file."
  [f]
  (let [file (File/createTempFile "search-upsert" ".db")
        ctx  (db-factory/db-context {:adapter :sqlite :database-path (.getPath file)})
        ds   (:datasource ctx)]
    (try
      (create-search-documents-table! ds)
      (f ds (persistence/create-search-store ds (:adapter ctx)))
      (finally
        (db-factory/close-db-context! ctx)
        (.delete file)))))

(deftest ^:integration indexing-a-document-twice-keeps-one-row-on-sqlite
  (with-sqlite-store
    (fn [ds store]
      (let [entity-id (UUID/randomUUID)]
        (is (:on-conflict? store) "SQLite claims :on-conflict")
        (ports/upsert-document! store (make-doc {:entity-id entity-id :content-all "Original"
                                                 :metadata {:v 1} :filters {:status "draft"}}))
        (ports/upsert-document! store (make-doc {:entity-id entity-id :content-all "Updated"
                                                 :entity-type :service :metadata {:v 2}
                                                 :filters {:status "published"}}))
        (let [rows (jdbc/execute! ds ["SELECT entity_type, content_all, metadata, filters FROM search_documents"]
                                  {:builder-fn rs/as-unqualified-lower-maps})]
          (is (= 1 (count rows)))
          (is (= {:entity_type "service" :content_all "Updated" :metadata (pr-str {:v 2})}
                 (select-keys (first rows) [:entity_type :content_all :metadata]))
              "every non-key column takes the second version")
          (is (re-find #"published" (str (:filters (first rows))))))))))

(deftest ^:integration like-fallback-under-a-turkish-locale-on-sqlite
  (with-sqlite-store
    (fn [_ store]
      (let [id      (UUID/randomUUID)
            default (Locale/getDefault)]
        (ports/upsert-document! store (make-doc {:entity-id id :content-all "ID card"}))
        (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
        (try
          (is (= [id] (map :entity-id (ports/search-documents store :product-search :product
                                                              "ID" {:limit 5 :offset 0}))))
          (is (= 1 (ports/count-results store :product "ID" {:index-id :product-search})))
          (is (= [id] (map :entity-id (ports/suggest-documents store :product-search :product
                                                               "ID" {:limit 5}))))
          (finally (Locale/setDefault default)))))))
