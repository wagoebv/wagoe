(ns wagoe.workflow.shell.module-wiring-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.admin.ports :as admin-ports]
            [wagoe.workflow.shell.module-wiring :as wiring]))

(deftest ^:unit the-admin-port-is-built-only-when-the-admin-is-on
  ;; BOU-563: the admin shows an entity's workflow state and removes its
  ;; instances on delete, through a component this module contributes.
  (testing "admin on"
    (let [c (:components (wiring/ig-config {} {:enabled #{:wagoe/workflow :wagoe/admin}}))]
      (is (= {:workflow (ig/ref :wagoe/workflow)} (:wagoe/workflow-admin c)))))
  (testing "admin off"
    (is (not (contains? (:components (wiring/ig-config {} {:enabled #{:wagoe/workflow}}))
                        :wagoe/workflow-admin))))
  (testing "the component satisfies the admin's port"
    (is (satisfies? admin-ports/IEntityWorkflows
                    (ig/init-key :wagoe/workflow-admin {:workflow {:store nil}})))))
