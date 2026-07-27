(ns wagoe.platform.shell.database.cli-migrations-test
  (:require [wagoe.platform.shell.database.cli-migrations :as sut]
            [wagoe.platform.shell.database.migrations :as migrations]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.cli :as cli]))

(deftest ^:unit command-functions-return-exit-codes
  (testing "successful commands delegate and return zero"
    (let [calls (atom [])]
      (with-redefs [migrations/migrate (fn [] (swap! calls conj :migrate))
                    migrations/rollback (fn [] (swap! calls conj :rollback))
                    migrations/print-status (fn [] (swap! calls conj :status))
                    migrations/create-migration (fn [name]
                                                   (swap! calls conj [:create name])
                                                   {:message (str "Created " name)
                                                    :directory "migrations/"})
                    migrations/reset (fn [] (swap! calls conj :reset))
                    migrations/init (fn [] (swap! calls conj :init))
                    read-line (fn [] "yes")]
        (is (= 0 (sut/cmd-migrate {})))
        (is (= 0 (sut/cmd-rollback {})))
        (is (= 0 (sut/cmd-status {})))
        (is (= 0 (sut/cmd-create "add-users" {})))
        (is (= 0 (sut/cmd-reset {})))
        (is (= 0 (sut/cmd-init {})))
        (is (= [:migrate :status
                :rollback :status
                :status
                [:create "add-users"]
                :reset :status
                :init]
               @calls)))))

  (testing "create rejects blank migration names"
    (is (= 1 (sut/cmd-create "   " {}))))

  (testing "reset returns zero when cancelled"
    (with-redefs [read-line (fn [] "nope")]
      (is (= 0 (sut/cmd-reset {})))))

  (testing "failing commands return one"
    (with-redefs [wagoe.platform.shell.database.migrations/migrate (fn [] (throw (ex-info "migrate boom" {})))
                  wagoe.platform.shell.database.migrations/rollback (fn [] (throw (ex-info "rollback boom" {})))
                  wagoe.platform.shell.database.migrations/print-status (fn [] (throw (ex-info "status boom" {})))
                  wagoe.platform.shell.database.migrations/create-migration (fn [_] (throw (ex-info "create boom" {})))
                  wagoe.platform.shell.database.migrations/reset (fn [] (throw (ex-info "reset boom" {})))
                  wagoe.platform.shell.database.migrations/init (fn [] (throw (ex-info "init boom" {})))
                  read-line (fn [] "yes")]
      (is (= 1 (sut/cmd-migrate {})))
      (is (= 1 (sut/cmd-rollback {})))
      (is (= 1 (sut/cmd-status {})))
      (is (= 1 (sut/cmd-create "broken" {})))
      (is (= 1 (sut/cmd-reset {})))
      (is (= 1 (sut/cmd-init {}))))))

(deftest ^:unit main-dispatches-and-exits-with-command-status
  (testing "help, missing command, parse errors, dispatch, and unknown commands set exit status"
    (let [exits (atom [])]
    (with-redefs [cli/parse-opts (fn [args _opts & _]
                                                   (case (first args)
                                                     "--help" {:options {:help true} :arguments [] :errors nil}
                                                     "missing" {:options {} :arguments [] :errors nil}
                                                     "bad" {:options {} :arguments ["migrate"] :errors ["bad flag"]}
                                                     "migrate" {:options {:verbose true} :arguments ["migrate"] :errors nil}
                                                     "create" {:options {} :arguments ["create" "add-users"] :errors nil}
                                                     "unknown" {:options {} :arguments ["wat"] :errors nil}))
                    sut/print-help (fn [] nil)
                    sut/cmd-migrate (fn [opts] (is (= {:verbose true} opts)) 7)
                    sut/cmd-create (fn [name opts] (is (= "add-users" name)) (is (= {} opts)) 9)
                    sut/exit! (fn [code] (swap! exits conj code) (throw (ex-info "exit" {:code code})))]
        (doseq [args [["--help"] ["missing"] ["bad"] ["migrate"] ["create"] ["unknown"]]]
          (try
            (apply sut/-main args)
            (catch clojure.lang.ExceptionInfo ex
              (is (= "exit" (ex-message ex))))))
        (is (= [0 1 1 7 9 1] @exits))))))
