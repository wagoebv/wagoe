(ns wagoe.devtools.shell.dashboard.pages.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.devtools.shell.dashboard.pages.config :as config-page]
            [clojure.string :as str]))

(deftest ^:unit renders-config-page
  (testing "renders config tree with real values (dev-only, editable)"
    (let [html (config-page/render {:config {:wagoe/http {:port 3000}
                                             :wagoe/db {:host "localhost"}}
                                    :config-editable? true})]
      (is (string? html))
      (is (str/includes? html "Config"))
      (is (str/includes? html "3000"))
      (is (str/includes? html "localhost")))))

(deftest ^:unit renders-empty-when-no-config
  (testing "renders empty state without config"
    (let [html (config-page/render {})]
      (is (string? html))
      (is (str/includes? html "No config")))))

(deftest ^:unit apply-result-renders-the-message-not-the-map
  ;; The failure branches return {:error {:type … :message …}} since BOU-323;
  ;; this renderer reads that value into (str "✗ " …), so a version that read
  ;; :error itself would print the map into the page — and nothing covered it.
  (let [html (config-page/render-apply-result
              {:success? false
               :error {:type :restart-failed
                       :message "Failed to restart :wagoe/db (boom). All changes rolled back."}})]
    (is (str/includes? html "✗ Failed to restart :wagoe/db (boom). All changes rolled back."))
    (is (not (str/includes? html ":restart-failed"))
        "the type is for the caller to branch on, not for the page"))

  (testing "a failure with no message still says something"
    (is (str/includes? (config-page/render-apply-result {:success? false}) "✗ Apply failed")))

  (testing "success is unchanged"
    (is (str/includes? (config-page/render-apply-result {:success? true :restarted [:wagoe/db]})
                       "Config applied successfully"))))

(deftest ^:unit config-is-read-only-without-a-repl-system
  ;; Started by wagoe.main the dashboard can show the config (BOU-508), but
  ;; Apply restarts components through integrant.repl.state, which only (go)
  ;; fills. Offering Apply there reported "Config applied successfully" while
  ;; restarting nothing, so outside the REPL the editor is a viewer.
  (let [cfg {:wagoe/http {:port 3000}}]
    (testing "no REPL system: values shown, no way to apply"
      (let [html (config-page/render {:config cfg :config-editable? false})]
        (is (str/includes? html "3000"))
        (is (not (str/includes? html "config-apply")))
        (is (not (str/includes? html "config-preview")))
        (is (str/includes? html "readonly"))
        (is (str/includes? html "(go)")
            "says what would make it editable")))

    (testing "the default is read-only — editing has to be switched on"
      (is (not (str/includes? (config-page/render {:config cfg}) "config-apply"))))

    (testing "with a REPL system the editor is unchanged"
      (let [html (config-page/render {:config cfg :config-editable? true})]
        (is (str/includes? html "config-apply"))
        (is (str/includes? html "config-preview"))
        (is (not (str/includes? html "readonly")))))))
