(ns wagoe.bench.hotpaths
  "Baseline benchmarks for the hot-path findings of the performance assessment.

   Each section benches the CURRENT implementation against the PROPOSED fix so
   the gain is measured, not guessed.

   Run all:
     clojure -M:bench hotpaths

   Run from REPL:
     (require '[wagoe.bench.hotpaths :as hot])
     (hot/run-all)"
  (:require [criterium.core :as crit]
            [malli.core :as m]
            [wagoe.core.utils.case-conversion :as cc]
            [wagoe.i18n.shell.render :as i18n-render]
            [wagoe.observability.logging.ports :as log-ports]))

(defn- bench [label f]
  (println (str "\n--- " label " ---"))
  (crit/quick-bench (f))
  (println))

;; =============================================================================
;; 1. Malli: raw schema per call (current) vs compiled validator (proposed)
;; =============================================================================

(def ^:private user-schema
  [:map
   [:id :uuid]
   [:name [:string {:min 2 :max 100}]]
   [:email [:re #".+@.+\..+"]]
   [:role [:enum :user :admin :manager]]
   [:status [:enum :active :suspended :deleted]]
   [:created-at inst?]])

(def ^:private compiled-valid? (m/validator user-schema))
(def ^:private compiled-explain (m/explainer user-schema))

(def ^:private valid-user
  {:id (java.util.UUID/randomUUID)
   :name "Jane Doe"
   :email "jane@example.com"
   :role :user
   :status :active
   :created-at (java.util.Date.)})

(def ^:private invalid-user
  (assoc valid-user :role :unknown :email "bad"))

(defn run-malli []
  (println "\n========== Malli: raw vs compiled ==========")
  (bench "CURRENT  (m/validate raw-schema x)" #(m/validate user-schema valid-user))
  (bench "PROPOSED ((m/validator schema) x)" #(compiled-valid? valid-user))
  (bench "CURRENT  (m/explain raw-schema bad)" #(m/explain user-schema invalid-user))
  (bench "PROPOSED ((m/explainer schema) bad)" #(compiled-explain invalid-user)))

;; =============================================================================
;; 2. Case conversion: per-row map rebuild (current) vs memoized key fn
;; =============================================================================

(def ^:private db-row
  ;; 20 columns, realistic user row shape
  {:id "u-1" :tenant_id "t-1" :email "a@b.c" :password_hash "x" :name "Jane"
   :role "user" :status "active" :created_at "2026-01-01" :updated_at "2026-01-02"
   :last_login_at "2026-01-03" :failed_login_count 0 :lockout_until nil
   :email_verified_at "2026-01-01" :mfa_enabled false :mfa_secret nil
   :locale "en" :timezone "UTC" :avatar_url nil :phone_number nil :deleted_at nil})

(def ^:private result-set (vec (repeat 100 db-row)))

;; ponytail: bounded by column-name vocabulary, unbounded memoize is fine here
(def ^:private kebab-key
  (memoize (fn [k] (keyword (cc/snake-case->kebab-case-string (name k))))))

(defn- snake->kebab-map-memoized [m]
  (reduce-kv (fn [acc k v] (assoc acc (kebab-key k) v)) {} m))

(defn run-case-conversion []
  (println "\n========== Case conversion: DB result-set (100 rows x 20 cols) ==========")
  (bench "CURRENT  single row str/replace per key" #(cc/snake-case->kebab-case-map db-row))
  (bench "PROPOSED single row memoized key fn" #(snake->kebab-map-memoized db-row))
  (bench "CURRENT  100-row mapv rebuild (execution.clj)" #(mapv cc/snake-case->kebab-case-map result-set))
  (bench "PROPOSED 100-row mapv memoized keys" #(mapv snake->kebab-map-memoized result-set))
  (bench "CURRENT  triple conversion (execution + db->entity)"
         #(mapv (comp cc/snake-case->kebab-case-map cc/snake-case->kebab-case-map) result-set)))

;; =============================================================================
;; 3. i18n: postwalk marker resolution + second hiccup traversal
;; =============================================================================

(def ^:private t-fn
  (fn ([k] (str "T:" k)) ([k _params] (str "T:" k)) ([k _params _n] (str "T:" k))))

(def ^:private page-tree
  ;; Representative admin list page: chrome with markers + 50-row x 5-col table
  [:html
   [:head [:title [:t :admin/title]]]
   [:body
    [:nav (for [i (range 10)] [:a {:href (str "/" i)} [:t (keyword "nav" (str "item" i))]])]
    [:main
     [:h1 [:t :admin/users]]
     [:table
      [:thead [:tr (for [c (range 5)] [:th [:t (keyword "col" (str "c" c))]])]]
      [:tbody
       (for [r (range 50)]
         [:tr (for [c (range 5)] [:td (str "cell-" r "-" c)])])]]]]])

(defn run-i18n []
  (println "\n========== i18n render: full page (50-row table, ~26 markers) ==========")
  (bench "resolve-markers only (postwalk pass)" #(i18n-render/resolve-markers page-tree t-fn))
  (bench "full render (postwalk + hiccup2/html)" #(i18n-render/render page-tree t-fn))
  (let [resolved (i18n-render/resolve-markers page-tree t-fn)]
    (bench "hiccup2/html only (the unavoidable pass)" #(i18n-render/render resolved t-fn))))

;; =============================================================================
;; 4. Logger: reflective interop (current) vs protocol call (proposed)
;; =============================================================================

(def ^:private noop-logger
  (reify log-ports/ILogger
    (info [_ _message] nil)
    (info [_ _message _context] nil)))

(defn- reflective-info
  ;; no type hint — mirrors interceptors.clj (.info logger ...) call sites
  [logger]
  (.info logger "request started" {:method :get :path "/api/v1/users"}))

(defn- protocol-info [logger]
  (log-ports/info logger "request started" {:method :get :path "/api/v1/users"}))

(defn run-logger []
  (println "\n========== Logger dispatch: reflection vs protocol ==========")
  (bench "CURRENT  reflective (.info logger ...)" #(reflective-info noop-logger))
  (bench "PROPOSED protocol (ports/info logger ...)" #(protocol-info noop-logger)))

;; =============================================================================
;; Runner
;; =============================================================================

(defn run-all []
  (println "==============================================")
  (println "  Boundary hot-path baseline benchmarks")
  (println "==============================================")
  (run-malli)
  (run-case-conversion)
  (run-i18n)
  (run-logger)
  (println "\n========== Done =========="))
