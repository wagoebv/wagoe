(ns wagoe.catalogue-snippet-test
  "Does the config `wagoe add <module>` writes actually boot the module?

   `modules-catalogue.edn` carries a `:config-snippet` per module, injected into
   `:active` by `wagoe add`. Audience's still described the wiring BOU-419
   replaced: `#ig/ref` values, which `:active` holds settings not components,
   and a ref to `:wagoe/user-data-source` — a key that has never existed. So
   `wagoe add audience` wrote a config the application must not have (BOU-427).

   Read from the catalogue and booted, because a snippet is a claim about what
   works and only a boot settles it."
  (:require [aero.core :as aero]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.main :as main]
            [wagoe.platform.shell.modules :as modules]
            [wagoe.system-config :as sys-config]))

(defn- boot-with
  "Boot the test profile with `active` merged into :active, minus the HTTP
   listener — this is about whether the module comes up, not about a port."
  [active]
  (let [cfg (reduce-kv (fn [c k v] (assoc-in c [:active k] v))
                       (config/load-config {:profile :test})
                       active)]
    (ig/init (main/worker-ig-config (sys-config/ig-config cfg)))))

(defn- catalogue []
  (edn/read-string {:readers {'ig/ref (fn [k] (ig/ref k))}}
                   (slurp (io/file "libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn"))))

(def ^:private needs-more-than-config
  "Modules a documented config alone cannot boot, and what they want.

   Each is an entry that must keep failing for its stated reason. One that
   starts booting is removed from here — the burn-down rule the other gates
   use, so this list shrinks rather than rots."
  {"external" (str "its three adapters assert a host or an account-sid, and the "
                   "catalogue documents SMTP through the email entry rather than "
                   "here \u2014 :wagoe.external/imap and /twilio have no documented "
                   "settings at all (BOU-427)")})

(defn- module [name*]
  (first (filter #(= name* (:name %)) (:modules (catalogue)))))

(defn- snippet-settings
  "The `:active` entries a snippet injects, as data.

   Read through Aero, not with a hand-written reader table. Snippets carry
   `#env`, `#or` and `#long`, and approximating those is how a probe for this
   turned an unset credential into `nil` and blamed the module — the tags mean
   what Aero says they mean, and the application reads them the same way."
  [snippet]
  (when (seq (str/trim (or snippet "")))
    (let [f (java.io.File/createTempFile "snippet" ".edn")]
      (try
        (spit f (str "{" snippet "}"))
        (aero/read-config f {:profile :test})
        (finally (.delete f))))))

(deftest ^:integration the-audience-snippet-boots-the-module
  (let [entry (module "audience")]
    (testing "the catalogue was read"
      (is (some? entry) "no audience entry; this would pass vacuously")
      (is (seq (:config-snippet entry))))

    (testing "it names settings, not components"
      ;; The shape of the old one: an application writing `#ig/ref` into
      ;; `:active` is writing the wiring the module builds for itself.
      (let [settings (get (snippet-settings (:config-snippet entry)) :wagoe/audience)]
        (is (map? settings))
        (is (not-any? #(instance? integrant.core.Ref %) (vals settings))
            (str "the snippet puts Integrant refs in :active: " (pr-str settings)))))

    (testing "and a system configured from it comes up"
      (let [settings (get (snippet-settings (:config-snippet entry)) :wagoe/audience)
            cfg      (assoc-in (config/load-config {:profile :test})
                               [:active :wagoe/audience] settings)
            system   (ig/init (main/worker-ig-config (sys-config/ig-config cfg)))]
        (try
          (is (some? (:wagoe/audience system)))
          (is (some? (:wagoe/audience-user-source system)))
          (finally (ig/halt! system)))))))

(def ^:private activated-another-way
  "Framework modules whose catalogue entry carries no snippet, and why.

   `wagoe add` injects `:config-snippet` into `:active`; an empty one writes
   nothing, so the library lands in deps.edn and the module is never switched
   on. That is what reports and calendar did (BOU-427). These four are
   deliberate — an entry that grows a snippet is removed from here."
  {"user"     "enabled in code: `wagoe new` writes #{:wagoe/user} as an extra-module"
   "external" "its SMTP settings are documented under the email entry; :wagoe.external/imap and /twilio are opt-in by hand"
   "ui-style" "an asset bundle every service reads; nothing to configure"
   "devtools" "dev-only, and its library ships in the :repl alias rather than :deps"})

(deftest ^:unit every-module-is-switched-on-by-what-wagoe-add-writes
  (let [by-lib (into {} (map (juxt :name identity)) (:modules (catalogue)))]
    (testing "the catalogue was read"
      (is (< 15 (count by-lib))))

    (doseq [lib (sort (set (vals modules/framework-modules)))]
      (testing lib
        (let [snippet (:config-snippet (get by-lib lib))]
          (if-let [why (get activated-another-way lib)]
            (is (empty? (str/trim (or snippet "")))
                (str lib " has a snippet now — drop it from activated-another-way (" why ")"))
            (is (seq (str/trim (or snippet "")))
                (str lib " has an empty :config-snippet, so `wagoe add " lib
                     "` installs the library and switches nothing on"))))))))

(deftest ^:integration every-module-boots-from-its-documented-config
  ;; The promise is "switch on by editing config.edn alone". BOU-420 made the
  ;; library resolve; this asks whether the module then starts, from the config
  ;; `wagoe add` writes — the snippet where there is one, `{:enabled? true}`
  ;; where the catalogue says that is the whole of it.
  ;;
  ;; It found two things that reading could not: push's APNs provider selected
  ;; :apns for a credentials map of unset #env values, throwing on a key file
  ;; that is not there, and reports and calendar had no snippet at all, so
  ;; `wagoe add` installed them without switching them on.
  (let [by-lib (into {} (map (juxt :name identity)) (:modules (catalogue)))]

    (testing "the catalogue and the module table were both read"
      (is (< 15 (count by-lib)))
      (is (< 15 (count modules/framework-modules))))

    (doseq [[k lib] (sort-by val (apply dissoc modules/framework-modules
                                        (keys modules/dev-only-modules)))
            :let    [entry    (get by-lib lib)
                     settings (snippet-settings (:config-snippet entry))
                     active   (or settings {k {:enabled? true}})]]
      (testing (str k " from " (if settings "its snippet" "{:enabled? true}"))
        (if-let [why (get needs-more-than-config lib)]
          (is (thrown? Exception (boot-with active))
              (str lib " boots now — drop it from needs-more-than-config (" why ")"))
          (let [system (boot-with active)]
            (try (is (some? system))
                 (finally (ig/halt! system)))))))))
