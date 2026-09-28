(ns wagoe.platform.core.http.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.http.errors :as sut]))

(deftest ^:unit the-body-is-one-shape
  (is (= {:error {:type "not-found" :message "No such user"}}
         (sut/body :not-found "No such user")))
  (testing "details and correlation id sit inside :error, empty details are left out"
    (is (= {:error {:type "validation-error" :message "Bad" :details {:email ["invalid"]}
                    :correlation-id "c-1"}}
           (sut/body :validation-error "Bad" {:details {:email ["invalid"]} :correlation-id "c-1"})))
    (is (= {:error {:type "conflict" :message "Taken"}}
           (sut/body :conflict "Taken" {:details {}}))))
  (testing "a qualified type keeps its namespace; a missing message gets one"
    (is (= "billing/declined" (get-in (sut/body :billing/declined nil) [:error :type])))
    (is (string? (get-in (sut/body :x nil) [:error :message])))))

(deftest ^:unit the-response-echoes-the-correlation-id
  (let [r (sut/response 404 :not-found "Gone" {:correlation-id "c-2"})]
    (is (= 404 (:status r)))
    (is (= "c-2" (get-in r [:headers "X-Correlation-ID"])))
    (is (nil? (get-in r [:headers "Content-Type"])) "muuntaja must encode it")))

(deftest ^:unit error-body?-judges-decoded-json-too
  (is (sut/error-body? {"error" {"type" "not-found" "message" "Gone"}}))
  (is (sut/error-body? (sut/body :not-found "Gone")))
  (is (not (sut/error-body? {:error "unauthorized" :message "Authentication required"})))
  (is (not (sut/error-body? {:error {:type :not-found :message "keyword type"}}))))

(deftest ^:unit a-status-names-a-type
  (is (= :not-found (sut/status->type 404)))
  (is (= :internal-error (sut/status->type 502)))
  (testing "a 4xx without a word of its own is the caller's input refused"
    (is (= :validation-error (sut/status->type 413)))))
