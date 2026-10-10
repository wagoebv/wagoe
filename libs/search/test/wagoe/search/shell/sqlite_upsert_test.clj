(ns wagoe.search.shell.sqlite-upsert-test
  "On SQLite, which claims :on-conflict, the upsert is one INSERT … ON CONFLICT
   rather than a delete and an insert (ADR-039). Indexing a document twice
   leaves one row holding the second version."
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.persistence :as persistence]
            [wagoe.search.test.support :refer [create-search-documents-table! make-doc]])
  (:import [java.io File]
           [java.util UUID]))

(deftest ^:integration indexing-a-document-twice-keeps-one-row-on-sqlite
  (let [file (File/createTempFile "search-upsert" ".db")
        ctx  (db-factory/db-context {:adapter :sqlite :database-path (.getPath file)})
        ds   (:datasource ctx)]
    (try
      (create-search-documents-table! ds)
      (let [store     (persistence/create-search-store ds (:adapter ctx))
            entity-id (UUID/randomUUID)]
        (is (:on-conflict? store) "SQLite claims :on-conflict")
        (ports/upsert-document! store (make-doc {:entity-id entity-id :content-all "Original"}))
        (ports/upsert-document! store (make-doc {:entity-id entity-id :content-all "Updated"}))
        (is (= ["Updated"] (mapv :search_documents/content_all
                                 (jdbc/execute! ds ["SELECT content_all FROM search_documents"])))))
      (finally
        (db-factory/close-db-context! ctx)
        (.delete file)))))
