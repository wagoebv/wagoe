(ns wagoe.search.test.support
  "What the search tests share: the search_documents table and a sample document."
  (:require [migratus.core :as migratus]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.search.ports :as ports])
  (:import [java.time Instant]
           [java.util Locale UUID]))

(defn create-search-documents-table!
  "The search_documents table, from the migrations the library ships, so the
   tests run against the schema an application gets."
  [ds]
  (migratus/migrate (migrations/migratus-config ds ["wagoe/search/migrations/"])))

(defn call-with-locale
  "Call `f` with the JVM's default locale set to `tag`, then restore it."
  [tag f]
  (let [default (Locale/getDefault)]
    (Locale/setDefault (Locale/forLanguageTag tag))
    (try (f) (finally (Locale/setDefault default)))))

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

(defn find-ID-under-a-turkish-locale
  "Index \"ID card\", then search, count and suggest (through `suggest-store`)
   for \"ID\" with the JVM in tr-TR, which lowers \"ID\" to \"ıd\" (BOU-608).
   Returns [entity-id what-each-found]."
  [store suggest-store]
  (let [id (UUID/randomUUID)]
    (ports/upsert-document! store (make-doc {:entity-id id :content-all "ID card"}))
    [id (call-with-locale "tr-TR"
          #(hash-map :search  (map :entity-id (ports/search-documents store :product-search :product
                                                                      "ID" {:limit 5 :offset 0}))
                     :count   (ports/count-results store :product "ID" {:index-id :product-search})
                     :suggest (map :entity-id (ports/suggest-documents suggest-store :product-search
                                                                       :product "ID" {:limit 5}))))]))
