(ns wagoe.admin.shell.module-wiring-test
  "The admin module's Integrant graph, and what its handlers can reach."
  (:require [clojure.test :refer [deftest testing is]]
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

  (testing "an invalid pattern is dropped, not carried to a formatter that throws"
    ;; `ofPattern` throws on an unknown pattern letter, and it would throw once
    ;; per cell from a core namespace — 500ing every list page over one typo.
    (let [opts (support/display-options {:date-time-format "yyyy-MM-dd bb"
                                         :date-format      "yyyy-QQQQQQ"}
                                        {})]
      (is (nil? (:date-time-format opts)))
      (is (nil? (:date-format opts)))))

  (testing "a pattern that is not a string is dropped, not cast"
    ;; `ofPattern` takes a String: `:date-format :iso` in config.edn threw a
    ;; ClassCastException with no `:type`, 500ing every admin page.
    (let [opts (support/display-options {:date-time-format :iso :date-format 42} {})]
      (is (nil? (:date-time-format opts)))
      (is (nil? (:date-format opts)))))

  (testing "the request's locale reaches the renderer, so textual patterns match the page"
    (is (= (java.util.Locale/forLanguageTag "nl")
           (:locale (support/display-options {} {:i18n/locale-chain [:nl :en]}))))
    (is (= (java.util.Locale/forLanguageTag "en")
           (:locale (support/display-options {} {:i18n/default-locale :en}))))
    (is (nil? (:locale (support/display-options {} {}))))))
