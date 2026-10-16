(ns wagoe.search.test.support
  "What the search tests share: the search_documents table and a sample document."
  (:require [migratus.core :as migratus]
            [wagoe.platform.shell.database.migrations :as migrations])
  (:import [java.time Instant]
           [java.util UUID]))

(defn create-search-documents-table!
  "The search_documents table, from the migrations the library ships, so the
   tests run against the schema an application gets."
  [ds]
  (migratus/migrate (migrations/migratus-config ds ["wagoe/search/migrations/"])))

(defn make-doc
  "A product search document, with `overrides` merged in. A `:content-all`
   override is the whole text, weight columns included, as indexing makes it;
   PostgreSQL's full-text search reads the weights, LIKE reads content-all."
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
          (when-let [text (:content-all overrides)]
            {:weight-a text :weight-b "" :weight-c ""})
          overrides)))
