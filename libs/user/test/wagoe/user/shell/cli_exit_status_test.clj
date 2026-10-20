(ns wagoe.user.shell.cli-exit-status-test
  "BOU-790: `user list`, `update` and `delete` answered `:status :success`, which
   the CLI took for a failure and exited 1."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.cli :as cli]))

(def ^:private id (random-uuid))

(def ^:private user
  {:id id :email "ann@example.com" :name "Ann" :role :user :active true})

(defn- service [updates]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/IUserService
    (list-users [_ _] {:users [user] :total-count 1})
    (get-user-by-id [_ _] user)
    (update-user-profile [_ entity] (swap! updates conj entity) (merge user entity))
    (delete-user [_ _] true)))

(defn- run [& args]
  (let [updates (atom [])
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        exit (binding [*out* out *err* err]
               (cli/run-cli! (service updates) (vec args)))]
    {:exit exit :out (str out) :err (str err) :updates @updates}))

(deftest ^:unit successful-commands-exit-0
  (testing "list prints the users"
    (let [{:keys [exit out]} (run "list")]
      (is (= 0 exit))
      (is (str/includes? out "ann@example.com"))))
  (testing "update"
    (is (= 0 (:exit (run "update" "--id" (str id) "--name" "Bea")))))
  (testing "delete"
    (is (= 0 (:exit (run "delete" "--id" (str id)))))))

(deftest ^:unit update-without-fields-is-refused
  (let [{:keys [exit err updates]} (run "update" "--id" (str id))]
    (is (= 1 exit))
    (is (str/includes? err "No update fields provided"))
    (is (empty? updates) "nothing is written")))
