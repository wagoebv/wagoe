(ns wagoe.e2e.html.admin-ux-wired-once-test
  "A swap of the whole body runs admin-ux.js again. Wired twice, every failed
   request would toast twice."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private run-again-then-fail-a-request
  "New toasts after admin-ux.js runs a second time and one request fails with
   no toast of its own."
  "async () => {
     const src = document.querySelector('script[src*=admin-ux]').src;
     await new Promise((ok, ko) => {
       const s = document.createElement('script');
       s.src = src + (src.includes('?') ? '&' : '?') + 'again';
       s.onload = ok;
       s.onerror = ko;
       document.body.appendChild(s);
     });
     const toasts = () => document.querySelectorAll('#toasts > .alert').length;
     const before = toasts();
     const xhr = {status: 500, getResponseHeader: () => null};
     document.body.dispatchEvent(new CustomEvent('htmx:responseError',
       {bubbles: true, detail: {elt: document.body, xhr, requestConfig: {headers: {}}}}));
     return toasts() - before;
   }")

(deftest ^:integration ^:e2e admin-ux-wires-its-listeners-once
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (is (= 1 (page/evaluate pg run-again-then-fail-a-request)))))
