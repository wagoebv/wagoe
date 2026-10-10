(ns wagoe.search.test.support
  "What the search tests share: the search_documents table and a sample document."
  (:require [next.jdbc :as jdbc])
  (:import [java.time Instant]
           [java.util UUID]))

(defn create-search-documents-table!
  "The search_documents table, as the tests create it on H2 and SQLite."
  [ds]
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS search_documents (
                        id          TEXT NOT NULL PRIMARY KEY,
                        index_id    TEXT NOT NULL,
                        entity_type TEXT NOT NULL,
                        entity_id   TEXT NOT NULL,
                        language    TEXT NOT NULL DEFAULT 'english',
                        weight_a    TEXT NOT NULL DEFAULT '',
                        weight_b    TEXT NOT NULL DEFAULT '',
                        weight_c    TEXT NOT NULL DEFAULT '',
                        weight_d    TEXT NOT NULL DEFAULT '',
                        content_all TEXT NOT NULL DEFAULT '',
                        metadata    TEXT,
                        filters     TEXT,
                        updated_at  TEXT NOT NULL,
                        UNIQUE (index_id, entity_id))"]))

(defn make-doc
  "A product search document, with `overrides` merged in."
  ([] (make-doc {}))
  ([overrides]
   (merge {:id          (str (UUID/randomUUID))
           :index-id    :product-search
           :entity-type :product
           :entity-id   (UUID/randomUUID)
           :language    "english"
           :weight-a    "Widget Pro"
           :weight-b    "A great widget for professionals"
           :weight-c    "tools hardware"
           :weight-d    ""
           :content-all "Widget Pro A great widget for professionals tools hardware"
           :updated-at  (Instant/now)}
          overrides)))
