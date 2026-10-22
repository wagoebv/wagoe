(ns wagoe.e2e.html.admin-theme-test
  "The admin keeps the wagoe.org palette whatever the OS prefers: the dark
   block in wagoe-tokens.css used to win when the OS was dark."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private colours
  "Resolved primary and surface, and the avatar's background and text, with
   <html data-theme> set to the argument (removed for null)."
  "(t) => {
     const h = document.documentElement;
     if (t) h.setAttribute('data-theme', t); else h.removeAttribute('data-theme');
     const probe = document.createElement('div');
     probe.style.cssText = 'background:var(--color-primary);color:var(--surface-0)';
     document.body.appendChild(probe);
     const p = getComputedStyle(probe);
     const a = getComputedStyle(document.querySelector('.user-menu-button'));
     const r = [p.backgroundColor, p.color, a.backgroundColor, a.color];
     probe.remove();
     return r;
   }")

(def ^:private dark  ["rgb(95, 211, 182)" "rgb(20, 35, 30)" "rgb(224, 165, 121)" "rgb(15, 28, 24)"])
(def ^:private light ["rgb(14, 124, 102)" "rgb(255, 255, 255)" "rgb(224, 165, 121)" "rgb(15, 28, 24)"])

(deftest ^:integration ^:e2e the-admin-palette-wins-over-the-os-colour-scheme
  (spel/with-testing-page {:color-scheme :dark} [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (doseq [[os theme expected] [[:dark nil dark]  [:dark "dark" dark]  [:dark "light" light]
                                 [:light nil light] [:light "dark" dark] [:light "light" light]]]
      (testing (str "OS " (name os) ", data-theme " (or theme "unset"))
        (page/emulate-media! pg {:color-scheme os})
        (is (= expected (page/evaluate pg colours theme)))))))
