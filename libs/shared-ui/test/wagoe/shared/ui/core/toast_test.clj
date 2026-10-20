(ns wagoe.shared.ui.core.toast-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.toast :as toast]))

(defn- html [hiccup] (str (h/html hiccup)))

(deftest ^:unit a-toast-has-its-type-and-dismisses-itself
  (let [out (html (toast/toast {:type :success :message "Saved"}))]
    (is (str/includes? out "class=\"alert alert-success shadow-lg\""))
    (is (str/includes? out "x-data=\"toast(5000)\""))
    (is (str/includes? out "<span>Saved</span>"))
    (is (str/includes? out "aria-label=\"Dismiss\""))
    (is (str/includes? out "@click=\"dismiss()\""))
    (is (not (str/includes? out "role="))))
  (testing "info by default"
    (is (str/includes? (html (toast/toast {:message "x"})) "alert-info shadow-lg\" x-data=\"toast(5000)\"")))
  (testing "an error interrupts and stays until closed"
    (let [out (html (toast/toast {:type "error" :message "No"}))]
      (is (str/includes? out "alert-error"))
      (is (str/includes? out "role=\"alert\""))
      (is (str/includes? out "toast(0)"))))
  (testing "a keyword message is a catalogue key"
    (is (some #{[:span [:t :user/group-saved]]}
              (toast/toast {:message :user/group-saved}))))
  (testing "the message is escaped"
    (is (str/includes? (html (toast/toast {:message "<b>"})) "&lt;b&gt;"))))

(deftest ^:unit flash-toasts-wait-in-a-template-in-either-flash-shape
  (is (nil? (toast/flash-toasts nil)))
  (is (= [:template {:data-toast-flash ""} (toast/toast {:type :success :message "a"})]
         (toast/flash-toasts {:type :success :message "a"})))
  (is (= [:template {:data-toast-flash ""}
          (toast/toast {:type :error :message "b"}) (toast/toast {:type :info :message "c"})]
         (toast/flash-toasts {:error "b" :info "c"}))))

(deftest ^:unit the-region-is-polite-and-survives-a-body-swap
  (let [out (html (toast/toast-region))]
    (is (str/includes? out "id=\"toasts\""))
    (is (str/includes? out "aria-live=\"polite\""))
    (is (str/includes? out "hx-preserve=\"true\""))
    (is (str/includes? out "class=\"toast toast-top toast-end"))
    (testing "and an empty toast of each type, for the client to fill in"
      (is (str/includes? out "<template data-toast-type=\"error\"><div class=\"alert alert-error shadow-lg\""))
      (is (= 4 (count (re-seq #"<template" out)))))))

(deftest ^:unit every-page-layout-has-the-region-and-its-flash
  (let [find (fn [page pred] (some #(when (and (vector? %) (map? (second %)) (pred %)) %) (tree-seq vector? seq page)))
        region? #(= "toasts" (:id (second %)))
        flash?  #(contains? (second %) :data-toast-flash)]
    (is (= (toast/toast-region) (find (layout/page-layout "t" [:p "x"]) region?)))
    (is (nil? (find (layout/page-layout "t" [:p "x"]) flash?)))
    (is (= (toast/flash-toasts {:success "Done"})
           (find (layout/page-layout "t" [:p "x"] {:flash {:success "Done"}}) flash?)))))

(deftest ^:unit with-toast-appends-an-out-of-band-toast
  (let [resp (toast/with-toast {:status 200 :body "<tr>row</tr>"} {:type :warning :message "Careful"} html)]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (:body resp) "<tr>row</tr><div hx-swap-oob=\"beforeend:#toasts\"><div class=\"alert alert-warning")))
  (testing "an empty body"
    (is (= "<div>t</div>" (:body (toast/with-toast {} {:message "m"} (constantly "<div>t</div>")))))))

(deftest ^:unit header-toasts-are-ascii-json
  (testing "a redirect carries its toast in X-Toast"
    (is (= {:status  200
            :headers {"HX-Redirect" "/web/users"
                      "X-Toast"     "{\"type\":\"success\",\"message\":\"Verwijderd \\\"\\u00E9\\\"\"}"}
            :body    ""}
           (toast/redirect-with-toast "/web/users" :success "Verwijderd \"é\""))))
  (testing "a failed response names its toast in HX-Trigger, keeping its status"
    (is (= {:status  409
            :headers {"HX-Trigger" "{\"showToast\":{\"type\":\"error\",\"message\":\"No\"}}"}}
           (toast/with-error-toast {:status 409} "No")))))
