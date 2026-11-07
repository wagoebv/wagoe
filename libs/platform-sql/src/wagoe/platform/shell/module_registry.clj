(ns wagoe.platform.shell.module-registry
  "Which library owns each framework module, and which modules a config
   switches on. Pure data, here rather than in wagoe-platform so migrations
   can skip switched-off modules without depending on it (BOU-934).
   `wagoe.platform.shell.modules` re-exports all three.")

(def framework-modules
  "`:active` key -> the library that owns it.

   The wiring namespace is derived (`wagoe.<lib>.shell.module-wiring`) rather
   than listed, so a new module is one entry. Four keys predate the convention
   and cannot be derived from their own name; they are spelled out.

   These are keyword-to-string pairs, not namespace loads: neither platform
   nor platform-sql depends on these libraries."
  (into {:wagoe/payment-provider    "payments"
         :wagoe/ai-service          "ai"
         :wagoe/geo-service         "geo"
         :wagoe.external/smtp       "external"
         :wagoe.external/imap       "external"
         :wagoe.external/twilio     "external"
         :wagoe.external/ftps       "external"
         :wagoe.external/soap       "external"
         :wagoe/dev-error-enricher  "devtools"
         :wagoe/dashboard           "devtools"}
        (map (juxt #(keyword "wagoe" %) identity))
        ["email" "i18n" "user" "cache" "tenant" "admin" "workflow" "search" "events"
         "push" "audience" "storage" "jobs" "realtime" "reports" "calendar"
         "ui-style" "webhooks"]))


(def always-on-modules
  "Modules every application gets whether or not its config mentions them.

   Both fall back to a working default — the logging email sender, an
   English-only catalogue — and the HTTP handler takes a ref to i18n's
   middleware unconditionally, so leaving them out is not an option an
   application has. They are modules rather than core components because the
   wiring lives in their own library, and platform depends on neither."
  #{:wagoe/email :wagoe/i18n})

(defn module-entries
  "The framework modules `active` switches on, as [key lib] pairs.

   `extra` names modules an application enables in code rather than in config;
   projects generated before BOU-667 enable user this way."
  [active extra]
  (->> framework-modules
       (filter (fn [[k _]] (or (contains? extra k)
                               (and (contains? active k)
                                    (not (false? (:enabled? (get active k))))))))
       (sort-by key)))

(defn enabled-libraries
  "The libraries whose framework modules `active` switches on, or `extra`
   enables in code, as names (\"geo\", \"workflow\"). Always-on ones included."
  ([active] (enabled-libraries active #{}))
  ([active extra]
   (into #{} (map val) (module-entries active (into always-on-modules extra)))))
