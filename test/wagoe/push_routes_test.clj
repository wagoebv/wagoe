(ns wagoe.push-routes-test
  "Push contributes its routes like any module: an app with `:wagoe/push` on
   serves them under /api/v1/push, without mounting anything itself."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [support.route-table :as rt]
            [wagoe.user.shell.auth :as auth]))

(use-fixtures :once (rt/system-fixture "push-routes"))

(deftest ^:integration a-signed-in-user-registers-and-lists-devices
  (let [{:keys [id email role]} (get-in (rt/decoded (rt/call :post "/test/reset" "{}"))
                                        [:seeded :user])
        headers {"authorization"
                 (str "Bearer " (auth/create-jwt-token {:id id :email email :role (keyword role)} 1))}
        device  {:token "device-token-1" :platform "fcm" :app-id "com.example"}
        created (rt/call :post "/api/v1/push/devices" (json/generate-string device) headers)
        listed  (rt/call :get "/api/v1/push/devices" "" headers)]
    (is (= 201 (:status created)) (pr-str (rt/decoded created)))
    (is (= 200 (:status listed)))
    (is (= ["device-token-1"] (map :token (:devices (rt/decoded listed)))))))

(deftest ^:integration the-callback-is-mounted-and-public
  ;; Anonymous, as a device posts it, with JSON strings for the enums: the
  ;; HMAC is checked, so the body got past validation and nobody asked who
  ;; was calling.
  (let [payload {:device-token "device-token-1" :provider-message-id "msg-1"
                 :event-type "delivered" :callback-token "not-the-hmac"
                 :notification-id "order-shipped" :platform "fcm"}]
    (is (= 403 (:status (rt/call :post "/api/v1/push/callback"
                                 (json/generate-string payload)))))))
