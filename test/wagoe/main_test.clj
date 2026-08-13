(ns wagoe.main-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.main :as main]))

(deftest ^:unit worker-ig-config-drops-http-surface
  (let [full {:wagoe/http-server  1
              :wagoe/http-handler  2
              :wagoe/dashboard     3
              :wagoe/db-context    4
              :wagoe/user-service  5}
        worker (main/worker-ig-config full)]
    (testing "the HTTP surface keys are removed so the worker binds no port"
      (is (empty? (set/intersection (set (keys worker))
                                    (set main/http-surface-keys)))))
    (testing "background/service components are kept"
      (is (= {:wagoe/db-context   4
              :wagoe/user-service 5}
             worker)))))

;; =============================================================================
;; Startup failure reporting
;; =============================================================================

(deftest ^:unit startup-failure-summary-omits-the-config-map
  ;; Integrant's ex-data carries :value — the config for the failing key — so
  ;; logging the exception printed the database configuration. Measured against
  ;; the prod profile, POSTGRES_PASSWORD appeared twice in the output of a
  ;; failed boot, which container logs ship onward.
  (let [db-config {:adapter  :postgresql
                   :host     "db.internal"
                   :username "app"
                   :password "S3cr3t-Prod-Passw0rd"}
        cause     (ex-info "FATAL: database \"app\" does not exist" {})
        failure   (ex-info "Error on key :wagoe/db-context when building system"
                           {:reason :integrant.core/build-threw-exception
                            :key    :wagoe/db-context
                            :value  db-config
                            :system {:wagoe/logging "…"}}
                           cause)
        summary   (main/startup-failure-summary failure)]

    (testing "the secret does not appear"
      (is (not (str/includes? summary "S3cr3t-Prod-Passw0rd"))))

    (testing "nor does any part of the config map"
      (is (not (str/includes? summary "db.internal")))
      (is (not (str/includes? summary ":password"))))

    (testing "the failing component is named"
      (is (str/includes? summary ":wagoe/db-context")))

    (testing "the actual reason is reported, not the Integrant wrapper"
      ;; "Error on key … when building system" says nothing a caller can act on.
      (is (str/includes? summary "database \"app\" does not exist")))

    (testing "the exception type is named, for a cause with no message"
      (is (str/includes? summary "ExceptionInfo")))))

(deftest ^:unit startup-failure-summary-handles-plain-failures
  (testing "an exception with no ex-data still reports its message"
    (let [summary (main/startup-failure-summary (RuntimeException. "boom"))]
      (is (str/includes? summary "boom"))
      (is (not (str/includes? summary "could not be built")))))

  (testing "the innermost cause is the one reported"
    (let [deep (ex-info "outer" {} (ex-info "middle" {} (RuntimeException. "innermost")))]
      (is (str/includes? (main/startup-failure-summary deep) "innermost"))))

  (testing "root-cause of an exception with no cause is itself"
    (let [e (RuntimeException. "only")]
      (is (identical? e (main/root-cause e))))))

