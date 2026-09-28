(ns wagoe.platform.shell.database.cli-migrations-test
  (:require [wagoe.platform.shell.database.cli-migrations :as sut]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.database.reset :as reset]
            [wagoe.platform.shell.adapters.database.config :as db-config]
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
                    reset/plan (fn [_] {:database "app"})
                    reset/execute! (fn [_] (swap! calls conj :reset))
                    sut/tty? (constantly true)
                    migrations/init (fn [] (swap! calls conj :init))
                    read-line (constantly "app")]
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

  (testing "reset returns non-zero when cancelled"
    ;; A cancelled destructive operation is not success for a caller. Returning
    ;; 0 let `bb db:reset` announce "Reset complete." over a reset that never
    ;; ran (BOU-500).
    (with-redefs [reset/plan (fn [_] {:database "app"})
                  reset/execute! (fn [_] (throw (ex-info "must not run" {})))
                  sut/tty? (constantly true)
                  read-line (fn [] "y")]
      (is (= 1 (sut/cmd-reset {})))))

  (testing "failing commands return one"
    (with-redefs [wagoe.platform.shell.database.migrations/migrate (fn [] (throw (ex-info "migrate boom" {})))
                  wagoe.platform.shell.database.migrations/rollback (fn [] (throw (ex-info "rollback boom" {})))
                  wagoe.platform.shell.database.migrations/print-status (fn [] (throw (ex-info "status boom" {})))
                  wagoe.platform.shell.database.migrations/create-migration (fn [_] (throw (ex-info "create boom" {})))
                  reset/plan (fn [_] {:database "app"})
                  reset/execute! (fn [_] (throw (ex-info "reset boom" {})))
                  sut/tty? (constantly true)
                  wagoe.platform.shell.database.migrations/init (fn [] (throw (ex-info "init boom" {})))
                  read-line (constantly "app")]
      (is (= 1 (sut/cmd-migrate {})))
      (is (= 1 (sut/cmd-rollback {})))
      (is (= 1 (sut/cmd-status {})))
      (is (= 1 (sut/cmd-create "broken" {})))
      (is (= 1 (sut/cmd-reset {})))
      (is (= 1 (sut/cmd-init {}))))))

(deftest ^:unit reset-refuses-outside-dev-test-and-acc
  ;; Before connecting and before asking (BOU-585).
  (doseq [env ["prod" "staging" ""]]
    (testing (pr-str env)
      (let [calls (atom [])
            out   (with-redefs [db-config/getenv {"WAG_ENV" env}
                                migrations/rollback-config (fn [] (swap! calls conj :connect) {})
                                reset/execute! (fn [_] (swap! calls conj :reset))
                                sut/tty? (constantly true)
                                read-line (fn [] (swap! calls conj :read-line) env)]
                    (with-out-str (is (= 1 (sut/cmd-reset {})))))]
        (is (= [] @calls) "neither connected, asked nor reset")
        (is (re-find #"bb migrate up" out) out)))))

(deftest ^:unit reset-asks-for-the-database-name-on-a-terminal-only
  ;; `yes | bb db:reset` answered the old prompt; no terminal now refuses,
  ;; and only the database's name confirms (BOU-585).
  (let [plan  {:env "dev" :host "localhost" :database "shop_dev" :schema "public"
               :tables ["auth_users" "schema_migrations"] :tenant-schemas ["tenant_acme"]}
        run   (fn [tty answer]
                (let [ran (atom false)
                      out (with-redefs [reset/plan (fn [_] plan)
                                        reset/execute! (fn [_] (reset! ran true))
                                        migrations/print-status (fn [])
                                        sut/tty? (constantly tty)
                                        read-line (constantly answer)]
                            (with-out-str (sut/cmd-reset {})))]
                  [@ran out]))]
    (testing "the plan is shown first"
      (let [[_ out] (run true "shop_dev")]
        (doseq [s ["localhost" "shop_dev" "public" "auth_users" "tenant_acme"]]
          (is (re-find (re-pattern s) out) s))))
    (is (not (first (run false "shop_dev"))) "no terminal")
    (is (not (first (run true "y"))))
    (is (not (first (run true "dev"))) "the profile is not the name")
    (is (first (run true "shop_dev")))))

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

(deftest ^:unit help-after-reset-prints-help-and-resets-nothing
  ;; `bb db:reset --help` reached the reset (BOU-588).
  (doseq [flag ["--help" "-h"]]
    (let [calls (atom [])
          exits (atom [])
          out   (with-redefs [reset/plan     (fn [_] (swap! calls conj :plan) {:database "app"})
                              reset/execute! (fn [_] (swap! calls conj :reset))
                              sut/tty?       (constantly true)
                              read-line      (constantly "app")
                              sut/exit!      (fn [code] (swap! exits conj code))]
                  (with-out-str (sut/-main "reset" flag)))]
      (is (= [] @calls) flag)
      (is (= [0] @exits))
      (is (re-find #"reset" out)))))

(deftest ^:unit a-reset-that-dropped-the-users-names-create-admin
  (let [run (fn [tables]
              (with-redefs [reset/plan (fn [_] {:env "dev" :host "localhost" :database "app" :tables tables})
                            reset/execute! (fn [_])
                            migrations/print-status (fn [])
                            sut/tty? (constantly true)
                            read-line (constantly "app")]
                (with-out-str (sut/cmd-reset {}))))]
    (is (re-find #"bb create-admin" (run ["user_sessions" "users" "auth_users" "schema_migrations"])))
    (is (not (re-find #"bb create-admin" (run ["schema_migrations"])))
        "no user tables, no admin to recreate")))

(deftest ^:unit create-tells-the-user-one-directory
  ;; BOU-274: the summary line printed (:directory result) while the next-steps
  ;; line hardcoded "migrations/". In a resources-backed layout those two lines
  ;; contradicted each other, and the one the user acts on — "Edit the generated
  ;; SQL files in …" — was the wrong one.
  (testing "both lines name the directory the files were written to"
    (doseq [dir ["migrations/" "resources/migrations/"]]
      (with-redefs [migrations/create-migration
                    (fn [n] {:success true
                             :message (str "Created migration files for: " n)
                             :directory dir})]
        (let [out    (with-out-str (sut/cmd-create "add-users" {}))
              quoted (java.util.regex.Pattern/quote dir)]
          (is (re-find (re-pattern (str "created in: " quoted)) out))
          (is (re-find (re-pattern (str "Edit the generated SQL files in " quoted)) out)
              (str dir ": the instruction has to point at the files that exist"))
          (when (= "resources/migrations/" dir)
            (is (not (re-find #"SQL files in migrations/" out))
                "the hardcoded project directory must not survive here")))))))
