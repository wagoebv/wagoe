(ns wagoe.devtools.shell.dashboard.pages.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.config.secrets :as secrets]
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

(deftest ^:unit a-deferred-dashboard-restart-links-to-where-it-went
  (let [url  "http://localhost:9998/dashboard/config"
        html (config-page/render-apply-result
              {:success? true :restarted [:wagoe/dashboard] :follow-url url :instance "old-id"})]
    (is (str/includes? html (str "data-url=\"" url "\"")) "the script follows this")
    (is (str/includes? html "data-instance=\"old-id\"") "and waits for a server that is not this one")
    (is (str/includes? html (str "href=\"" url "\"")) "and the reader can")
    (is (str/includes? html "fetch(new URL('") "the script is not escaped")))

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

(defn- resolve-test-secret!
  "A value read through #secret, as `load-config` would have read it."
  [secret-name value]
  (secrets/resolve-secret {:secret-sources {:test (reify secrets/ISecretSource
                                                    (fetch-secret [_ _] value))}}
                          [:test secret-name]))

(deftest ^:unit secret-values-are-redacted-and-apply-restores-them
  ;; Real values are shown so Apply can write them back, but a #secret value
  ;; sits under any key — a DSN, a URL — so it is redacted by value.
  (let [dsn  (resolve-test-secret! "dsn" "postgres://app:dashb0ard-pw@db/app")
        cfg  {:wagoe/db {:url dsn :host "localhost"}}
        html (config-page/render {:config cfg :config-editable? true})]
    (is (not (str/includes? html "dashb0ard-pw")))
    (is (str/includes? html "&lt;secret test:dsn&gt;"))
    (is (str/includes? html "localhost") "everything else is still shown")
    (testing "the placeholder an edit sends back is the value again"
      (is (= {:url dsn :host "localhost"}
             (config-page/parse-edited-value "{:url \"<secret test:dsn>\" :host \"localhost\"}" (:wagoe/db cfg) nil))))
    (testing "the preview does not print it either"
      (is (not (str/includes? (config-page/render-preview-fragment
                               :wagoe/db (:wagoe/db cfg) "{:url \"<secret test:dsn>\" :host \"other\"}")
                              "dashb0ard-pw"))))))

(deftest ^:unit the-textarea-holds-pretty-printed-edn
  (let [html (config-page/render {:config {:wagoe/db {:url (apply str "jdbc:h2:mem:" (repeat 100 "x"))
                                                      :aliases ["a" "b"]}}
                                  :config-editable? true})]
    (is (str/includes? html "{:aliases [&quot;a&quot; &quot;b&quot;]\n :url"))))

(deftest ^:unit a-function-in-a-section-is-left-out-and-kept-on-apply
  ;; :wagoe/dashboard can carry :ig-config-fn. Shown as #object[...] it is not
  ;; EDN, so Apply on that section always failed to parse.
  (let [f    (fn [] {})
        cfg  {:wagoe/dashboard {:port 9999 :ig-config-fn f}}
        html (config-page/render {:config cfg :config-editable? true})]
    (is (not (str/includes? html "#object")))
    (is (str/includes? html ":port 9999"))
    (is (str/includes? html "Not shown, as EDN cannot represent them: [:ig-config-fn]"))
    (is (str/includes? html "config-apply") "the section stays editable")
    (testing "the edited text parses, and the function comes back"
      (is (= {:port 1234 :ig-config-fn f}
             (config-page/parse-edited-value "{:port 1234}" (:wagoe/dashboard cfg) nil))))
    (testing "and the preview sees only the edit"
      (let [preview (config-page/render-preview-fragment
                     :wagoe/dashboard (:wagoe/dashboard cfg) "{:port 9999}")]
        (is (str/includes? preview "No changes detected"))))))

(deftest ^:unit a-section-that-is-itself-a-function-is-read-only
  (let [html (config-page/render {:config {::thunk (fn [] 1)} :config-editable? true})]
    (is (not (str/includes? html "#object")))
    (is (not (str/includes? html "config-apply")))))
