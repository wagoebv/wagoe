(ns wagoe.user.shell.client-ip-test
  "Login, audit and session IPs come from the request's resolved `:client-ip`,
   not the proxy's `:remote-addr` or a header the caller wrote."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.interceptors :as user-interceptors]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.web-handlers :as web-handlers]))

(def ^:private behind-proxy
  {:remote-addr "10.0.0.2" :client-ip "198.51.100.7"
   :headers     {"user-agent" "test-agent" "x-forwarded-for" "6.6.6.6"}})

(deftest ^:contract ^:security login-context-uses-the-client-ip
  (let [seen (atom nil)
        svc  #_{:clj-kondo/ignore [:missing-protocol-method]}
             (reify ports/IUserService
               (authenticate-user [_ creds]
                 (reset! seen creds)
                 {:authenticated false}))]
    ((web-handlers/login-submit-handler svc {})
     (assoc behind-proxy :form-params {"email" "user@example.com" "password" "password123"}))
    (is (= "198.51.100.7" (:ip-address @seen)))))

(deftest ^:unit ^:security audit-context-uses-the-client-ip
  (is (= "198.51.100.7" (:ip-address (service/request-audit-context behind-proxy))))
  (testing "a request that did not pass the middleware falls back to the peer"
    (is (= "10.0.0.2" (:ip-address (service/request-audit-context (dissoc behind-proxy :client-ip)))))))

(deftest ^:unit ^:security api-session-ignores-a-caller-supplied-ip
  (let [ctx {:interface-type   :http
             :request          behind-proxy
             :raw-session-data {:email      "user@example.com"
                                :password   "password123"
                                :deviceInfo {:ipAddress "1.1.1.1" :userAgent "ua"}}}]
    (is (= "198.51.100.7"
           (get-in ((:enter user-interceptors/transform-session-creation-data) ctx)
                   [:session-data :ip-address])))))
