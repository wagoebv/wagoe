(ns wagoe.admin.shell.module-wiring-test
  "The admin module's Integrant graph, and what its handlers can reach."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.tools.logging.test :as log-test]
            [integrant.core :as ig]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.support :as support]
            [wagoe.admin.shell.module-wiring :as wiring]))

(defn- routes-config
  "The `:config` the admin route handlers are built with."
  [settings app-settings]
  (-> (wiring/ig-config settings {:config {:active {:wagoe/settings app-settings}}})
      (get-in [:components :wagoe/admin-routes :config])))

(deftest ^:unit date-formats-reach-the-route-handlers
  ;; A module's settings are its own `:active` section, so the date patterns
  ;; under `:wagoe/settings` were unreachable from every admin handler — the
  ;; reason nothing read them and the list table showed raw database timestamps
  ;; (BOU-382).
  (testing "the application's date patterns are copied into the routes config"
    (let [cfg (routes-config {:logo-url "/logo.svg"}
                             {:date-format      "yyyy-MM-dd"
                              :date-time-format "yyyy-MM-dd HH:mm:ss"})]
      (is (= "yyyy-MM-dd HH:mm:ss" (:date-time-format cfg)))
      (is (= "yyyy-MM-dd" (:date-format cfg)))
      (is (= "/logo.svg" (:logo-url cfg))
          "and the module's own settings still come through")))

  (testing "the module's own value wins over the application default"
    (let [cfg (routes-config {:date-time-format "dd MMM yyyy, HH:mm"}
                             {:date-time-format "yyyy-MM-dd HH:mm:ss"})]
      (is (= "dd MMM yyyy, HH:mm" (:date-time-format cfg)))))

  (testing "an application that configures neither still builds"
    (let [cfg (routes-config {} nil)]
      (is (nil? (:date-time-format cfg)))
      (is (map? cfg))))

  (testing "the graph still names the components it did before"
    (let [graph (wiring/ig-config {} {})]
      (is (= #{:wagoe/admin-schema-provider :wagoe/admin-service :wagoe/admin-routes}
             (set (keys (:components graph)))))
      (is (= 1 (count (:routes graph)))))))

