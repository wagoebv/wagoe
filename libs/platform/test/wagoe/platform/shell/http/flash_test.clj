(ns wagoe.platform.shell.http.flash-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.http.flash :as flash]))

(defn- with-cookie [value]
  {:cookies {flash/cookie-name {:value value}}})

(def ^:private echo (flash/wrap-flash (fn [req] {:status 200 :body (:flash req)})))

(deftest ^:unit response-flash-becomes-a-cookie
  (let [response ((flash/wrap-flash (fn [_] {:status 303 :flash {:success "Saved"}})) {})]
    (is (not (contains? response :flash)))
    (is (= (pr-str {:success "Saved"}) (get-in response [:cookies flash/cookie-name :value])))
    (is (true? (get-in response [:cookies flash/cookie-name :http-only])))))

(deftest ^:unit cookie-becomes-request-flash-and-is-cleared
  (let [response (echo (with-cookie (pr-str {:type :success :message "Welcome"})))]
    (is (= {:type :success :message "Welcome"} (:body response)))
    (is (= 0 (get-in response [:cookies flash/cookie-name :max-age])))))

(deftest ^:unit a-new-flash-replaces-the-one-read
  (let [handler  (flash/wrap-flash (fn [_] {:status 303 :flash {:success :user/group-saved}}))
        response (handler (with-cookie (pr-str {:success "old"})))]
    (is (= (pr-str {:success :user/group-saved})
           (get-in response [:cookies flash/cookie-name :value])))))

(deftest ^:unit only-a-flash-shape-is-read
  (testing "markup, unknown keys and unreadable values are dropped"
    (doseq [v [(pr-str {:success [:script "x"]})
               (pr-str {:mfa-backup-codes ["a"]})
               (pr-str ["not" "a" "map"])
               "{:unclosed"
               "#=(java.lang.System/exit 0)"]]
      (is (nil? (:body (echo (with-cookie v)))) v))))

(deftest ^:unit a-flash-the-next-request-would-drop-is-not-written
  (let [handler (flash/wrap-flash (fn [_] {:status 303 :flash {:success [:t :x/y {:n 1}]}}))]
    (is (nil? (:cookies (handler {}))))))

(deftest ^:unit secure-follows-the-setting
  (let [set-flash (fn [_] {:status 303 :flash {:success "Saved"}})
        secure    (fn [opts] (get-in ((flash/wrap-flash set-flash opts) {}) [:cookies flash/cookie-name :secure]))]
    (is (true? (secure {})))
    (is (false? (secure {:secure? false})))))

(deftest ^:unit no-cookie-no-flash-no-set-cookie
  (let [response (echo {})]
    (is (nil? (:body response)))
    (is (nil? (:cookies response)))))
