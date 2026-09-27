(ns wagoe.websocket-authentication-test
  "The documented `ws://host/ws?token=<jwt>` connection works behind the
   platform's default-deny, and is refused there without a good token
   (BOU-568)."
  (:require [clojure.test :refer [deftest is testing]]
            [ring.websocket :as ring-ws]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.realtime.shell.handlers.ring-websocket :as ws-handler]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.middleware :as user-middleware]))

(defn- handler []
  (reitit-router/compile-routes
   [["/ws" {:get {:handler (ws-handler/websocket-handler ::no-service)}}]]
   {:swagger-enabled false
    ;; JWT needs no user service; a session would, and none is sent here.
    :authentication  {:middleware [(user-middleware/authenticate-if-present nil)]}}))

(defn- upgrade [query]
  {:request-method :get
   :uri            "/ws"
   :query-string   query
   :headers        {"connection" "Upgrade" "upgrade" "websocket"}})

(def ^:private user
  {:id #uuid "00000000-0000-0000-0000-0000000000aa" :email "a@example.com" :role :user})

(deftest ^:unit ^:security a-websocket-upgrade-authenticates-with-its-token
  (let [h (handler)]
    (testing "a valid token reaches the upgrade"
      (let [resp (h (upgrade (str "token=" (auth/create-jwt-token user 1))))]
        (is (contains? resp ::ring-ws/listener) (pr-str (dissoc resp :body)))))

    (testing "no token, or a bad one, is refused before the upgrade"
      (is (= 401 (:status (h (upgrade nil)))))
      (is (= 401 (:status (h (upgrade "token=not-a-jwt"))))))

    (testing "a token in the query string authenticates nothing but an upgrade"
      (is (= 401 (:status (h (dissoc (upgrade (str "token=" (auth/create-jwt-token user 1)))
                                     :headers))))))))
