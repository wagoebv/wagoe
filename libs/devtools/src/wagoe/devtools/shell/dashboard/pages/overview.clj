(ns wagoe.devtools.shell.dashboard.pages.overview
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [wagoe.devtools.shell.dashboard.pages.errors :as dashboard-errors]
            [wagoe.devtools.shell.repl :as devtools-repl]
            [clojure.string :as str]
            [integrant.repl.state :as state]))

(def ^:private infra-keys
  #{"db-context" "http-server" "http-handler" "router" "logging" "metrics"
    "error-reporting" "cache" "dashboard" "i18n"})

(defn- active-modules [sys]
  (when sys
    (->> (keys sys)
         (filter keyword?)
         (map name)
         (remove infra-keys)
         sort)))

(defn- system-data
  "Gather system info. Uses injected context refs when available,
   falls back to integrant.repl.state/system for supplementary data."
  [ctx]
  (let [sys      (try state/system (catch Exception _ nil))
        handler  (or (:http-handler ctx) (when sys (get sys :wagoe/http-handler)))
        db-ctx   (or (:db-context ctx) (when sys (get sys :wagoe/db-context)))
        routes   (when handler
                   (try (devtools-repl/extract-routes-from-handler handler)
                        (catch Exception _ [])))
        ;; Derive modules from routes when REPL state is unavailable
        route-modules (when routes
                        (->> routes (keep :module) distinct sort))
        modules  (or (seq (active-modules sys)) route-modules)
        ;; Build component list from REPL state if available, otherwise from injected refs
        components (if sys
                     (for [k (sort-by str (keys sys))]
                       {:name (name k) :status :running})
                     ;; Derive minimal component list from what we know is running
                     (cond-> []
                       handler  (conj {:name "http-handler" :status :running})
                       db-ctx   (conj {:name "db-context" :status :running})
                       true     (conj {:name "dashboard" :status :running})))
        adapter  (when db-ctx
                   (let [a (:adapter db-ctx)]
                     (if (keyword? a) (name a) (str (type a)))))
        host     (when db-ctx (or (get-in db-ctx [:options :host])
                                  (get-in db-ctx [:host])
                                  "localhost"))]
    {:component-count (or (:component-count ctx) (if sys (count sys) (count components)))
     :route-count     (count (or routes []))
     :route-methods   (when routes (frequencies (map :method routes)))
     :module-count    (count (or modules []))
     :module-names    modules
     :components      components
     :profile         (or (System/getenv "WAG_ENV") "dev")
     :db-info         (when adapter (str adapter " @ " (or host "localhost")))
     :http-port       (or (:http-port ctx) 3000)
     :nrepl-port      7888
     :java-version    (System/getProperty "java.version")}))

(defn render [opts]
  (let [data      (system-data opts)
        err-stats (dashboard-errors/error-stats)
        err-total (:total err-stats)]
    (layout/dashboard-page
     (merge opts {:component-count (:component-count data)
                  :error-count     err-total
                  :http-port       (:http-port data)
                  :system-status   :running})
     [:div.stat-grid
      (c/stat-card {:label "Components" :value (:component-count data)
                    :sub "all healthy" :sub-class "healthy"})
      (c/stat-card {:label "Routes" :value (:route-count data)
                    :sub (when-let [m (:route-methods data)]
                           (str/join " · "
                                     (for [[method cnt] (sort-by key m)]
                                       (str cnt " " (str/upper-case (name method))))))})
      (c/stat-card {:label "Modules" :value (:module-count data)
                    :sub (when (:module-names data)
                           (str/join " · " (:module-names data)))})
      (c/stat-card {:label "Errors (24h)" :value err-total
                    :value-class (if (pos? err-total) "stat-value-error" "green")
                    :sub (if (pos? err-total)
                           "view error dashboard"
                           "no recent errors")})]
     [:div.two-col
      (c/card {:title "Integrant Components" :flush? true}
              (c/data-table
               {:columns      ["Component" "Status"]
                :col-template "1fr 100px"
                :rows         (for [{:keys [name status]} (take 15 (:components data))]
                                {:cells [[:span.text-mono name]
                                         (c/status-dot status)]})}))
      (c/card {:title "Environment"}
              [:div {:style "font-family:var(--font-mono);font-size:12px;line-height:2"}
               [:div [:span.text-muted "Profile: "] [:span {:style "color:var(--accent-yellow)"} (:profile data)]]
               [:div [:span.text-muted "Database: "] [:span (or (:db-info data) "unknown")]]
               [:div [:span.text-muted "Web: "] [:a.topbar-link {:href (str "http://localhost:" (:http-port data)) :target "_blank"} (str "http://localhost:" (:http-port data))]]
               [:div [:span.text-muted "Admin: "] [:a.topbar-link {:href (str "http://localhost:" (:http-port data) "/web/admin/") :target "_blank"} (str "http://localhost:" (:http-port data) "/web/admin/")]]
               [:div [:span.text-muted "nREPL: "] [:span (str "port " (:nrepl-port data))]]
               [:div [:span.text-muted "Java: "] [:span (:java-version data)]]])])))
