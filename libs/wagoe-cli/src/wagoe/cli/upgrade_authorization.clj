(ns wagoe.cli.upgrade-authorization
  "`wagoe upgrade authorization <step>`: walk an app from
   `:wagoe/authorization :off` to `:enforce`, one environment at a time.

   This side reads and edits resources/conf/<env>/config.edn. What needs the
   database or the compiled routes runs in the app's JVM, through
   wagoe.user.shell.cli-entry.authorization-upgrade."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [wagoe.cli.catalogue :as cat]
            [wagoe.cli.config-edn :as config-edn]
            [wagoe.cli.upgrade :as upgrade]))

;; ─── config.edn ──────────────────────────────────────────────────────────────

(defn- loc-at [text path]
  (some-> (z/of-string text) (upgrade/path-loc path)))

(defn- value [loc]
  (try (z/sexpr loc) (catch Exception _ ::unreadable)))

(def ^:private sections [[:active] [] [:inactive]])

(defn- require-role-locs [text]
  (keep #(some-> (loc-at text (conj % :wagoe/admin)) (upgrade/key-loc :require-role)) sections))

(defn config-facts
  "{:mode :require-role? :default-roles [[provider role]]} of a config.edn.
   `:mode` is :off when `:wagoe/authorization` is absent."
  [text]
  (let [mode (some-> (loc-at text [:active :wagoe/authorization :mode]) value)]
    {:mode           (or mode :off)
     :require-role?  (boolean (seq (require-role-locs text)))
     :default-roles  (when-let [providers (loc-at text [:active :wagoe/user :oidc :providers])]
                       (when (= :map (z/tag providers))
                         (for [id-loc (take-nth 2 (take-while some? (iterate z/right (z/down providers))))
                               :let [role (some-> id-loc z/right (upgrade/key-loc :default-role) z/right value)]
                               :when role]
                           [(value id-loc) role])))}))

(defn drop-require-role
  "`text` without `:require-role` in `:wagoe/admin`, which nothing reads."
  [text]
  (if-let [k (first (require-role-locs text))]
    (recur (-> k z/right z/remove z/remove z/root-string))
    text))

(defn set-mode
  "`text` with `:wagoe/authorization :mode` set to `mode` under `:active`."
  [text mode]
  (let [mode-loc (loc-at text [:active :wagoe/authorization :mode])
        authz    (loc-at text [:active :wagoe/authorization])]
    (cond
      mode-loc                          (z/root-string (z/replace mode-loc mode))
      (and authz (= :map (z/tag authz))) (z/root-string (z/assoc authz :mode mode))
      :else (config-edn/append-entry
             text ":active"
             (str "  ;; :report logs what :enforce would refuse, :enforce refuses it. Set by\n"
                  "  ;; `bb upgrade authorization`; the Authorization guide has the rest.\n"
                  "  :wagoe/authorization\n  {:mode " mode "}")))))

;; ─── The project ─────────────────────────────────────────────────────────────

(defn- read-deps [dir]
  (try (edn/read-string (slurp (io/file dir "deps.edn"))) (catch Exception _ nil)))

(defn config-ns
  "The app's system config namespace: its `:run` alias's main namespace with
   `system-config` for `main`, else the one src/<dir>/system_config.clj holds."
  [dir deps]
  (let [main (second (drop-while #(not= "-m" %) (get-in deps [:aliases :run :main-opts])))]
    (or (some-> main (str/replace #"\.main$" ".system-config"))
        (some->> (.listFiles (io/file dir "src"))
                 (filter #(.isFile (io/file % "system_config.clj")))
                 first
                 (#(str (str/replace (.getName ^java.io.File %) "_" "-") ".system-config"))))))

(defn- version-parts [v]
  (mapv parse-long (re-seq #"\d+" (first (str/split v #"-")))))

(defn version-note
  "Why the app's wagoe-user may be too old for these steps, or nil."
  [deps]
  (let [have  (get-in deps [:deps 'com.wagoe/wagoe-user :mvn/version])
        known (:version (cat/find-module "user"))]
    (when (and have known (neg? (compare (version-parts have) (version-parts known))))
      (str "deps.edn has wagoe-user " have " and this CLI ships " known
           ". Bump every com.wagoe/* in deps.edn first: the steps below run in the app."))))

(defn- dotenv
  "What `dir`/.env assigns that the environment does not."
  [dir]
  (let [f (io/file dir ".env")]
    (if-not (.isFile f)
      {}
      (into {} (for [line (str/split-lines (slurp f))
                     :let [[_ k v] (re-matches #"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)" line)]
                     :when (and k (nil? (System/getenv k)))]
                 [k (str/replace (str/trim v) #"^([\"'])(.*)\1$" "$2")])))))

(def jvm-main "wagoe.user.shell.cli-entry.authorization-upgrade")

(defn- run-jvm
  "Runs `step` in the app's JVM, output to the terminal; its exit code."
  [{:keys [dir env config-ns]} step]
  (let [pb (ProcessBuilder. ^java.util.List
            ["clojure" "-J-Duser.timezone=UTC" "-J--enable-native-access=ALL-UNNAMED"
             "-M" "-m" jvm-main config-ns step])]
    (.directory pb (io/file dir))
    (.inheritIO pb)
    (.putAll (.environment pb) (assoc (dotenv dir) "WAG_ENV" env))
    (.waitFor (.start pb))))

(def ^:dynamic *jvm*
  "Runs a JVM step, (fn [ctx step]) → exit code; rebound in tests."
  run-jvm)

(defn- jvm! [ctx step]
  (let [code (*jvm* ctx step)]
    (when (= 1 code)
      (println)
      (println (str "The " step " step failed in the app (above). If it cannot locate " jvm-main
                    ", bump com.wagoe/* in deps.edn.")))
    code))

;; ─── The :report log ─────────────────────────────────────────────────────────

(def ^:private refusal-line "Authorization would refuse this request under :enforce")

(defn- log-value
  "A value as the log prints it: a map logged with print, not pr, so a string
   has no quotes and a route cannot be read back as EDN."
  [v]
  (cond
    (= "nil" v)                 nil
    (str/starts-with? v "#uuid") (second (re-find #"\"([^\"]+)\"" v))
    (str/starts-with? v ":")     (keyword (subs v 1))
    :else                        v))

(defn log-refusals
  "The entries of `text`'s would-be refusal lines, {:user-id :route :method
   :permission} as strings and keywords."
  [text]
  (for [line  (str/split-lines text)
        :let  [at   (str/index-of line refusal-line)
               body (when at (second (re-find #"\{(.*)\}" (subs line at))))]
        :when body]
    (into {} (for [pair (str/split body #", (?=:[a-z-]+ )")
                   :let [[_ k v] (re-matches #":([a-z-]+) (.*)" pair)]
                   :when k]
               [(keyword k) (log-value v)]))))

(defn format-log-refusals
  "`entries` per permission and route, with the users refused."
  [entries]
  (if (empty? entries)
    ["  None."]
    (for [[[permission method route] es] (sort-by (comp (partial mapv str) key)
                                                  (group-by (juxt :permission :method :route) entries))
          :let [users (sort (distinct (map (comp str :user-id) es)))]]
      (str "  " (if permission (subs (str permission) 1) "(no rule)") "  "
           (.toUpperCase (name (or method :any)) java.util.Locale/ROOT) " " route "  "
           (count users) " user(s): " (str/join ", " (take 5 users))
           (when (> (count users) 5) (str " and " (- (count users) 5) " more"))))))

;; ─── Steps ───────────────────────────────────────────────────────────────────

(def ^:private verify
  {"check"   ["Verify: nothing has changed. /web/admin/groups shows the groups as they are now."]
   "prepare" ["Verify: start the app and sign in as a platform admin. A user's Rights tab"
              "(/web/users/<id>/rights) shows each permission and the group and role it comes from;"
              "/web/admin/permissions/view?name=<permission> shows who holds one. Sign in as a"
              "test user of each kind and use the pages they use: under :report nothing is refused."]
   "report"  ["Verify: run under :report through a normal week, then pass the log with --log <file>."
              "Grant what the lines name (a role on a group at /web/admin/groups), or give the route"
              "a rule, until no line is left."]
   "enforce" ["Verify: restart and sign in as a test user who is no admin; their usual pages load."
              "A refusal logs 'Authorization refused a request' with the user, route and permission."]})

(defn- print-log-refusals [log entries]
  (println)
  (println (str "Would-be refusals in " log ":"))
  (run! println (format-log-refusals entries)))

(defn- print-verify [step]
  (println)
  (run! println (verify step)))

(defn- header [step env path]
  (println (str "Authorization upgrade: " step " (WAG_ENV=" env ", " path ")")))

(defn- project-lines [deps {:keys [mode require-role? default-roles]}]
  (concat
   (when-let [n (version-note deps)] [(str "  " n)])
   [(str "  :wagoe/authorization mode in config.edn: " mode)]
   (when require-role? ["  :require-role in :wagoe/admin: nothing reads it; prepare deletes it."])
   (for [[id role] default-roles]
     (str "  OIDC provider " id " :default-role " role ": deprecated. Delete it, and put those users"
          " in a group, by :group-membership or by hand."))))

(defn- show-diff [dir path old new]
  (if (= old new)
    (println (str "  " path ": nothing to change."))
    (println (upgrade/unified-diff (upgrade/relative dir path) old new))))

(defn- step-check [{:keys [deps text] :as ctx}]
  (println)
  (println "Project:")
  (run! println (project-lines deps (config-facts text)))
  (let [code (jvm! ctx "check")]
    (println)
    (println "What the steps change:")
    (println "  prepare  deletes :require-role, sets :mode :report if it is :off, runs the migrations,")
    (println "           and moves the rights as shown above.")
    (println "  report   changes nothing: lists where :enforce would refuse what :off allowed.")
    (println "  enforce  sets :mode :enforce, and refuses while a route has no rule or a user would be refused.")
    code))

(defn- step-prepare [{:keys [dir path text dry-run?] :as ctx}]
  (let [{:keys [mode]} (config-facts text)
        new (cond-> (drop-require-role text)
              (= :off mode) (set-mode :report))]
    (println)
    (show-diff dir path text new)
    (if dry-run?
      (do (println)
          (println "Dry run: config.edn is not written, and nothing runs against the database. Its state:")
          (jvm! ctx "check"))
      (do (when (not= text new) (spit path new) (println (str "Wrote " (upgrade/relative dir path))))
          (jvm! ctx "prepare")))))

(defn- step-report [{:keys [log] :as ctx}]
  (println)
  (println "Under :report each request :enforce would refuse is logged as a warning:")
  (println (str "  " refusal-line " {:user-id … :route … :method … :permission … :reason …}"))
  (println "Repeats of one user, route and permission are logged once a minute, not every time.")
  (let [code (jvm! ctx "report")]
    (if log
      (print-log-refusals log (log-refusals (slurp log)))
      (do (println)
          (println "Pass the app's log with --log <file> to summarise the lines it holds.")))
    code))

(defn- step-enforce [{:keys [dir path text dry-run? force? log] :as ctx}]
  (let [{:keys [mode]} (config-facts text)]
    (cond
      (= :enforce mode)
      (do (println) (println "Already :enforce; nothing to do.") 0)

      (and (= :off mode) (not force?))
      (do (println) (println "The mode is :off. Run prepare first, and run the app under :report.") 1)

      :else
      (let [code    (jvm! ctx "report")
            logged  (when log (log-refusals (slurp log)))
            blocked (or (= 2 code) (seq logged))]
        (when (seq logged) (print-log-refusals log logged))
        (cond
          (= 1 code) 1

          (and blocked (not force?))
          (do (println)
              (println "Not switched: routes without a rule, or users :enforce would refuse (above).")
              (println "Fix them, or pass --force to switch anyway.")
              2)

          :else
          (let [new (set-mode text :enforce)]
            (println)
            (show-diff dir path text new)
            (if dry-run?
              (do (println) (println "Dry run: config.edn is not written.") 0)
              (do (spit path new)
                  (println (str "Wrote " (upgrade/relative dir path) ". Restart the app to enforce."))
                  (println)
                  (println "To roll back, set :wagoe/authorization {:mode :report} in that file and restart.")
                  (println "Nothing else changes: rights stay as they are.")
                  0))))))))

(def ^:private steps
  {"check" step-check "prepare" step-prepare "report" step-report "enforce" step-enforce})

(def usage
  ["Usage: wagoe upgrade authorization <step> [--env ENV] [--dry-run] [--force] [--log FILE]"
   ""
   "Moves this app from :wagoe/authorization :off to :enforce, for one environment"
   "(--env, default $WAG_ENV or dev). Run the steps in order; each can be run again."
   "  check    what the app has and what each step changes; writes nothing"
   "  prepare  delete :require-role, set :mode :report, run migrations, move rights"
   "  report   where :enforce would refuse what :off allows; --log FILE summarises a :report log"
   "  enforce  set :mode :enforce; refused while anything would be refused, unless --force"
   "  --dry-run  show what prepare or enforce would write, and write nothing"])

(defn- parse-args [args]
  (loop [[a & more] args, opts {}]
    (case a
      nil         opts
      ("--help" "-h") (recur more (assoc opts :help? true))
      "--dry-run" (recur more (assoc opts :dry-run? true))
      "--force"   (recur more (assoc opts :force? true))
      "--env"     (recur (rest more) (assoc opts :env (first more)))
      "--log"     (recur (rest more) (assoc opts :log (first more)))
      (if (and (nil? (:step opts)) (not (str/starts-with? a "-")))
        (recur more (assoc opts :step a))
        (assoc opts :error (str "Unknown argument: " a))))))

(defn run
  "Runs the step `args` name in project `dir`; the exit code."
  [dir args]
  (let [{:keys [step error env log help?] :as opts} (parse-args args)
        env  (or env (System/getenv "WAG_ENV") "dev")
        path (io/file dir "resources" "conf" env "config.edn")
        deps (read-deps dir)
        app  (config-ns dir deps)]
    (cond
      help?
      (do (run! println usage) 0)

      (or error (not (steps step)))
      (do (when error (println error)) (run! println usage) 1)

      (not (.isFile path))
      (do (println (str "No resources/conf/" env "/config.edn; pass --env.")) 1)

      (and log (not (.isFile (io/file log))))
      (do (println (str "No such log file: " log)) 1)

      (nil? app)
      (do (println "Cannot find the app's system config namespace (deps.edn :run alias).") 1)

      :else
      (let [text (slurp path)]
        (header step env (upgrade/relative dir path))
        (let [code ((steps step) (assoc opts :dir dir :env env :path path :text text
                                        :deps deps :config-ns app))]
          (when (or (zero? code) (#{"check" "report"} step))
            (print-verify (if (:dry-run? opts) "check" step)))
          code)))))
