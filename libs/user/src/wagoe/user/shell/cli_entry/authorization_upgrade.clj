(ns wagoe.user.shell.cli-entry.authorization-upgrade
  "The JVM half of `wagoe upgrade authorization`: the steps that need the
   app's database and routes. The Babashka half edits config.edn and calls

     clojure -M -m wagoe.user.shell.cli-entry.authorization-upgrade <app>.system-config <step>

   - `check`: what the first start of this version will move, who holds which
     legacy role, the routes no rule covers. Builds the routes without the
     rights migration or boot migrations, so it changes no data.
   - `prepare`: runs the migrations and the rights migration, then prints what
     each user holds and where `:enforce` differs from `:off`.
   - `report`: those differences again. Exits 2 while any would refuse."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [wagoe.core.utils.text :as text]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.ports.authorization :as authz]
            [wagoe.platform.shell.authorization :as authz-shell]
            [wagoe.platform.shell.system.authorization-report :as authz-report]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.user.core.authorization-upgrade :as core]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-service :as group-service]))

(def ^:dynamic *exit!*
  "Ends the process with `code`; rebound in tests."
  (fn [code] (shutdown-agents) (System/exit code)))

(defn- quiet-logging!
  "Every logback logger to WARN, so the boot's INFO and DEBUG lines do not bury
   the report. By reflection: logback is the app's choice, not a dependency."
  []
  (try
    (let [warn (.get (.getField (Class/forName "ch.qos.logback.classic.Level") "WARN") nil)
          ctx  (clojure.lang.Reflector/invokeStaticMethod "org.slf4j.LoggerFactory" "getILoggerFactory"
                                                          (object-array 0))]
      (doseq [logger (clojure.lang.Reflector/invokeInstanceMethod ctx "getLoggerList" (object-array 0))]
        (clojure.lang.Reflector/invokeInstanceMethod logger "setLevel" (object-array [warn]))))
    (catch Exception _ nil)))