(deftest ^:unit every-admin-entity-has-read-and-write-permissions
  (is (= #{:admin.users/read :admin.users/write :admin.orders/read :admin.orders/write}
         (-> (wiring/ig-config {:entity-discovery {:mode :allowlist :allowlist #{:users :orders}}} {})
             (get-in [:authorization :permissions])
             keys
             set))))

(deftest ^:unit settings-without-defaults-still-list-entities
  ;; `wagoe add admin` writes three keys and no `:entity-discovery`; the nil
  ;; mode threw and every admin page answered 500 (BOU-637).
  (let [provider (fn [settings]
                   (let [{:keys [config]} (get-in (wiring/ig-config settings {})
                                                  [:components :wagoe/admin-schema-provider])]
                     (schema-repo/create-schema-repository nil config)))]
    (testing "the settings `wagoe add admin` writes boot an admin with no entities"
      (is (= [] (ports/list-available-entities
                 (provider {:enabled? true :base-path "/web/admin" :require-role :admin})))))

    (testing "configured values win, and a partial map keeps the other defaults"
      (let [cfg (routes-config {:entity-discovery {:allowlist #{:users}}
                                :pagination       {:default-page-size 20}}
                               nil)]
        (is (= {:mode :allowlist :allowlist #{:users}} (:entity-discovery cfg)))
        (is (= {:default-page-size 20 :max-page-size 200} (:pagination cfg)))))

    (testing "a setting left nil gets its default, and paging stays at 20"
      (let [cfg (routes-config {:entity-discovery nil} nil)]
        (is (= :allowlist (get-in cfg [:entity-discovery :mode])))
        (is (= 20 (get-in cfg [:pagination :default-page-size])))))

    (testing "no default \"Other\" label displaces the translated one"
      (is (nil? (get-in (routes-config {} nil) [:ui :field-grouping :other-label]))))))

(deftest ^:unit bad-date-patterns-are-dropped-at-boot
  ;; Checked once here rather than per request: a misconfigured application
  ;; should say so at startup, not log the same warning on every page load.
  (testing "an unknown pattern letter never reaches a handler"
    ;; `ofPattern` throws on it, and it would throw once per cell from a core
    ;; namespace — 500ing every list page over one typo.
    (let [cfg (routes-config {} {:date-time-format "yyyy-MM-dd bb"
                                 :date-format      "yyyy-QQQQQQ"})]
      (is (nil? (:date-time-format cfg)))
      (is (nil? (:date-format cfg)))))

  (testing "a pattern that is not a string is dropped, not cast"
    ;; `ofPattern` takes a String: `:date-format :iso` in config.edn threw a
    ;; ClassCastException with no `:type`, 500ing every admin page.
    (let [cfg (routes-config {} {:date-time-format :iso :date-format 42})]
      (is (nil? (:date-time-format cfg)))
      (is (nil? (:date-format cfg)))))

  (testing "the module's own bad pattern is dropped too"
    (let [cfg (routes-config {:date-time-format "yyyy-MM-dd bb"}
                             {:date-time-format "yyyy-MM-dd HH:mm"})]
      (is (nil? (:date-time-format cfg))
          "the module's value wins the merge, so it is the one checked")))

  (testing "a valid pattern is left alone"
    (is (= "yyyy-MM-dd HH:mm:ss"
           (:date-time-format (routes-config {} {:date-time-format "yyyy-MM-dd HH:mm:ss"}))))))

(deftest ^:unit display-options-carry-a-zone-and-the-configured-patterns
  (testing "the zone is read in the shell, which core may not do"
    (let [opts (support/display-options {:date-time-format "yyyy-MM-dd HH:mm:ss"
                                         :date-format      "yyyy-MM-dd"}
                                        {})]
      (is (instance? java.time.ZoneId (:zone-id opts)))
      (is (= "yyyy-MM-dd HH:mm:ss" (:date-time-format opts)))
      (is (= "yyyy-MM-dd" (:date-format opts)))))

  (testing "an unconfigured application leaves the patterns to the renderer"
    (let [opts (support/display-options {} {})]
      (is (instance? java.time.ZoneId (:zone-id opts)))
      (is (nil? (:date-time-format opts)))))

  (testing "the request's locale reaches the renderer, so textual patterns match the page"
    (is (= (java.util.Locale/forLanguageTag "nl")
           (:locale (support/display-options {} {:i18n/locale-chain [:nl :en]}))))
    (is (= (java.util.Locale/forLanguageTag "en")
           (:locale (support/display-options {} {:i18n/default-locale :en}))))
    (is (nil? (:locale (support/display-options {} {}))))))

(deftest ^:unit the-deprecated-component-says-so-when-it-is-wired
  ;; A `### Deprecated` entry the running system says nothing about is an
  ;; announcement to whoever already read it (BOU-433). The module contributes
  ;; admin-schema-provider/-service/-routes and not this key, so init only runs
  ;; for a hand-wired one.
  (testing "init warns, naming the removal"
    (log-test/with-log
      (ig/init-key :wagoe/admin {:logo-url "/logo.svg"})
      (is (log-test/logged? 'wagoe.admin.shell.module-wiring :warn #"DEPRECATED"))
      (is (log-test/logged? 'wagoe.admin.shell.module-wiring :warn #"removed in 2\.0"))))

  (testing "and switching the module on does not reach it"
    (is (not (contains? (:components (wiring/ig-config {} {:config {:active {}}}))
                        :wagoe/admin))
        "a warning on every correct application is a warning nobody reads")))

(defn- with-mode [mode]
  {:config {:active {:wagoe/authorization {:mode mode}}}})

(deftest ^:unit every-admin-entity-has-viewer-and-editor-roles
  (is (= {:admin.orders/viewer #{:admin.orders/read}
          :admin.orders/editor #{:admin.orders/read :admin.orders/write}}
         (-> (wiring/ig-config {:entity-discovery {:mode :allowlist :allowlist #{:orders}}} {})
             (get-in [:authorization :roles])
             (update-vals :permissions)))))

(deftest ^:unit rights-are-wired-only-when-authorization-checks
  (let [routes (fn [ctx] (get-in (wiring/ig-config {} ctx) [:components :wagoe/admin-routes]))]
    (testing ":off wires what it always did"
      (is (not-any? (routes {}) [:permission-registry :rights]))
      (is (= :off (get-in (routes {}) [:config :authorization-mode]))))
    (testing ":report and :enforce read the registry and the user's rights"
      (doseq [mode [:report :enforce]]
        (is (= mode (get-in (routes (with-mode mode)) [:config :authorization-mode])))
        (is (= (ig/ref :wagoe/permission-registry) (:permission-registry (routes (with-mode mode)))))
        (is (= (ig/ref :wagoe/user-group-service) (:rights (routes (with-mode mode)))))))))

(deftest ^:unit require-role-is-deprecated-once-authorization-checks
  (log-test/with-log
    (wiring/ig-config {:require-role :admin} {})
    (is (not (log-test/logged? 'wagoe.admin.shell.module-wiring :warn #":require-role")) ":off says nothing"))
  (log-test/with-log
    (wiring/ig-config {:require-role :admin} (with-mode :enforce))
    (is (log-test/logged? 'wagoe.admin.shell.module-wiring :warn #"DEPRECATED :require-role.*removed in 2\.0")))
  (log-test/with-log
    (wiring/ig-config {} (with-mode :enforce))
    (is (not (log-test/logged? 'wagoe.admin.shell.module-wiring :warn #":require-role"))
        "the defaults carry it; a config without it is not warned")))

(defn order-domain
  "A relation condition, named by symbol as config.edn would."
  [ids]
  [:in :order_id {:select [:id] :from [:orders] :where [:in :domain_id ids]}])

(deftest ^:unit a-scope-symbol-resolves-to-its-fn-at-boot
  (let [scope (fn [s] (get-in (wiring/ig-config {:entities {:lines {:scope s}}} {})
                              [:components :wagoe/admin-routes :config :entities :lines :scope]))]
    (is (= order-domain (:domain-id (scope {:domain-id `order-domain}))))
    (is (= {:domain-id :domain-id} (scope {:domain-id :domain-id})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"resolves to nothing"
                          (scope {:domain-id 'no.such/condition})))))

(def ^:private tabs [{:label "Rights" :href "/web/users/{id}/rights"}])

(deftest ^:unit a-detail-tabs-symbol-resolves-to-its-var-at-boot
  (let [resolved (fn [t] (get-in (wiring/ig-config {:entities {:users {:detail-tabs t}}} {})
                                 [:components :wagoe/admin-routes :config :entities :users :detail-tabs]))]
    (is (= tabs (resolved `tabs)))
    (is (= tabs (resolved tabs)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"resolves to nothing" (resolved 'no.such/tabs)))))
