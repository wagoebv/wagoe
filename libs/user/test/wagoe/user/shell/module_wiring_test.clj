(ns wagoe.user.shell.module-wiring-test
  (:require [wagoe.user.shell.module-wiring :as sut]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(deftest ^:unit user-http-middleware-is-a-seq-the-handler-can-thread
  ;; A seq, like tenant's, because platform concatenates it into the pipeline.
  (testing "with a user service, one middleware"
    (let [mw (ig/init-key :wagoe/user-http-middleware {:user-service ::svc})]
      (is (= 1 (count mw)))
      (is (fn? (first mw)) "and it is a (fn [handler] ...)")))

  (testing "without one, none — rather than a middleware that cannot work"
    (is (= [] (ig/init-key :wagoe/user-http-middleware {})))
    (is (= [] (ig/init-key :wagoe/user-http-middleware {:user-service nil})))))

(deftest ^:unit user-contributes-its-authentication-middleware-to-the-handler
  ;; :auth-middleware, not :extra-middleware. Platform puts the former
  ;; outermost, ahead of the tenant middleware that reads [:user :id]; module
  ;; contributions merge by key and iterate sorted, and :wagoe/tenant sorts
  ;; before :wagoe/user, so the order could not come from module iteration
  ;; (BOU-373).
  (let [{:keys [components http]} (sut/ig-config nil {:config {} :enabled #{}})]
    (is (contains? components :wagoe/user-http-middleware))
    (is (= (ig/ref :wagoe/user-service)
           (:user-service (:wagoe/user-http-middleware components)))
        "it needs the service to validate sessions with")
    (is (= (ig/ref :wagoe/user-http-middleware) (:auth-middleware http)))
    (is (not (contains? http :extra-middleware))
        "tenant owns that key; a second contributor would silently replace it")))
