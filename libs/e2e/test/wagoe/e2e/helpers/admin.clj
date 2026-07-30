(ns wagoe.e2e.helpers.admin
  "Shared helpers for admin UI e2e tests.
   Wraps BOU-9 auth helpers with admin-specific login and
   DOM query utilities for tables, forms, and HTMX interactions."
  (:require [clojure.string :as str]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.helpers.reset :as reset]))

(def ^:private base-url (reset/default-base-url))

(defn admin-url
  "Build a full admin URL from a relative path."
  [path]
  (str base-url "/web/admin" path))

(defn login-as-admin!
  "Navigate to /web/login, fill seed admin credentials, wait for redirect
   to /web/admin/users. Returns the page for chaining."
  [pg seed]
  (page/navigate pg (str base-url "/web/login"))
  (page/wait-for-load-state pg)
  (loc/fill (page/locator pg "input[name='email']") (-> seed :admin :email))
  (loc/fill (page/locator pg "input[name='password']") (-> seed :admin :password))
  (loc/click (page/locator pg "form.form-card button[type='submit']"))
  (page/wait-for-url pg #".*/web/admin/users.*" {:timeout 10000.0})
  pg)

(defn login-as-user!
  "Navigate to /web/login, fill seed user credentials, wait for redirect
   to /web/dashboard. Returns the page for chaining."
  [pg seed]
  (page/navigate pg (str base-url "/web/login"))
  (page/wait-for-load-state pg)
  (loc/fill (page/locator pg "input[name='email']") (-> seed :user :email))
  (loc/fill (page/locator pg "input[name='password']") (-> seed :user :password))
  (loc/click (page/locator pg "form.form-card button[type='submit']"))
  (page/wait-for-url pg #".*/web/dashboard.*" {:timeout 10000.0})
  pg)

(defn install-htmx-settle-listener!
  "Install a one-shot htmx:afterSettle listener BEFORE triggering an
   interaction. Stores a promise on window that resolves on settle or
   after 10s (safety timeout). Call `await-htmx-settle!` after the
   interaction to wait for the result."
  [pg]
  (page/evaluate pg
                 (str "window.__htmxSettled = new Promise(r => {"
                      "  let done = false;"
                      "  document.addEventListener('htmx:afterSettle', () => { if (!done) { done = true; r(true); } }, {once:true});"
                      "  setTimeout(() => { if (!done) { done = true; r(false); } }, 10000);"
                      "});")))

(defn await-htmx-settle!
  "Await the promise installed by `install-htmx-settle-listener!`.
   Must be called AFTER the interaction that triggers the HTMX request."
  [pg]
  (page/evaluate pg "window.__htmxSettled"))

(defn table-headers
  "Read visible column header texts from table.data-table thead th.
   Returns a vector of lowercase trimmed strings."
  [pg]
  (let [ths (page/locator pg "table.data-table thead th")]
    (->> (range (loc/count-elements ths))
         (mapv #(-> (loc/nth-element ths %)
                    loc/text-content
                    str/trim
                    str/lower-case))
         (filterv seq))))

(defn table-row-count
  "Count data rows in table.data-table tbody."
  [pg]
  (loc/count-elements (page/locator pg "table.data-table tbody tr")))

(defn search!
  "Fill the search input and trigger the HTMX search by clicking the
   search button. Waits for the HTMX settle event after the click."
  [pg query]
  (let [input (page/locator pg "input.search-input")]
    (loc/fill input query)
    ;; Click the search button to trigger the HTMX request immediately
    ;; (avoids relying on keyup debounce timing)
    (install-htmx-settle-listener! pg)
    (loc/click (page/locator pg ".toolbar-search button[aria-label]"))
    (await-htmx-settle! pg)))

(defn has-empty-state?
  "Check if the empty state element is visible or the table has no data rows."
  [pg]
  (or (pos? (loc/count-elements (page/locator pg ".empty-state")))
      (zero? (loc/count-elements (page/locator pg "table.data-table tbody tr")))))

(defn field-group-visible?
  "Check if a field group with the given data-group-id is visible."
  [pg group-id]
  (loc/is-visible? (page/locator pg (str "div.form-field-group[data-group-id='" group-id "']"))))

(defn flash-visible?
  "Check if a flash/alert message of the given type is visible."
  [pg flash-type]
  (loc/is-visible? (page/locator pg (str ".alert.alert-" (name flash-type)))))
