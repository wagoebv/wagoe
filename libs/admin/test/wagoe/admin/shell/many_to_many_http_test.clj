(ns wagoe.admin.shell.many-to-many-http-test
  "BOU-778 through the handlers: the form edits links with checkboxes or a
   multiple combobox, the list shows them as chips and filters on them. On
   H2: many-to-many-test sweeps the SQL over every engine."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.shell.http.handlers.list :as list-handlers]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private admin-config
  {:entity-discovery {:mode :allowlist :allowlist #{:posts :tags :post-tags :authors :post-authors}}
   :entities         {:tags    {:title-field :name}
                      :authors {:title-field :name}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(def ^:private i18n-catalogue
  (catalogue/create-map-catalogue (catalogue/load-catalogue "wagoe/i18n/translations")))

(def ^:dynamic *sys* nil)

(defn- with-system [f]
  (let [ctx (db-factory/db-context {:adapter :h2 :database-path (str "mem:bou778http" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
    (try
      (doseq [stmt ["CREATE TABLE posts (id VARCHAR(36) PRIMARY KEY, title VARCHAR(100) NOT NULL)"
                    "CREATE TABLE tags (id VARCHAR(36) PRIMARY KEY, name VARCHAR(50) NOT NULL)"
                    "CREATE TABLE authors (id VARCHAR(36) PRIMARY KEY, name VARCHAR(50) NOT NULL)"
                    (str "CREATE TABLE post_tags (post_id VARCHAR(36) NOT NULL, tag_id VARCHAR(36) NOT NULL,"
                         " PRIMARY KEY (post_id, tag_id))")
                    (str "CREATE TABLE post_authors (post_id VARCHAR(36) NOT NULL, author_id VARCHAR(36) NOT NULL,"
                         " PRIMARY KEY (post_id, author_id))")]]
        (db/execute-update! ctx {:raw stmt}))
      (let [schema (schema-repo/create-schema-repository ctx admin-config {})
            svc    (service/create-admin-service ctx schema
                                                 (logging-no-op/create-logging-component {})
                                                 (error-reporting-no-op/create-error-reporting-component {})
                                                 admin-config)
            tags   (into {} (for [n ["News" "Sport" "Tech"]]
                              [n (str (:id (ports/create-entity svc :tags {:name n})))]))
            ;; More authors than checkboxes hold: a combobox
            authors (into {} (for [i (range 1 13)
                                   :let [n (format "Author %02d" i)]]
                               [n (str (:id (ports/create-entity svc :authors {:name n})))]))]
        (binding [*sys* {:schema schema :svc svc :tags tags :authors authors}]
          (f)))
      (finally (db-factory/close-db-context! ctx)))))

(use-fixtures :each with-system)

(defn- request [method uri path-params & [form query]]
  {:request-method method
   :uri            uri
   :user           admin-user
   :path-params    path-params
   :form-params    (or form {})
   :query-params   (or query {})
   :i18n/catalogue i18n-catalogue
   :i18n/default-locale :en})

(defn- body [response] (str (:body response)))

(defn- links [post k]
  (mapv :title (:items (get (ports/linked-records (:svc *sys*) :posts k [post] {}) (str post)))))

(deftest ^:integration the-form-edits-links
  (let [{:keys [schema svc tags authors]} *sys*]
    (testing "a few records are checkboxes, many a multiple combobox; the join tables are not in the sidebar"
      (let [page (body ((detail/new-entity-handler svc schema admin-config)
                        (request :get "/web/admin/posts/new" {:entity "posts"})))]
        (is (str/includes? page "Linked records"))
        (is (str/includes? page "<input name=\"tags\" type=\"checkbox\""))
        (is (str/includes? page "class=\"combobox combobox-multiple\""))
        (is (str/includes? page "hx-get=\"/web/admin/posts/options/authors\""))
        (is (re-find #"name=\"__links\"[^>]*value=\"tags\"" page))
        (is (not (str/includes? page "href=\"/web/admin/post-tags\"")))))

    (testing "a create saves the ticked and picked records"
      (let [response ((crud/create-entity-handler svc schema admin-config)
                      (request :post "/web/admin/posts" {:entity "posts"}
                               {"title"   "Hello"
                                "tags"    [(tags "News") (tags "Tech")]
                                "authors" (authors "Author 03")
                                "__links" ["tags" "authors"]}))
            post     (:id (first (:records (ports/list-entities svc :posts {}))))]
        (is (= 200 (:status response)))
        (is (= ["News" "Tech"] (links post :tags)))
        (is (= ["Author 03"] (links post :authors)))

        (testing "the edit page shows them as chips and ticked boxes"
          (let [page (body ((detail/entity-detail-handler svc schema admin-config)
                            (request :get (str "/web/admin/posts/" post) {:entity "posts" :id (str post)})))]
            (is (re-find (re-pattern (str "<input checked[^>]*name=\"tags\"[^>]*value=\"" (tags "News") "\"")) page))
            (is (re-find #"class=\"link-chip\" data-value=\"[^\"]+\"><span class=\"link-chip-label\">Author 03" page))
            (is (str/includes? page (str "href=\"/web/admin/tags/" (tags "News") "\"")) "the summary links them")))

        (testing "an update with nothing ticked unlinks them; the other relation is replaced"
          (let [response ((crud/update-entity-handler svc schema admin-config)
                          (request :put (str "/web/admin/posts/" post) {:entity "posts" :id (str post)}
                                   {"title" "Hello" "authors" [(authors "Author 01") (authors "Author 02")]
                                    "__links" ["tags" "authors"]}))]
            (is (= 200 (:status response)))
            (is (= [] (links post :tags)))
            (is (= ["Author 01" "Author 02"] (links post :authors)))))

        (testing "an unknown record is a 422 on its relation, and the form keeps what was chosen"
          (let [response ((crud/update-entity-handler svc schema admin-config)
                          (request :put (str "/web/admin/posts/" post) {:entity "posts" :id (str post)}
                                   {"title" "Hello" "tags" [(tags "Sport") (str (random-uuid))]
                                    "__links" "tags"}))]
            (is (= 422 (:status response)))
            (is (str/includes? (body response) "Not a record this can link"))
            (is (= ["Author 01" "Author 02"] (links post :authors)))))))))

(deftest ^:integration the-combobox-searches-the-linked-entity
  (let [{:keys [schema svc]} *sys*
        page (body ((crud/field-options-handler svc schema admin-config)
                    (request :get "/web/admin/posts/options/authors" {:entity "posts" :field "authors"} nil {"q" "or 1"})))]
    (is (str/includes? page "data-label=\"Author 10\""))
    (is (not (str/includes? page "Author 09")))))

(deftest ^:integration the-list-shows-chips-and-filters-on-a-link
  (let [{:keys [schema svc tags]} *sys*
        _ (ports/create-entity svc :posts {:title "Match" :admin/links {:tags [(tags "Sport") (tags "News") (tags "Tech")]}})
        _ (ports/create-entity svc :posts {:title "Other"})
        list-page (fn [query]
                    (body ((list-handlers/entity-list-handler svc schema admin-config)
                           (request :get "/web/admin/posts" {:entity "posts"} nil query))))]
    (testing "the default columns carry the links as chips"
      (let [page (list-page {})]
        (is (str/includes? page "c-links\""))
        (is (re-find #"link-chip\"><a href=\"/web/admin/tags/[^\"]+\" title=\"News\">News" page))))
    (testing "has X, with the chip naming it"
      (let [page (list-page {"filters[tags][op]" "has" "filters[tags][value]" (tags "Sport")})]
        (is (str/includes? page "Match"))
        (is (not (str/includes? page ">Other<")))
        (is (str/includes? page "Tags has Sport"))))
    (testing "has no X"
      (let [page (list-page {"filters[tags][op]" "has-not" "filters[tags][value]" (tags "Sport")})]
        (is (str/includes? page "Other"))
        (is (not (str/includes? page ">Match<")))))
    (testing "the filter drawer offers the records"
      (let [page (body ((list-handlers/filter-drawer-handler svc schema admin-config)
                        (request :get "/web/admin/posts/filters" {:entity "posts"})))]
        (is (str/includes? page "name=\"filters[tags][op]\""))
        (is (str/includes? page ">has no<"))
        (is (re-find #"<option[^>]*value=\"[^\"]+\"[^>]*>Sport</option>" page))))))