(defn- app-ig-config
  "The Integrant config of the app whose system config namespace is `config-ns`.
   Its `load-config`, or wagoe.config's when it has none."
  [config-ns]
  (let [load-config (or (requiring-resolve (symbol config-ns "load-config"))
                        (requiring-resolve 'wagoe.config/load-config))]
    ((requiring-resolve (symbol config-ns "ig-config")) (load-config))))

(defn- boot
  "The part of the system the routes need. `read-only?` skips the rights
   migration and `:migrate-on-start?`."
  [ig-config read-only?]
  (let [cfg (cond-> ig-config
              read-only? (-> (assoc-in [:wagoe/user-group-service :migrate?] false)
                             (update :wagoe/db-context dissoc :migrate-on-start?))
              ;; The logging component applies its level to logback at init.
              (and (contains? ig-config :wagoe/logging) (not (System/getenv "WAGOE_DEBUG")))
              (assoc-in [:wagoe/logging :level] :warn))]
    (try (ig/init cfg (filterv #(contains? cfg %) [:wagoe/user-db-schema :wagoe/http-handler]))
         (catch clojure.lang.ExceptionInfo e
           (some-> (:system (ex-data e)) ig/halt!)
           (throw e)))))

(defn- all-users [repo]
  (loop [offset 0, acc []]
    (let [{:keys [users total-count]} (ports/find-users repo {:limit 100 :offset offset})
          acc (into acc users)]
      (if (and (seq users) (< (count acc) total-count))
        (recur (+ offset (count users)) acc)
        acc))))

(defn users-with-rights
  "Every user of `user-repo` as `core.authorization-upgrade` reads one, with
   the rights `group-svc` gives them now."
  [registry group-svc user-repo]
  (for [user (all-users user-repo)]
    (assoc (select-keys user [:id :email :role :tenant-id])
           :platform-admin? (access/platform-admin? user)
           :rights (authz-shell/rights-for registry group-svc user any?))))

(defn- facts
  "What the steps read from a booted `system`."
  [system]
  (let [registry  (:wagoe/permission-registry system)
        group-svc (:wagoe/user-group-service system)
        {:keys [mode report]} (authz-report/handler-routes (:wagoe/http-handler system))]
    {:mode      (or mode :off)
     :report    report
     :unruled   (filterv #(= :none (:rule %)) report)
     :admin     (sort (filter #(some-> (namespace %) (str/starts-with? "admin.")) (keys (authz/permissions registry))))
     :group-svc group-svc
     :users     (vec (users-with-rights registry group-svc (:wagoe/user-repository system)))}))

(defn- section [title lines]
  (println)
  (println title)
  (run! println lines))

(defn- print-unruled [unruled]
  (section "Routes no rule covers (:enforce refuses them to all but a platform admin):"
           (if (seq unruled)
             (for [{:keys [path method]} (sort-by (juxt :path :method) unruled)]
               (str "  " (text/upper-case (name method)) " " path))
             ["  None."])))

(defn- findings [{:keys [report admin users]}]
  (concat (core/route-findings report users) (core/admin-findings admin users)))

(defn- print-findings [fs]
  (section "Where :enforce differs from :off (sign-in only, the admin for role :admin):"
           (core/format-findings fs)))

(defn- group-names [group-svc]
  (into {} (map (juxt :id :name)) (ports/list-groups group-svc)))

(defn check [system]
  (let [{:keys [mode group-svc users unruled]} (facts system)
        plan (group-service/migration-plan group-svc)]
    (section "Database:"
             [(let [pending (migrations/pending-list)]
                (str "  Pending migrations: " (if (seq pending) (str/join ", " pending) "none")))
              (str "  :wagoe/authorization mode: " mode)])
    (section "Users by stored role:" (if (seq users) (core/legacy-roles users (:viewers plan)) ["  None."]))
    (section "The first start of this version (prepare runs it):"
             (core/format-plan plan (group-names group-svc)))
    (print-unruled unruled)
    0))

(defn- group-lines [group-svc]
  (for [{:keys [id name member-count]} (sort-by :name (ports/list-groups group-svc))
        :let [roles (:roles (ports/get-group group-svc id))]]
    (str "  " name (if (group-core/everyone? id) " (every user)" (str " (" (or member-count 0) " member(s))")) ": "
         (if (seq roles)
           (str/join ", " (map #(str (core/permission-str (:role %))
                                     (when (seq (:scope %)) (str " " (pr-str (:scope %)))))
                               roles))
           "no roles"))))

(defn prepare [system]
  (let [{:keys [group-svc users unruled] :as f} (facts system)]
    (section "Groups and their roles:" (group-lines group-svc))
    (section "What each user holds:" (if (seq users) (core/format-rights users) ["  No users."]))
    (print-unruled unruled)
    (print-findings (findings f))
    0))

(defn report [system]
  (let [{:keys [group-svc unruled] :as f} (facts system)
        plan (group-service/migration-plan group-svc)
        fs   (findings f)]
    (when (or (seq (:grants plan)) (seq (:viewers plan)) (:everyone-roles plan))
      (section "Rights are not moved yet; run prepare first. Until then this overstates the differences:"
               (core/format-plan plan (group-names group-svc))))
    (print-unruled unruled)
    (print-findings fs)
    (if (or (seq unruled) (core/blocking? fs)) 2 0)))

(def ^:private steps {"check" [check true] "prepare" [prepare false] "report" [report true]})

(defn run-step
  "Runs `step` against the app config `ig-config`; the exit code."
  [ig-config step]
  (let [[f read-only?] (steps step)]
    (if-not (contains? ig-config :wagoe/user-group-service)
      (do (println "The user module is off, so there are no users or groups to move.") 0)
      (do (when (= "prepare" step)
            (println "Running migrations…")
            (migrations/migrate))
          (let [system (boot ig-config read-only?)]
            (try (f system)
                 (finally (ig/halt! system))))))))

(defn -main [& [config-ns step]]
  (when-not (System/getenv "WAGOE_DEBUG") (quiet-logging!))
  (*exit!*
   (if-not (and config-ns (steps step))
     (do (println "Usage: clojure -M -m wagoe.user.shell.cli-entry.authorization-upgrade <app>.system-config check|prepare|report")
         1)
     (try
       (run-step (app-ig-config config-ns) step)
       (catch Exception e
         (println (str "Failed: " (ex-message e)
                       (when-let [t (:type (ex-data e))] (str " (:type " t ")"))))
         (doseq [c (rest (take-while some? (iterate ex-cause e)))]
           (println (str "  caused by: " (ex-message c))))
         1)))))
