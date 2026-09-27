(ns wagoe.user.shell.http-error-body-test
  "An untyped exception in a JSON handler must not reach the client (BOU-557)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.http.reitit-router :as reitit]
            [wagoe.user.shell.http :as sut]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.middleware :as user-mw]))

(def ^:private secret "jdbc:postgresql://db.internal:5432/app?password=hunter2")

(defn- serve
  "POST /mfa/setup through the platform router, with setup-mfa doing `f`."
  [f]
  (with-redefs [mfa/setup-mfa (fn [_ _] (f))]
    (let [handler (reitit/compile-routes
                   [["/mfa/setup" {:post {:handler (sut/mfa-setup-handler ::mfa-service)}}]]
                   ;; Production: dev answers a missing-:type diagnostic instead.
                   {:swagger-enabled false
                    :system          {:environment "production"}})
          resp    (handler {:request-method :post
                            :uri            "/mfa/setup"
                            :headers        {}
                            :user           {:id (random-uuid)}})
          body    (:body resp)]
      {:status (:status resp)
       :raw    (if (string? body) body (slurp body))})))

(deftest ^:unit untyped-exception-is-a-generic-500
  (let [{:keys [status raw]} (serve #(throw (RuntimeException. secret)))
        body                 (json/parse-string raw true)]
    (is (= 500 status))
    (is (not (str/includes? raw "hunter2")) raw)
    (testing "the platform's error shape"
      (is (= "internal-error" (:error body)))
      (is (= "Internal Server Error" (:message body)))
      (is (contains? body :correlation-id)))))

(deftest ^:unit typed-validation-error-keeps-its-message
  (let [{:keys [status raw]} (serve #(throw (ex-info "Secret must be base32"
                                                     {:type :validation-error})))
        body                 (json/parse-string raw true)]
    (is (= 400 status))
    (is (= "validation-error" (:error body)))
    (is (= "Secret must be base32" (:message body)))))

(deftest ^:unit the-auth-middleware-401-is-json
  ;; A hardcoded Content-Type made muuntaja skip the map, so the body was
  ;; never encoded (BOU-561).
  (let [handler (reitit/compile-routes
                 [["/thing" {:middleware [(user-mw/flexible-authentication-middleware ::no-service)]
                             :get        {:handler (constantly {:status 200 :body {}})}}]]
                 {:swagger-enabled false})
        resp    (handler {:request-method :get :uri "/thing" :headers {}})
        body    (:body resp)]
    (is (= 401 (:status resp)))
    (is (= "Authentication required"
           (:message (json/parse-string (if (string? body) body (slurp body)) true))))))

(deftest ^:unit the-role-middleware-403-is-json
  (let [handler (reitit/compile-routes
                 [["/thing" {:middleware [(partial user-mw/require-role-middleware #{:admin})]
                             :get        {:handler (constantly {:status 200 :body {}})}}]]
                 {:swagger-enabled false})
        resp    (handler {:request-method :get :uri "/thing" :headers {}
                          :user {:id (random-uuid) :role :user}})
        body    (:body resp)]
    (is (= 403 (:status resp)))
    (is (= "forbidden"
           (:error (json/parse-string (if (string? body) body (slurp body)) true))))))
