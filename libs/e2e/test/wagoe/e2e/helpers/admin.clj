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

(def ^:private stale-marker
  "Attribute stamped on what an HTMX request is about to replace.

   Waiting for a mark to disappear waits for the swap that actually happened.
   The `htmx:afterSettle` listener it replaced fired for any swap anywhere on
   the page, so it could resolve on something else entirely and let the
   assertion run against DOM that had not been touched yet (BOU-297)."
  "data-e2e-stale")

(defn- mark-stale!
  "Stamp what an HTMX request is about to replace, so the swap can be detected.

   `selector` names the element HTMX targets. When the swap replaces its
   contents the mark goes on its first child; when it replaces the element
   itself the mark goes on the element. Marking both covers either swap style.

   Returns how many elements were marked, which is what `await-swap!` waits to
   drop below. Returning it rather than assuming two matters: a target with no
   children marks one, and a wait hardcoded to \"fewer than two\" would be
   satisfied before the request had even been sent.

   Throws when there is nothing to mark: waiting for the replacement of an
   element that was never there passes by never having looked."
  [pg selector]
  (let [marked (page/evaluate
                pg
                (str "(() => {"
                     "  const el = document.querySelector('" selector "');"
                     "  if (!el) return 0;"
                     "  el.setAttribute('" stale-marker "', '1');"
                     "  if (!el.firstElementChild) return 1;"
                     "  el.firstElementChild.setAttribute('" stale-marker "', '1');"
                     "  return 2;"
                     "})()"))
        marked (long (or marked 0))]
    (when (zero? marked)
      (throw (ex-info (str "Nothing matched " selector " to wait on")
                      {:type :wagoe.e2e/nothing-to-wait-for :selector selector})))
    marked))

(defn- await-swap!
  "Block until something marked by `mark-stale!` has been replaced.

   `wait-for-function` answers with an anomaly map rather than throwing, so the
   result is checked. On success it hands back a JSHandle, so anything map-like
   is the failure — keyed on the shape rather than on the anomaly's namespaced
   `:category`, which a first version of this guessed wrong (`cognitect.` for
   `com.blockether.`) and so never fired at all.

   The listener this replaced had the same hole from the other side: a
   ten-second promise resolving to `false`, which nobody read. A test that
   cannot tell \"the swap did not happen\" from \"the swap happened and the
   answer was empty\" is the shape both admin search tests failed in —
   intermittently, which is worse (BOU-297)."
  [pg marked selector]
  (let [result (page/wait-for-function
                pg
                ;; The swap only has to clear one of the marks: an innerHTML
                ;; swap keeps the target and drops its children, an outerHTML
                ;; swap does the reverse.
                (str "document.querySelectorAll('[" stale-marker "]').length < " marked)
                {:timeout 10000.0 :polling 50.0})]
    (when (map? result)
      (throw (ex-info (str "HTMX never replaced " selector)
                      {:type      :wagoe.e2e/htmx-swap-timeout
                       :selector  selector
                       :waited-ms 10000
                       :anomaly   result})))
    true))

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
  "Fill the search input, trigger the HTMX request, and wait for the table to
   be replaced.

   The button rather than the input, because the input is
   `hx-trigger=\"keyup changed delay:300ms\"` and waiting out a debounce is
   waiting on a clock. The wait is for the swap itself: both `search!` calls in
   a test hit the same container, and asserting on the second before its swap
   landed read the first search's rows."
  [pg query]
  (let [input (page/locator pg "input.search-input")]
    (loc/fill input query)
    (let [marked (mark-stale! pg "#entity-table-container")]
      (loc/click (page/locator pg ".toolbar-search button[aria-label]"))
      (await-swap! pg marked "#entity-table-container"))))

(defn submit-entity-form!
  "Click an admin entity form's submit button and wait for it to re-render.

   The three callers of this did the same thing with a one-shot
   `htmx:afterSettle` listener whose ten-second timeout resolved to `false` and
   was never read, so a submit that never landed asserted against the form as
   it was before (BOU-297)."
  [pg]
  (let [marked (mark-stale! pg "form.entity-form")]
    (loc/click (page/locator pg "form.entity-form button[type='submit']"))
    (await-swap! pg marked "form.entity-form")
    (page/wait-for-load-state pg)))

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
