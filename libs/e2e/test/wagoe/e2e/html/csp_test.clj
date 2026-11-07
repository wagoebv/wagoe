(ns wagoe.e2e.html.csp-test
  "BOU-898: script-src is 'self'. A page that still carries an inline script,
   handler or hx-on gets it refused, and Chromium says so on the console."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.locator :as loc]
            [com.blockether.spel.page :as page]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(deftest ^:integration ^:e2e admin-pages-break-no-content-security-policy
  (spel/with-testing-page [pg]
    (let [refused (atom [])]
      (page/on-console pg (fn [msg]
                            (when (str/includes? (page/console-text msg) "Content Security Policy")
                              (swap! refused conj (page/console-text msg)))))
      (admin/login-as-admin! pg fx/*seed*)
      (doseq [url [(admin/admin-url "/users")
                   (admin/admin-url "/e2e-orders/new")
                   (admin/app-url "/web/users/new")
                   (admin/app-url "/web/profile")
                   (admin/app-url "/api-docs/index.html")]]
        (page/navigate pg url)
        (page/wait-for-load-state pg))
      (page/navigate pg (admin/admin-url "/e2e-tickets"))
      (page/wait-for-load-state pg)
      (loc/click (page/locator pg "button.filter-open"))
      (page/wait-for-selector pg ".filter-drawer-form" {:timeout 10000.0})
      (is (empty? @refused)))))