(deftest ^:unit startup-failure-sites-do-not-log-the-exception
  ;; The unit tests above pass against a `startup-failure-summary` that nothing
  ;; calls. What leaked the password was `(log/error e "Failed to start …")` —
  ;; logging the exception object, whose ex-data carries the config map. This
  ;; asserts the call sites, not just the helper.
  (let [src (or (some #(when (.exists (io/file %)) (slurp %))
                      ["src/wagoe/main.clj" "../../src/wagoe/main.clj"])
                (throw (ex-info "src/wagoe/main.clj not found — cannot check"
                                {:cwd (System/getProperty "user.dir")})))
        start-sites (re-seq #"\(log/error[^)]*\"Failed to start[^\"]*\"" src)]

    (testing "the source was read — otherwise this passes vacuously"
      (is (str/includes? src "startup-failure-summary")))

    (testing "no startup failure logs the exception object"
      (is (empty? start-sites)
          (str "these log the exception, and its ex-data holds the config: "
               (pr-str start-sites))))

    (testing "every boot mode reports through the summary"
      ;; Counted rather than named, so a mode added later has to come here and
      ;; decide — which is what happened when `service` was added (BOU-91).
      ;; server, worker, service.
      (is (= 3 (count (re-seq #"\(log/error \(startup-failure-summary e\)\)" src)))))))

;; =============================================================================
;; Everything the config emits must be registered by the entry point
;; =============================================================================

(deftest ^:integration entry-point-registers-every-key-the-config-emits
  ;; The real invariant, and the one my first attempt missed: whatever
  ;; `ig-config` puts in the map, requiring the entry point must have
  ;; registered an `ig/init-key` for it. Anything else fails at startup with
  ;; "No method in multimethod 'init-key' for dispatch value: …".
  ;;
  ;; The earlier version of this test named three external keys by hand. That
  ;; passes while the next module moved out of platform breaks — which is how
  ;; :wagoe.external/smtp came to break generated projects. Comparing the two
  ;; sets needs no list to maintain.
  ;;
  ;; It asserts the *entry point*, not wagoe.config: 31 of the 32 keys are
  ;; registered by the module-wiring namespaces wagoe.main requires, not by the
  ;; config layer. Email and external self-register in wagoe.config because
  ;; that namespace emits them conditionally.
  ;; The optional modules are activated deliberately. Against the plain test
  ;; profile this test passed with the external require deleted, because that
  ;; profile activates no external adapter, so ig-config emitted none of the
  ;; keys the test exists to check — vacuous for its own motivating case.
  (let [loaded  (config/load-config {:profile :test})
        opt-in  {:wagoe.external/smtp   {:host "localhost" :port 25}
                 :wagoe.external/imap   {:host "localhost" :port 143}
                 :wagoe.external/twilio {:account-sid "x" :auth-token "y"}}
        with-all (update loaded :active merge opt-in)
        cfg        (config/ig-config with-all)
        registered (set (keys (methods ig/init-key)))
        missing    (remove registered (keys cfg))]

    (testing "the optional keys really are emitted — otherwise this is vacuous"
      (doseq [k (keys opt-in)]
        (is (contains? cfg k) (str k " was not emitted; the opt-in shape has changed"))))

    (testing "the config was built"
      (is (<= 20 (count cfg)) (str "only " (count cfg) " keys emitted")))

    (testing "every emitted key has an init-key method"
      (is (empty? missing)
          (str "emitted by ig-config but never registered: " (pr-str (sort missing))
               " — require the module-wiring namespace that defines them")))))

;; =============================================================================
;; Conditionally emitted keys must not be statically required (BOU-131)
;; =============================================================================

(deftest ^:unit config-requires-optional-module-wiring-only-when-active
  ;; Moving platform's static requires into wagoe.config fixed nothing if they
  ;; stayed static: loading this namespace would still demand jars the app may
  ;; not ship — the same mandatory dependency, one layer down.
  ;;
  ;; A module-wiring may be required statically here only if its key is emitted
  ;; unconditionally. Today that is email and i18n — both are in the base map of
  ;; core-system-config with defaults. cache, payments and the external adapters
  ;; are opt-in, and are required at the point the config decides they are
  ;; active.
  (let [src (or (some #(when (.exists (io/file %)) (slurp %))
                      ["src/wagoe/config.clj" "../../src/wagoe/config.clj"])
                (throw (ex-info "config.clj not found — cannot check"
                                {:cwd (System/getProperty "user.dir")})))
        ns-form  (subs src 0 (str/index-of src "(def ^:private env-aliases"))
        static   (set (map second (re-seq #"\[(wagoe\.[a-z0-9-]+\.shell\.module-wiring)\]" ns-form)))
        guarded  (set (map second (re-seq #"\(require '(wagoe\.[a-z0-9-]+\.shell\.module-wiring)\)" src)))]

    (testing "the source parsed — otherwise this passes vacuously"
      (is (str/includes? src "ig-config"))
      (is (seq static) "no static requires found at all; the ns form was not read"))

    (testing "only unconditionally emitted modules are required statically"
      (is (= #{"wagoe.email.shell.module-wiring"
               "wagoe.i18n.shell.module-wiring"} static)
          (str "statically required: " (pr-str static)
               " — a module whose key is emitted conditionally must be required "
               "where that condition is evaluated, or every consumer must ship its jar")))

    (testing "the opt-in modules are required, just guarded"
      (doseq [m ["wagoe.cache.shell.module-wiring"
                 "wagoe.payments.shell.module-wiring"
                 "wagoe.external.shell.module-wiring"]]
        (is (contains? guarded m)
            (str m " is neither statically required nor required at its condition — "
                 "its init-key would be missing when the module is active"))))))

;; =============================================================================
;; Every published library must be documented
;; =============================================================================

(deftest ^:integration every-published-library-has-a-documentation-page
  ;; BOU-93 added `libs/events` — a published artifact, a CLI catalogue entry,
  ;; a config key — and no page under docs/modules/libraries/. Nothing noticed:
  ;; `bb check:doc-counts` verifies the *number* is right, `bb check-links`
  ;; verifies existing links resolve, and neither asks whether a library that
  ;; exists is written about anywhere.
  ;;
  ;; It surfaces downstream rather than here: the website builds its library
  ;; docs from these pages, so an undocumented library is missing from
  ;; wagoe.org with nothing in either repo to say why.
  (let [;; A library is publishable exactly when it has a build.clj — the same
        ;; set wagoe.tools.deploy/all-libs carries, read from the filesystem
        ;; because that namespace is Babashka-only and not on this classpath.
        libs      (->> (.listFiles (io/file "libs"))
                       (filter #(.isDirectory ^java.io.File %))
                       (filter #(.exists (io/file % "build.clj")))
                       (map #(.getName ^java.io.File %))
                       set)
        ;; Directory name and page name differ for two libraries by design.
        page-name {"wagoe-cli" "cli" "wagoe-mcp" "mcp"}
        page-for  (fn [lib] (get page-name lib lib))
        pages     (->> (file-seq (io/file "docs/modules/libraries/pages"))
                       (filter #(.isFile ^java.io.File %))
                       (map #(str/replace (.getName ^java.io.File %) ".adoc" ""))
                       set)
        undocumented (remove #(contains? pages (page-for %)) (sort libs))]

    (testing "the library list and the pages were both read"
      ;; Otherwise an empty set on either side makes this pass vacuously.
      (is (< 25 (count libs)) (str "only found " (count libs) " libraries"))
      (is (< 25 (count pages)) (str "only found " (count pages) " pages")))

    (testing "every published library is written about"
      (is (empty? undocumented)
          (str "published with no page under docs/modules/libraries/pages/: "
               (str/join ", " undocumented)
               " — the website builds its library docs from these, so this is "
               "how a library ships and is then absent from wagoe.org")))))
