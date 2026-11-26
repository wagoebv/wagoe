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

(defn delete-within-a-tenant
  "Index a document of tenant-1, then delete it as tenant-2 and as tenant-1
   (BOU-1114). Returns the document count after each."
  [store]
  (let [doc (make-doc {:filters {:tenant-id "tenant-1"}})
        after-delete-as (fn [tenant]
                          (ports/delete-tenant-document! store :product-search (:entity-id doc) tenant)
                          (ports/count-documents store :product-search))]
    (ports/upsert-document! store doc)
    {:as-another (after-delete-as "tenant-2") :as-its-own (after-delete-as "tenant-1")}))

(defn index-across-tenants
  "Index one entity id as tenant-1, as tenant-2, under both tenant spellings,
   with a blank tenant, as tenant-1 again and with no tenant; and claim, as
   tenant-2, rows written with no tenant (BOU-1121).
   Returns what each upsert did, :indexed or its error's :type, whether tenant-1
   still finds its document after tenant-2's try, and the row count."
  [store]
  (let [id      (UUID/randomUUID)
        upsert! #(try (ports/upsert-document! store (make-doc %)) :indexed
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
        as      #(upsert! {:entity-id id :content-all %2 :filters (when %1 {:tenant-id %1})})
        claim   (fn [filters]
                  (let [legacy (UUID/randomUUID)]
                    (upsert! {:entity-id legacy :filters filters})
                    (upsert! {:entity-id legacy :filters {:tenant-id "tenant-2"}})))]
    (array-map
     :first            (as "tenant-1" "Original")
     :as-another       (as "tenant-2" "Moved")
     :tenant-1-finds?  (= [id] (map :entity-id (ports/search-documents store :product-search :product "Original"
                                                                       {:limit 5 :offset 0 :filters {:tenant-id "tenant-1"}})))
     :in-two-spellings (upsert! {:entity-id id :filters {:tenant-id "tenant-1" :tenant_id "tenant-2"}})
     :blank-tenant     (upsert! {:entity-id id :filters {:tenant-id " "}})
     :as-its-own       (as "tenant-1" "Updated")
     :with-no-tenant   (as nil "Untenanted")
     :claim-no-filters (claim nil)
     :claim-no-tenant  (claim {:status "active"})
     :rows             (ports/count-documents store :product-search))))

(def indexed-across-tenants
  "What `index-across-tenants` returns when every try at tenant-1's row by
   another tenant, or by none it can check, is refused."
  {:first :indexed :as-another :conflict :tenant-1-finds? true
   :in-two-spellings :conflict :blank-tenant :validation-error :as-its-own :indexed
   :with-no-tenant :indexed :claim-no-filters :indexed :claim-no-tenant :indexed :rows 3})
