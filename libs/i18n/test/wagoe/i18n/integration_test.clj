(ns wagoe.i18n.integration-test
  "Integration tests for i18n language switching end-to-end.

   Verifies that setting a user's :language preference causes UI
   output to render in the correct locale. Uses real catalogue from
   disk rather than mocks."
  (:require [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.i18n.shell.render :as render]
            [wagoe.i18n.shell.middleware :as middleware]
            [wagoe.i18n.core.translate :as translate]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- make-t-fn
  "Build a t-fn for the given locale chain using the real catalogue."
  [cat locale-chain]
  (fn
    ([key]         (translate/t cat locale-chain key))
    ([key params]  (translate/t cat locale-chain key params))
    ([key params n] (translate/t cat locale-chain key params n))))

;; =============================================================================
;; Language switching
;; =============================================================================

(deftest ^:integration dutch-user-sees-dutch-strings
  (testing "Dutch user sees Dutch strings in rendered output"
    (let [cat      (catalogue/load-catalogue "wagoe/i18n/translations")
          t-fn     (make-t-fn cat [:nl :en])
          hiccup   [:div
                    [:span [:t :user/badge-active]]
                    [:span [:t :user/badge-inactive]]]
          html     (render/render hiccup t-fn)]
      (is (re-find #"Actief" html)   "should contain Dutch 'Actief'")
      (is (re-find #"Inactief" html) "should contain Dutch 'Inactief'")
      (is (not (re-find #"\bActive\b" html))   "should not contain English 'Active'")
      (is (not (re-find #"\bInactive\b" html)) "should not contain English 'Inactive'")))

  (testing "English user sees English strings in rendered output"
    (let [cat      (catalogue/load-catalogue "wagoe/i18n/translations")
          t-fn     (make-t-fn cat [:en])
          hiccup   [:div [:span [:t :user/badge-active]]]
          html     (render/render hiccup t-fn)]
      (is (re-find #"Active" html) "should contain English 'Active'"))))

(deftest ^:integration locale-fallback-to-english
  (testing "Unknown locale falls back to English"
    (let [cat    (catalogue/load-catalogue "wagoe/i18n/translations")
          t-fn   (make-t-fn cat [:fr :en])
          hiccup [:div [:span [:t :user/badge-active]]]
          html   (render/render hiccup t-fn)]
      (is (re-find #"Active" html) "should fall back to English 'Active' for unsupported :fr locale"))))

(deftest ^:integration interpolation-across-locales
  (testing "Interpolation works in Dutch locale"
    (let [cat    (catalogue/load-catalogue "wagoe/i18n/translations")
          t-fn   (make-t-fn cat [:nl :en])
          hiccup [:div [:t :user/dashboard-welcome {:name "Thijs"}]]
          html   (render/render hiccup t-fn)]
      (is (re-find #"Thijs" html) "interpolated name should appear in output")))

  (testing "Interpolation works in English locale"
    (let [cat    (catalogue/load-catalogue "wagoe/i18n/translations")
          t-fn   (make-t-fn cat [:en])
          hiccup [:div [:t :user/dashboard-welcome {:name "Alice"}]]
          html   (render/render hiccup t-fn)]
      (is (re-find #"Alice" html) "interpolated name should appear in output"))))

;; =============================================================================
;; wrap-i18n middleware
;; =============================================================================

(deftest ^:integration wrap-i18n-injects-t-fn
  (testing "wrap-i18n injects :i18n/t and :i18n/locale-chain into request"
    (let [cat      (catalogue/load-catalogue "wagoe/i18n/translations")
          captured (atom nil)
          handler  (fn [req] (reset! captured req) {:status 200 :body ""})
          wrapped  (middleware/wrap-i18n handler {:catalogue     cat
                                                  :default-locale :en})
          request  {:user {:language "nl"}}]
      (wrapped request)
      (is (fn? (:i18n/t @captured))           "should inject :i18n/t function")
      (is (= [:nl :en] (:i18n/locale-chain @captured)) "should inject Dutch locale chain")))

  (testing "wrap-i18n uses default locale when no user language set"
    (let [cat      (catalogue/load-catalogue "wagoe/i18n/translations")
          captured (atom nil)
          handler  (fn [req] (reset! captured req) {:status 200 :body ""})
          wrapped  (middleware/wrap-i18n handler {:catalogue      cat
                                                  :default-locale :en})
          request  {:session {}}]
      (wrapped request)
      (is (= [:en] (:i18n/locale-chain @captured)) "should use English as default")))

  (testing "wrap-i18n uses tenant language when user language is absent"
    (let [cat      (catalogue/load-catalogue "wagoe/i18n/translations")
          captured (atom nil)
          handler  (fn [req] (reset! captured req) {:status 200 :body ""})
          wrapped  (middleware/wrap-i18n handler {:catalogue      cat
                                                  :default-locale :en})
          request  {:session {}
                    :tenant  {:settings {:language "nl"}}}]
      (wrapped request)
      (is (= [:nl :en] (:i18n/locale-chain @captured))
          "should use tenant locale before default when user locale is missing")))

  (testing "wrap-i18n falls back to [:session :user :language] for consumers that populate session upstream"
    (let [cat      (catalogue/load-catalogue "wagoe/i18n/translations")
          captured (atom nil)
          handler  (fn [req] (reset! captured req) {:status 200 :body ""})
          wrapped  (middleware/wrap-i18n handler {:catalogue     cat
                                                  :default-locale :en})
          ;; :user is NOT populated (auth middleware runs later in some stacks),
          ;; but a Ring wrap-session upstream has made [:session :user] available.
          request  {:session {:user {:language "nl"}}}]
      (wrapped request)
      (is (= [:nl :en] (:i18n/locale-chain @captured))
          "should resolve Dutch from session.user.language as a defensive fallback")
      (let [t-fn (:i18n/t @captured)]
        (is (= "Actief" (t-fn :user/badge-active))
            "eager :i18n/t should honor the session-derived user locale"))))

  (testing "injected t-fn renders Dutch when user language is nl"
    (let [cat     (catalogue/load-catalogue "wagoe/i18n/translations")
          result  (atom nil)
          handler (fn [req]
                    (let [t-fn (:i18n/t req)]
                      (reset! result (t-fn :user/badge-active)))
                    {:status 200 :body ""})
          wrapped (middleware/wrap-i18n handler {:catalogue      cat
                                                 :default-locale :en})
          request {:user {:language "nl"}}]
      (wrapped request)
      (is (= "Actief" @result) "t-fn from middleware should return Dutch translation")))

  (testing "resolve-t-fn picks up :user added after middleware ran"
    (let [cat     (catalogue/load-catalogue "wagoe/i18n/translations")
          result  (atom nil)
          ;; Simulate auth middleware adding :user after wrap-i18n
          auth-mw (fn [handler]
                    (fn [req]
                      (handler (assoc req :user {:language "nl"}))))
          handler (fn [req]
                    (let [t-fn (middleware/resolve-t-fn req)]
                      (reset! result (t-fn :user/badge-active)))
                    {:status 200 :body ""})
          wrapped (middleware/wrap-i18n (auth-mw handler)
                                        {:catalogue      cat
                                         :default-locale :en})
          ;; Request starts without :user (just like in production)
          request {}]
      (wrapped request)
      (is (= "Actief" @result)
          "resolve-t-fn should use Dutch locale from :user added by auth middleware"))))

;; =============================================================================
;; Nested markers
;; =============================================================================

(deftest ^:integration nested-markers-in-params-resolve-before-the-sentence
  ;; `parse-user-agent` returns [:t :user/session-device {:browser [:t …]
  ;; :device [:t …]}] because word order differs between languages. That only
  ;; works if the renderer resolves the parameters first — asserted here
  ;; against the real catalogue rather than inferred from the implementation.
  (let [cat    (catalogue/load-catalogue "wagoe/i18n/translations")
        marker [:t :user/session-device {:browser [:t :user/browser-chrome]
                                         :device  [:t :user/device-desktop]}]]
    (testing "English assembles the sentence from translated parts"
      (is (re-find #"Chrome on Desktop"
                   (render/render [:span marker] (make-t-fn cat [:en])))))

    (testing "Dutch changes the connecting word, not the product names"
      (let [html (render/render [:span marker] (make-t-fn cat [:nl :en]))]
        (is (re-find #"Chrome op Desktop" html))
        (is (not (re-find #"Chrome on Desktop" html)))))

    (testing "an unresolved parameter would show as a marker, not silently"
      ;; Guards the failure mode this replaced: a raw vector reaching the HTML.
      (is (not (re-find #"\[:t "
                        (render/render [:span marker] (make-t-fn cat [:en]))))))))
