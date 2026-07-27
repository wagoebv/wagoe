# boundary-push Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a push notification library (`libs/push/`) supporting FCM + APNs with `defpush` macro, device token management, job-based delivery, HMAC-secured analytics callbacks, and error classification.

**Architecture:** FC/IS split — pure notification building, template rendering, payload construction, error classification in `core/`. All I/O (provider HTTP calls, DB persistence, job enqueueing, Ring handlers) in `shell/`. Platform-specific protocols (`IFCMProvider`, `IAPNsProvider`) behind unified `IPushService`.

**Tech Stack:** Clojure 1.12.4, Malli (schemas), Integrant (DI), next.jdbc + HoneySQL (DB), javax.crypto (HMAC), Reitit (routes), boundary-jobs (hard dep)

**Spec:** `docs/superpowers/specs/2026-05-24-boundary-push-design.md`

---

### Task 1: Library Scaffolding

**Files:**
- Create: `libs/push/deps.edn`
- Create: `libs/push/build.clj`
- Modify: `tests.edn` (add `:push` suite)

- [ ] **Step 1: Create `libs/push/deps.edn`**

```clojure
{:paths ["src" "resources"]

 :deps  {org.clojure/clojure       {:mvn/version "1.12.4"}
         metosin/malli             {:mvn/version "0.20.1"}
         org.clojure/tools.logging {:mvn/version "1.3.1"}
         com.github.seancorfield/next.jdbc {:mvn/version "1.3.1093"}
         com.github.seancorfield/honeysql  {:mvn/version "2.7.1316"}
         integrant/integrant      {:mvn/version "1.0.1"}
         cheshire/cheshire        {:mvn/version "6.2.0"}
         ring/ring-core           {:mvn/version "1.13.0"}
         com.google.auth/google-auth-library-oauth2-http {:mvn/version "1.30.1"}
         wagoe/jobs             {:local/root "../jobs"}
         wagoe/core             {:local/root "../core"}}

 :aliases
 {:test      {:extra-paths ["test"]
              :extra-deps  {lambdaisland/kaocha {:mvn/version "1.91.1392"}
                            com.h2database/h2   {:mvn/version "2.4.240"}}}

  :clj-kondo {:replace-deps {clj-kondo/clj-kondo {:mvn/version "2026.04.15"}}
              :main-opts    ["-m" "clj-kondo.main"]}

  :build     {:replace-deps {io.github.clojure/tools.build {:git/tag "v0.10.13" :git/sha "3a3c177d"}
                             slipset/deps-deploy           {:mvn/version "0.2.3"}}
              :ns-default   build}}}
```

- [ ] **Step 2: Create `libs/push/build.clj`**

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'org.boundary-app/boundary-push)
(def version "1.0.1-alpha-25")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/thijs-creemers/boundary"
                      :connection "scm:git:git://github.com/thijs-creemers/boundary.git"
                      :developerConnection "scm:git:ssh://git@github.com/thijs-creemers/boundary.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Push notification library for Boundary framework: FCM and APNs support, device management, delivery analytics"]
                           [:url "https://github.com/thijs-creemers/boundary"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License 2.0"]
                             [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (spit (str class-dir "/cljdoc.edn")
        (pr-str {:cljdoc/root "libs/push"}))
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact jar-file
    :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
```

- [ ] **Step 3: Add `:push` suite to `tests.edn`**

Three changes needed:

1. Add `"libs/push/src"` to the top-level `:kaocha/source-paths` vector
2. Add `"libs/push/test"` to the `:unit` suite's `:test-paths` vector
3. Add a new per-library suite entry to the `:tests` vector:

```clojure
{:id :push
 :test-paths ["libs/push/test"]
 :source-paths ["libs/push/src"]
 :ns-patterns ["boundary.push.*-test"]}
```

- [ ] **Step 4: Create directory structure**

```bash
mkdir -p libs/push/src/boundary/push/core
mkdir -p libs/push/src/boundary/push/shell/adapters
mkdir -p libs/push/test/boundary/push/core
mkdir -p libs/push/test/boundary/push/shell
mkdir -p libs/push/resources/boundary/push/migrations
```

- [ ] **Step 5: Commit**

```bash
git add libs/push/deps.edn libs/push/build.clj tests.edn
git commit -m "feat(push): scaffold library with deps.edn, build.clj, test suite"
```

---

### Task 2: Schemas

**Files:**
- Create: `libs/push/src/boundary/push/schema.clj`
- Create: `libs/push/test/boundary/push/core/schema_test.clj`

- [ ] **Step 1: Write schema validation tests**

```clojure
(ns boundary.push.core.schema-test
  (:require [clojure.test :refer :all]
            [boundary.push.schema :as schema]
            [malli.core :as m]))

(deftest ^:unit push-definition-validation
  (testing "valid push definition accepted"
    (is (m/validate schema/PushDefinition
          {:id :order-shipped
           :title {:en "Shipped" :nl "Verzonden"}
           :body {:en "Your order {{id}} shipped"}
           :channels #{:fcm :apns}
           :priority :high
           :ttl 3600
           :deep-link "/orders/{{id}}"
           :silent? false
           :collapse-key :order-status
           :retry {:max-attempts 3 :backoff :exponential}})))

  (testing "plain string title accepted"
    (is (m/validate schema/PushDefinition
          {:id :simple
           :title "Hello"
           :body "World"
           :channels #{:fcm}})))

  (testing "invalid id rejected"
    (is (not (m/validate schema/PushDefinition
               {:id "not-keyword"
                :title "X"
                :body "Y"
                :channels #{:fcm}})))))

(deftest ^:unit device-info-validation
  (testing "valid device info"
    (is (m/validate schema/DeviceInfo
          {:token "abc123" :platform :fcm :app-id "com.example"})))

  (testing "missing token rejected"
    (is (not (m/validate schema/DeviceInfo
               {:platform :fcm :app-id "com.example"})))))

(deftest ^:unit callback-payload-validation
  (testing "valid callback"
    (is (m/validate schema/CallbackPayload
          {:device-token "abc"
           :provider-message-id "msg-1"
           :event-type :delivered
           :callback-token "hmac-sig"})))

  (testing "invalid event type rejected"
    (is (not (m/validate schema/CallbackPayload
               {:device-token "abc"
                :provider-message-id "msg-1"
                :event-type :sent
                :callback-token "x"})))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.schema-test
```

Expected: FAIL — namespace not found.

- [ ] **Step 3: Implement schemas**

```clojure
(ns boundary.push.schema
  (:require [malli.core :as m]))

;; --- Enums ---
(def Platform [:enum :fcm :apns])
(def Priority [:enum :normal :high])
(def BackoffStrategy [:enum :exponential :linear :fixed])
(def AnalyticsEventType [:enum :sent :delivered :opened :failed])

;; --- Composites ---
(def LocalizedString
  [:or :string [:map-of :keyword :string]])

(def RetryConfig
  [:map
   [:max-attempts [:int {:min 1 :max 10}]]
   [:backoff {:optional true} BackoffStrategy]])

;; --- defpush definition ---
(def PushDefinition
  [:map
   [:id :keyword]
   [:title LocalizedString]
   [:body LocalizedString]
   [:channels [:set Platform]]
   [:priority {:optional true} Priority]
   [:ttl {:optional true} [:int {:min 0}]]
   [:deep-link {:optional true} :string]
   [:silent? {:optional true} :boolean]
   [:collapse-key {:optional true} :keyword]
   [:retry {:optional true} RetryConfig]])

;; --- Device ---
(def DeviceInfo
  [:map
   [:token [:string {:min 1}]]
   [:platform Platform]
   [:app-id [:string {:min 1}]]
   [:device-name {:optional true} :string]
   [:os-version {:optional true} :string]])

(def DeviceRecord
  [:map
   [:id :uuid]
   [:user-id :uuid]
   [:token :string]
   [:platform Platform]
   [:app-id :string]
   [:active? :boolean]
   [:created-at inst?]
   [:last-used-at inst?]])

;; --- Send input ---
(def SendPushInput
  [:map
   [:user-id :uuid]
   [:locale {:optional true} :keyword]])

;; --- Analytics ---
(def AnalyticsEvent
  [:map
   [:id :uuid]
   [:notification-id :keyword]
   [:device-token :string]
   [:event-type AnalyticsEventType]
   [:platform Platform]
   [:user-id {:optional true} :uuid]
   [:provider-message-id {:optional true} :string]
   [:error {:optional true} :string]
   [:timestamp inst?]])

(def PushStats
  [:map
   [:notification-id :keyword]
   [:sent :int]
   [:delivered :int]
   [:opened :int]
   [:failed :int]
   [:delivery-rate {:optional true} :double]
   [:open-rate {:optional true} :double]])

;; --- Callback ---
(def CallbackPayload
  [:map
   [:device-token :string]
   [:provider-message-id :string]
   [:event-type [:enum :delivered :opened]]
   [:callback-token :string]
   [:timestamp {:optional true} inst?]])

;; --- Validators ---
(defn valid-push-definition? [d] (m/validate PushDefinition d))
(defn explain-push-definition [d] (m/explain PushDefinition d))
(defn valid-device-info? [d] (m/validate DeviceInfo d))
(defn valid-callback? [d] (m/validate CallbackPayload d))
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.schema-test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/push/src/boundary/push/schema.clj libs/push/test/boundary/push/core/schema_test.clj
git commit -m "feat(push): add Malli schemas for push definitions, devices, analytics, callbacks"
```

---

### Task 3: Protocols

**Files:**
- Create: `libs/push/src/boundary/push/ports.clj`

- [ ] **Step 1: Create ports.clj**

```clojure
(ns boundary.push.ports
  "Protocol definitions for push notification delivery, device management, and analytics.")

;; ===== Service =====

(defprotocol IPushService
  (send-push! [this notification-id data opts]
    "Enqueue push delivery for all user devices. opts: {:user-id uuid, :locale kw}")
  (schedule-push! [this notification-id data opts scheduled-at]
    "Schedule push for future delivery via jobs.")
  (broadcast! [this notification-id data opts]
    "Send to all registered devices matching opts: {:platform kw, :app-id str}"))

;; ===== Providers =====

(defprotocol IFCMProvider
  (fcm-send! [this payload]
    "Send FCM message. Returns {:success? bool :message-id str :error map}")
  (fcm-send-multicast! [this payload tokens]
    "Send to multiple FCM tokens. Returns per-token results.")
  (fcm-validate-token [this token]
    "Dry-run send to check token validity."))

(defprotocol IAPNsProvider
  (apns-send! [this payload device-token]
    "Send APNs notification. Returns {:success? bool :apns-id str :error map}")
  (apns-send-batch! [this payload device-tokens]
    "Send to multiple APNs devices. Returns per-token results."))

;; ===== Persistence =====

(defprotocol IDeviceTokenStore
  (register-device! [this user-id device-info]
    "Store device token. device-info: {:token str :platform kw :app-id str}")
  (unregister-device! [this user-id device-token]
    "Remove device token.")
  (get-user-devices [this user-id]
    "All active devices for user.")
  (get-devices-by-platform [this platform opts]
    "All devices for platform. opts: {:limit n :offset n}. Used by broadcast.")
  (mark-token-invalid! [this device-token]
    "Flag token as invalid after provider rejection.")
  (cleanup-stale-tokens! [this max-age-days]
    "Purge tokens not used within max-age-days."))

;; ===== Analytics =====

(defprotocol IPushAnalyticsStore
  (record-send! [this event]
    "Log send attempt with provider response.")
  (record-delivery! [this event]
    "Log client-reported delivery confirmation.")
  (record-open! [this event]
    "Log client-reported notification open.")
  (get-push-stats [this notification-id opts]
    "Aggregate stats: sent/delivered/opened/failed counts.")
  (cleanup-old-events! [this retention-days]
    "Purge analytics events older than retention-days."))
```

- [ ] **Step 2: Verify it compiles**

```bash
clojure -M -e "(require 'boundary.push.ports)"
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add libs/push/src/boundary/push/ports.clj
git commit -m "feat(push): define protocols — IPushService, IFCMProvider, IAPNsProvider, stores"
```

---

### Task 4: `defpush` Macro & Notification Core

**Files:**
- Create: `libs/push/src/boundary/push/core/notification.clj`
- Create: `libs/push/test/boundary/push/core/notification_test.clj`

- [ ] **Step 1: Write notification core tests**

```clojure
(ns boundary.push.core.notification-test
  (:require [clojure.test :refer :all]
            [boundary.push.core.notification :as notif]))

(use-fixtures :each (fn [f] (notif/clear-registry!) (f)))

(deftest ^:unit render-template-test
  (is (= "Order ORD-123 shipped"
         (notif/render-template "Order {{order-id}} shipped"
                                {:order-id "ORD-123"})))
  (testing "missing placeholder left as-is"
    (is (= "Hello {{name}}"
           (notif/render-template "Hello {{name}}" {})))))

(deftest ^:unit resolve-content-test
  (testing "returns requested locale"
    (is (= "Verzonden"
           (notif/resolve-content {:en "Shipped" :nl "Verzonden"} :nl))))
  (testing "falls back to :en"
    (is (= "Shipped"
           (notif/resolve-content {:en "Shipped" :nl "Verzonden"} :de))))
  (testing "plain string passes through"
    (is (= "Shipped"
           (notif/resolve-content "Shipped" :nl))))
  (testing "falls back to first available when no :en"
    (is (some? (notif/resolve-content {:nl "Verzonden" :de "Versendet"} :fr)))))

(deftest ^:unit defpush-and-registry-test
  (notif/register-push!
    {:id :test-notification
     :title {:en "Test"}
     :body {:en "Body"}
     :channels #{:fcm}})
  (testing "registered push is retrievable"
    (is (= :test-notification (:id (notif/get-push :test-notification)))))
  (testing "list-pushes returns registered ids"
    (is (= [:test-notification] (notif/list-pushes))))
  (testing "clear-registry! removes all"
    (notif/clear-registry!)
    (is (nil? (notif/get-push :test-notification)))))

(deftest ^:unit build-notification-test
  (notif/register-push!
    {:id :order-shipped
     :title {:en "Order {{order-id}} Shipped" :nl "Bestelling {{order-id}} Verzonden"}
     :body {:en "On its way!" :nl "Onderweg!"}
     :channels #{:fcm :apns}
     :priority :high
     :ttl 3600
     :deep-link "/orders/{{order-id}}"
     :silent? false
     :collapse-key :order-status})
  (let [result (notif/build-notification
                 (notif/get-push :order-shipped)
                 {:order-id "ORD-42"}
                 :nl)]
    (is (= "Bestelling ORD-42 Verzonden" (:title result)))
    (is (= "Onderweg!" (:body result)))
    (is (= "/orders/ORD-42" (:deep-link result)))
    (is (= :high (:priority result)))
    (is (= 3600 (:ttl result)))
    (is (= false (:silent? result)))
    (is (= :order-status (:collapse-key result)))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.notification-test
```

Expected: FAIL — namespace not found.

- [ ] **Step 3: Implement notification core**

```clojure
(ns boundary.push.core.notification
  "Push notification definitions, registry, and template rendering."
  (:require [clojure.string :as str]))

;; ===== Registry =====

(defonce ^:private registry-atom (atom {}))

(defn register-push! [definition]
  (swap! registry-atom assoc (:id definition) definition)
  definition)

(defn get-push [id]
  (get @registry-atom id))

(defn list-pushes []
  (vec (keys @registry-atom)))

(defn clear-registry! []
  (reset! registry-atom {}))

(defmacro defpush
  "Define and register a push notification type."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-push! ~sym)
     ~sym))

(defn render-template
  "Interpolate {{var}} placeholders with data map values."
  [template data]
  (reduce-kv
    (fn [s k v]
      (str/replace s (str "{{" (name k) "}}") (str v)))
    template
    data))

(defn resolve-content
  "Resolve localized content. Fallback: requested -> :en -> first available."
  [content locale]
  (cond
    (string? content) content
    (map? content)    (or (get content locale)
                         (get content :en)
                         (first (vals content)))))

(defn build-notification
  "Pure: resolve locale + render templates into ready-to-send map."
  [push-def data locale]
  {:title       (render-template (resolve-content (:title push-def) locale) data)
   :body        (render-template (resolve-content (:body push-def) locale) data)
   :deep-link   (some-> (:deep-link push-def) (render-template data))
   :priority    (:priority push-def :normal)
   :ttl         (:ttl push-def 86400)
   :silent?     (:silent? push-def false)
   :collapse-key (:collapse-key push-def)
   :data        data})
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.notification-test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/push/src/boundary/push/core/notification.clj libs/push/test/boundary/push/core/notification_test.clj
git commit -m "feat(push): defpush macro, registry, template rendering, locale resolution"
```

---

### Task 5: Delivery Core — Payload Building & Error Classification

**Files:**
- Create: `libs/push/src/boundary/push/core/delivery.clj`
- Create: `libs/push/test/boundary/push/core/delivery_test.clj`

- [ ] **Step 1: Write delivery core tests**

```clojure
(ns boundary.push.core.delivery-test
  (:require [clojure.test :refer :all]
            [boundary.push.core.delivery :as delivery]))

(def sample-notification
  {:title "Order Shipped"
   :body "Your order ORD-42 is on its way!"
   :deep-link "/orders/ORD-42"
   :priority :high
   :ttl 3600
   :silent? false
   :collapse-key :order-status
   :data {:order-id "ORD-42"}})

(deftest ^:unit build-fcm-payload-test
  (let [payload (delivery/build-fcm-payload sample-notification "fcm-token-abc")]
    (is (= "fcm-token-abc" (get-in payload [:message :token])))
    (is (= "Order Shipped" (get-in payload [:message :notification :title])))
    (is (= "Your order ORD-42 is on its way!" (get-in payload [:message :notification :body])))
    (is (= "high" (get-in payload [:message :android :priority])))
    (is (= "3600s" (get-in payload [:message :android :ttl])))
    (is (= "order-status" (get-in payload [:message :android :collapse_key])))))

(deftest ^:unit build-apns-payload-test
  (let [payload (delivery/build-apns-payload sample-notification)]
    (is (= "Order Shipped" (get-in payload [:aps :alert :title])))
    (is (= "Your order ORD-42 is on its way!" (get-in payload [:aps :alert :body])))
    (is (= "default" (get-in payload [:aps :sound])))
    (is (= 0 (get-in payload [:aps :content-available])))
    (is (= "/orders/ORD-42" (:deep-link payload)))))

(deftest ^:unit build-apns-silent-payload-test
  (let [silent (assoc sample-notification :silent? true)
        payload (delivery/build-apns-payload silent)]
    (is (nil? (get-in payload [:aps :sound])))
    (is (= 1 (get-in payload [:aps :content-available])))))

(deftest ^:unit classify-error-test
  (testing "FCM errors"
    (is (= :retryable (delivery/classify-error :fcm "UNAVAILABLE")))
    (is (= :retryable (delivery/classify-error :fcm "INTERNAL")))
    (is (= :token-invalid (delivery/classify-error :fcm "UNREGISTERED")))
    (is (= :token-invalid (delivery/classify-error :fcm "INVALID_ARGUMENT")))
    (is (= :rate-limited (delivery/classify-error :fcm "QUOTA_EXCEEDED")))
    (is (= :permanent (delivery/classify-error :fcm "PERMISSION_DENIED")))
    (is (= :permanent (delivery/classify-error :fcm "SENDER_ID_MISMATCH"))))
  (testing "APNs errors"
    (is (= :retryable (delivery/classify-error :apns "ServiceUnavailable")))
    (is (= :token-invalid (delivery/classify-error :apns "BadDeviceToken")))
    (is (= :token-invalid (delivery/classify-error :apns "Unregistered")))
    (is (= :rate-limited (delivery/classify-error :apns "TooManyRequests")))
    (is (= :permanent (delivery/classify-error :apns "BadCertificate")))
    (is (= :permanent (delivery/classify-error :apns "Forbidden"))))
  (testing "unknown error defaults to :retryable"
    (is (= :retryable (delivery/classify-error :fcm "SOMETHING_NEW")))))

(deftest ^:unit retry-delay-ms-test
  (testing "exponential backoff"
    (is (= 1000 (delivery/retry-delay-ms {:backoff :exponential} 0)))
    (is (= 2000 (delivery/retry-delay-ms {:backoff :exponential} 1)))
    (is (= 4000 (delivery/retry-delay-ms {:backoff :exponential} 2))))
  (testing "linear backoff"
    (is (= 0 (delivery/retry-delay-ms {:backoff :linear} 0)))
    (is (= 1000 (delivery/retry-delay-ms {:backoff :linear} 1))))
  (testing "fixed backoff"
    (is (= 2000 (delivery/retry-delay-ms {:backoff :fixed} 0)))
    (is (= 2000 (delivery/retry-delay-ms {:backoff :fixed} 3)))))

(deftest ^:unit group-devices-by-platform-test
  (let [devices [{:token "a" :platform :fcm}
                 {:token "b" :platform :apns}
                 {:token "c" :platform :fcm}]]
    (is (= {:fcm [{:token "a" :platform :fcm} {:token "c" :platform :fcm}]
            :apns [{:token "b" :platform :apns}]}
           (delivery/group-devices-by-platform devices)))))

(deftest ^:unit stringify-values-test
  (is (= {"order-id" "ORD-42" "count" "3"}
         (delivery/stringify-values {:order-id "ORD-42" :count 3}))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.delivery-test
```

Expected: FAIL

- [ ] **Step 3: Implement delivery core**

```clojure
(ns boundary.push.core.delivery)

(defn stringify-values
  "Convert map keys to strings, values to strings. FCM data field requires string values."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (name k) (str v))) {} m))

(defn group-devices-by-platform
  "Group devices by :platform key."
  [devices]
  (group-by :platform devices))

(defn build-fcm-payload
  "Pure: transform rendered notification into FCM v1 API payload."
  [notification device-token]
  {:message
   {:token        device-token
    :notification {:title (:title notification)
                   :body  (:body notification)}
    :data         (stringify-values (:data notification))
    :android      {:priority     (name (:priority notification :normal))
                   :ttl          (str (:ttl notification 86400) "s")
                   :collapse_key (some-> (:collapse-key notification) name)
                   :notification {:click_action "OPEN_ACTIVITY"}}
    :apns         nil}})

(defn build-apns-payload
  "Pure: transform rendered notification into APNs payload."
  [notification]
  {:aps       {:alert             {:title (:title notification)
                                   :body  (:body notification)}
               :sound             (when-not (:silent? notification) "default")
               :badge             1
               :content-available (if (:silent? notification) 1 0)
               :mutable-content   1}
   :deep-link (:deep-link notification)
   :data      (:data notification)})

(def ^:private fcm-error-classification
  {"UNAVAILABLE"        :retryable
   "INTERNAL"           :retryable
   "UNREGISTERED"       :token-invalid
   "INVALID_ARGUMENT"   :token-invalid
   "QUOTA_EXCEEDED"     :rate-limited
   "PERMISSION_DENIED"  :permanent
   "SENDER_ID_MISMATCH" :permanent})

(def ^:private apns-error-classification
  {"ServiceUnavailable" :retryable
   "BadDeviceToken"     :token-invalid
   "Unregistered"       :token-invalid
   "TooManyRequests"    :rate-limited
   "BadCertificate"     :permanent
   "Forbidden"          :permanent})

(defn classify-error
  "Pure: classify provider error code into action category."
  [platform error-code]
  (let [table (case platform
                :fcm  fcm-error-classification
                :apns apns-error-classification)]
    (get table error-code :retryable)))

(defn retry-delay-ms
  "Pure: calculate backoff delay in ms for attempt n."
  [retry-config attempt]
  (case (:backoff retry-config :exponential)
    :exponential (* 1000 (long (Math/pow 2 attempt)))
    :linear      (* 1000 attempt)
    :fixed       2000))

(defn result->analytics-event
  "Pure: transform provider send result into analytics event map. Caller supplies timestamp."
  [notification-id {:keys [device-token platform success? message-id error]} timestamp]
  {:notification-id     notification-id
   :device-token        device-token
   :platform            platform
   :event-type          (if success? :sent :failed)
   :provider-message-id message-id
   :error               error
   :timestamp           timestamp})
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.delivery-test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/push/src/boundary/push/core/delivery.clj libs/push/test/boundary/push/core/delivery_test.clj
git commit -m "feat(push): payload building (FCM/APNs), error classification, retry backoff"
```

---

### Task 6: Device Core — Token Validation & Staleness

**Files:**
- Create: `libs/push/src/boundary/push/core/device.clj`
- Create: `libs/push/test/boundary/push/core/device_test.clj`

- [ ] **Step 1: Write device core tests**

```clojure
(ns boundary.push.core.device-test
  (:require [clojure.test :refer :all]
            [boundary.push.core.device :as device]))

(deftest ^:unit detect-platform-test
  (testing "FCM tokens are long alphanumeric with colons"
    (is (= :fcm (device/detect-platform "dGVzdA:APA91bHnK..."))))
  (testing "APNs tokens are 64-char hex"
    (is (= :apns (device/detect-platform (apply str (repeat 64 "a"))))))
  (testing "unknown defaults to nil"
    (is (nil? (device/detect-platform "short")))))

(deftest ^:unit stale-token?-test
  (let [now (java.time.Instant/now)
        old (java.time.Instant/parse "2025-01-01T00:00:00Z")]
    (testing "token used recently is not stale"
      (is (not (device/stale-token? {:last-used-at now} 30 now))))
    (testing "token unused for > max-age is stale"
      (is (device/stale-token? {:last-used-at old} 30 now)))))

(deftest ^:unit prepare-device-record-test
  (let [id     (random-uuid)
        now    (java.util.Date.)
        record (device/prepare-device-record
                 (random-uuid)
                 {:token "abc" :platform :fcm :app-id "com.example"
                  :device-name "Pixel 8" :os-version "Android 15"}
                 id now)]
    (is (= id (:id record)))
    (is (= "abc" (:token record)))
    (is (= :fcm (:platform record)))
    (is (true? (:active? record)))
    (is (= now (:created-at record)))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.device-test
```

Expected: FAIL

- [ ] **Step 3: Implement device core**

```clojure
(ns boundary.push.core.device
  (:import [java.time Instant Duration]))

(defn detect-platform
  "Heuristic platform detection from token format. Returns :fcm, :apns, or nil."
  [token]
  (cond
    (and (string? token) (re-find #":" token) (> (count token) 100)) :fcm
    (and (string? token) (= 64 (count token)) (re-matches #"[a-fA-F0-9]+" token)) :apns
    :else nil))

(defn stale-token?
  "Check if device token hasn't been used within max-age-days. Caller supplies current instant."
  [{:keys [last-used-at]} max-age-days now]
  (let [max-age  (Duration/ofDays max-age-days)
        used-at  (if (inst? last-used-at)
                   (.toInstant last-used-at)
                   last-used-at)]
    (.isAfter (Duration/between used-at now) max-age)))

(defn prepare-device-record
  "Pure: build device record from user-id and device-info. Caller supplies id and now."
  [user-id device-info id now]
  {:id           id
   :user-id      user-id
   :token        (:token device-info)
   :platform     (:platform device-info)
   :app-id       (:app-id device-info)
   :device-name  (:device-name device-info)
   :os-version   (:os-version device-info)
   :active?      true
   :created-at   now
   :last-used-at now})
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.device-test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/push/src/boundary/push/core/device.clj libs/push/test/boundary/push/core/device_test.clj
git commit -m "feat(push): device core — platform detection, staleness check, record preparation"
```

---

### Task 7: Analytics Core — Stats Aggregation

**Files:**
- Create: `libs/push/src/boundary/push/core/analytics.clj`
- Create: `libs/push/test/boundary/push/core/analytics_test.clj`

- [ ] **Step 1: Write analytics tests**

```clojure
(ns boundary.push.core.analytics-test
  (:require [clojure.test :refer :all]
            [boundary.push.core.analytics :as analytics]))

(deftest ^:unit calculate-rates-test
  (testing "normal counts"
    (let [result (analytics/calculate-rates
                   {:notification-id :test :sent 100 :delivered 80 :opened 20 :failed 5})]
      (is (= 0.8 (:delivery-rate result)))
      (is (= 0.25 (:open-rate result)))))
  (testing "zero sent — no rates added"
    (let [result (analytics/calculate-rates
                   {:notification-id :test :sent 0 :delivered 0 :opened 0 :failed 0})]
      (is (nil? (:delivery-rate result)))
      (is (nil? (:open-rate result)))))
  (testing "sent but zero delivered — delivery-rate present, no open-rate"
    (let [result (analytics/calculate-rates
                   {:notification-id :test :sent 10 :delivered 0 :opened 0 :failed 10})]
      (is (= 0.0 (:delivery-rate result)))
      (is (nil? (:open-rate result))))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.analytics-test
```

Expected: FAIL

- [ ] **Step 3: Implement analytics core**

```clojure
(ns boundary.push.core.analytics)

(defn calculate-rates
  "Pure: compute delivery-rate and open-rate from raw counts."
  [{:keys [sent delivered opened] :as stats}]
  (cond-> stats
    (pos? sent) (assoc :delivery-rate (double (/ delivered sent)))
    (pos? delivered) (assoc :open-rate (double (/ opened delivered)))))
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.push.core.analytics-test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/push/src/boundary/push/core/analytics.clj libs/push/test/boundary/push/core/analytics_test.clj
git commit -m "feat(push): analytics core — rate calculations with TDD"
```

---

### Task 8: Database Migrations

**Files:**
- Create: `libs/push/resources/boundary/push/migrations/20260524000000-device-tokens.up.sql`
- Create: `libs/push/resources/boundary/push/migrations/20260524000000-device-tokens.down.sql`
- Create: `libs/push/resources/boundary/push/migrations/20260524000001-push-send-log.up.sql`
- Create: `libs/push/resources/boundary/push/migrations/20260524000001-push-send-log.down.sql`
- Create: `libs/push/resources/boundary/push/migrations/20260524000002-analytics-events.up.sql`
- Create: `libs/push/resources/boundary/push/migrations/20260524000002-analytics-events.down.sql`

- [ ] **Step 1: Create device tokens migration (up)**

```sql
CREATE TABLE IF NOT EXISTS push_device_tokens (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL,
    tenant_id     UUID,
    token         VARCHAR(512) NOT NULL,
    platform      VARCHAR(10) NOT NULL,
    app_id        VARCHAR(255) NOT NULL,
    device_name   VARCHAR(255),
    os_version    VARCHAR(50),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_push_device_token UNIQUE (token, app_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_push_devices_user ON push_device_tokens (user_id, active);
--;;
CREATE INDEX IF NOT EXISTS idx_push_devices_platform ON push_device_tokens (platform, active);
```

- [ ] **Step 2: Create device tokens migration (down)**

```sql
DROP TABLE IF EXISTS push_device_tokens;
```

- [ ] **Step 3: Create send log migration (up)**

```sql
CREATE TABLE IF NOT EXISTS push_send_log (
    id                  UUID PRIMARY KEY,
    notification_id     VARCHAR(255) NOT NULL,
    user_id             UUID,
    device_token_id     UUID,
    device_token        VARCHAR(512) NOT NULL,
    platform            VARCHAR(10) NOT NULL,
    title               VARCHAR(500),
    body                TEXT,
    priority            VARCHAR(10) NOT NULL DEFAULT 'normal',
    status              VARCHAR(20) NOT NULL,
    provider_message_id VARCHAR(255),
    error_message       TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at             TIMESTAMP,
    tenant_id           UUID
);
--;;
CREATE INDEX IF NOT EXISTS idx_push_log_notification ON push_send_log (notification_id, created_at);
--;;
CREATE INDEX IF NOT EXISTS idx_push_log_user ON push_send_log (user_id, created_at);
```

- [ ] **Step 4: Create send log migration (down)**

```sql
DROP TABLE IF EXISTS push_send_log;
```

- [ ] **Step 5: Create analytics events migration (up)**

```sql
CREATE TABLE IF NOT EXISTS push_analytics_events (
    id                  UUID PRIMARY KEY,
    notification_id     VARCHAR(255) NOT NULL,
    device_token        VARCHAR(512) NOT NULL,
    platform            VARCHAR(10) NOT NULL,
    event_type          VARCHAR(20) NOT NULL,
    user_id             UUID,
    provider_message_id VARCHAR(255),
    error_message       TEXT,
    timestamp           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tenant_id           UUID
);
--;;
CREATE INDEX IF NOT EXISTS idx_push_analytics_notification ON push_analytics_events (notification_id, event_type);
--;;
CREATE INDEX IF NOT EXISTS idx_push_analytics_time ON push_analytics_events (timestamp);
```

- [ ] **Step 6: Create analytics events migration (down)**

```sql
DROP TABLE IF EXISTS push_analytics_events;
```

- [ ] **Step 7: Commit**

```bash
git add libs/push/resources/
git commit -m "feat(push): database migrations — device tokens, send log, analytics events"
```

---

### Task 9: Persistence Shell — Device Token Store

**Files:**
- Create: `libs/push/src/boundary/push/shell/persistence.clj`
- Create: `libs/push/test/boundary/push/shell/persistence_test.clj`

- [ ] **Step 1: Write contract tests for device token store**

```clojure
(ns boundary.push.shell.persistence-test
  (:require [clojure.test :refer :all]
            [boundary.push.shell.persistence :as p]
            [boundary.push.ports :as ports]
            [next.jdbc :as jdbc]))

(def ^:dynamic *db* nil)

(defn create-test-db []
  (let [ds (jdbc/get-datasource {:dbtype "h2:mem" :dbname (str "push-test-" (random-uuid))})]
    (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS push_device_tokens (
                         id UUID PRIMARY KEY, user_id UUID NOT NULL, tenant_id UUID,
                         token VARCHAR(512) NOT NULL, platform VARCHAR(10) NOT NULL,
                         app_id VARCHAR(255) NOT NULL, device_name VARCHAR(255),
                         os_version VARCHAR(50), active BOOLEAN NOT NULL DEFAULT TRUE,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         last_used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         CONSTRAINT uq_push_device_token UNIQUE (token, app_id))"])
    (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS push_analytics_events (
                         id UUID PRIMARY KEY, notification_id VARCHAR(255) NOT NULL,
                         device_token VARCHAR(512) NOT NULL, platform VARCHAR(10) NOT NULL,
                         event_type VARCHAR(20) NOT NULL, user_id UUID,
                         provider_message_id VARCHAR(255), error_message TEXT,
                         timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         tenant_id UUID)"])
    ds))

(use-fixtures :each
  (fn [f]
    (binding [*db* (create-test-db)]
      (f))))

(deftest ^:contract device-token-register-and-retrieve
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id
      {:token "fcm-token-123" :platform :fcm :app-id "com.example"})
    (let [devices (ports/get-user-devices store user-id)]
      (is (= 1 (count devices)))
      (is (= "fcm-token-123" (:token (first devices))))
      (is (= :fcm (:platform (first devices)))))))

(deftest ^:contract device-token-unregister
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id
      {:token "token-to-remove" :platform :fcm :app-id "com.example"})
    (ports/unregister-device! store user-id "token-to-remove")
    (is (empty? (ports/get-user-devices store user-id)))))

(deftest ^:contract mark-token-invalid-filters-from-active
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id
      {:token "valid-token" :platform :apns :app-id "com.example"})
    (ports/register-device! store user-id
      {:token "bad-token" :platform :apns :app-id "com.example2"})
    (ports/mark-token-invalid! store "bad-token")
    (let [devices (ports/get-user-devices store user-id)]
      (is (= 1 (count devices)))
      (is (= "valid-token" (:token (first devices)))))))

(deftest ^:contract duplicate-token-upserts
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id
      {:token "dup-token" :platform :fcm :app-id "com.example"})
    (ports/register-device! store user-id
      {:token "dup-token" :platform :fcm :app-id "com.example"})
    (is (= 1 (count (ports/get-user-devices store user-id))))))

(deftest ^:contract get-devices-by-platform-with-pagination
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (doseq [i (range 5)]
      (ports/register-device! store user-id
        {:token (str "fcm-" i) :platform :fcm :app-id (str "app-" i)}))
    (let [page1 (ports/get-devices-by-platform store :fcm {:limit 2 :offset 0})
          page2 (ports/get-devices-by-platform store :fcm {:limit 2 :offset 2})]
      (is (= 2 (count page1)))
      (is (= 2 (count page2))))))

(deftest ^:contract analytics-record-and-stats
  (let [store (p/->PushAnalyticsStore *db*)]
    (ports/record-send! store
      {:id (random-uuid) :notification-id :test-notif :device-token "t1"
       :platform :fcm :event-type :sent :timestamp (java.util.Date.)})
    (ports/record-send! store
      {:id (random-uuid) :notification-id :test-notif :device-token "t2"
       :platform :fcm :event-type :sent :timestamp (java.util.Date.)})
    (ports/record-delivery! store
      {:id (random-uuid) :notification-id :test-notif :device-token "t1"
       :platform :fcm :event-type :delivered :timestamp (java.util.Date.)})
    (let [stats (ports/get-push-stats store :test-notif {})]
      (is (= 2 (:sent stats)))
      (is (= 1 (:delivered stats))))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.push.shell.persistence-test
```

Expected: FAIL

- [ ] **Step 3: Implement persistence**

```clojure
(ns boundary.push.shell.persistence
  (:require [boundary.push.ports :as ports]
            [boundary.push.core.device :as device]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]))

(def ^:private default-opts
  {:builder-fn rs/as-unqualified-kebab-maps})

(defn- execute-one! [db sqlmap]
  (jdbc/execute-one! db (sql/format sqlmap) default-opts))

(defn- execute! [db sqlmap]
  (jdbc/execute! db (sql/format sqlmap) default-opts))

(defn- row->device [{:keys [platform] :as row}]
  (when row
    (-> row
        (update :platform keyword)
        (assoc :active? (:active row))
        (dissoc :active))))

(defrecord DeviceTokenStore [db]
  ports/IDeviceTokenStore

  (register-device! [_ user-id device-info]
    (let [record (device/prepare-device-record user-id device-info (random-uuid) (java.util.Date.))]
      (execute-one! db
        (-> (h/insert-into :push-device-tokens)
            (h/values [(-> record
                          (dissoc :active?)
                          (assoc :active (:active? record))
                          (update :platform name))])
            (h/on-conflict :token :app-id)
            (h/do-update-set :active :last-used-at :device-name :os-version)))
      record))

  (unregister-device! [_ user-id device-token]
    (execute-one! db
      (-> (h/delete-from :push-device-tokens)
          (h/where [:and [:= :user-id user-id] [:= :token device-token]]))))

  (get-user-devices [_ user-id]
    (->> (execute! db
           (-> (h/select :*)
               (h/from :push-device-tokens)
               (h/where [:and [:= :user-id user-id] [:= :active true]])))
         (mapv row->device)))

  (get-devices-by-platform [_ platform opts]
    (let [{:keys [limit offset] :or {limit 100 offset 0}} opts]
      (->> (execute! db
             (-> (h/select :*)
                 (h/from :push-device-tokens)
                 (h/where [:and [:= :platform (name platform)] [:= :active true]])
                 (h/limit limit)
                 (h/offset offset)))
           (mapv row->device))))

  (mark-token-invalid! [_ device-token]
    (execute-one! db
      (-> (h/update :push-device-tokens)
          (h/set {:active false})
          (h/where [:= :token device-token]))))

  (cleanup-stale-tokens! [_ max-age-days]
    (let [cutoff (java.sql.Timestamp/from
                   (.minus (java.time.Instant/now)
                           (java.time.Duration/ofDays max-age-days)))]
      (execute-one! db
        (-> (h/delete-from :push-device-tokens)
            (h/where [:and [:= :active false] [:< :last-used-at cutoff]]))))))

(defn- row->analytics-event [{:keys [platform event-type] :as row}]
  (when row
    (-> row
        (update :platform keyword)
        (update :event-type keyword)
        (update :notification-id keyword))))

(defrecord PushAnalyticsStore [db]
  ports/IPushAnalyticsStore

  (record-send! [_ event]
    (execute-one! db
      (-> (h/insert-into :push-analytics-events)
          (h/values [{:id                  (or (:id event) (random-uuid))
                      :notification-id     (name (:notification-id event))
                      :device-token        (:device-token event)
                      :platform            (name (:platform event))
                      :event-type          (name (:event-type event))
                      :user-id             (:user-id event)
                      :provider-message-id (:provider-message-id event)
                      :error-message       (:error event)
                      :timestamp           (or (:timestamp event) (java.util.Date.))}]))))

  (record-delivery! [this event]
    (ports/record-send! this (assoc event :event-type :delivered)))

  (record-open! [this event]
    (ports/record-send! this (assoc event :event-type :opened)))

  (get-push-stats [_ notification-id _opts]
    (let [rows (execute! db
                 (-> (h/select :event-type [[:count :*] :cnt])
                     (h/from :push-analytics-events)
                     (h/where [:= :notification-id (name notification-id)])
                     (h/group-by :event-type)))
          counts (reduce (fn [m {:keys [event-type cnt]}]
                           (assoc m (keyword event-type) cnt))
                         {:sent 0 :delivered 0 :opened 0 :failed 0}
                         rows)]
      (assoc counts :notification-id notification-id)))

  (cleanup-old-events! [_ retention-days]
    (let [cutoff (java.sql.Timestamp/from
                   (.minus (java.time.Instant/now)
                           (java.time.Duration/ofDays retention-days)))]
      (execute-one! db
        (-> (h/delete-from :push-analytics-events)
            (h/where [:< :timestamp cutoff]))))))
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.push.shell.persistence-test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/push/src/boundary/push/shell/persistence.clj libs/push/test/boundary/push/shell/persistence_test.clj
git commit -m "feat(push): persistence — DeviceTokenStore + PushAnalyticsStore with H2 contract tests"
```

---

### Task 10: Mock Adapters

**Files:**
- Create: `libs/push/src/boundary/push/shell/adapters/mock.clj`

- [ ] **Step 1: Implement mock providers**

```clojure
(ns boundary.push.shell.adapters.mock
  (:require [boundary.push.ports :as ports]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(defrecord MockFCMProvider []
  ports/IFCMProvider

  (fcm-send! [_ payload]
    (let [token (get-in payload [:message :token])
          msg-id (str "mock-fcm-" (UUID/randomUUID))]
      (log/infof "Mock FCM: sent to %s → %s" token msg-id)
      {:success?   true
       :message-id msg-id
       :device-token token
       :platform   :fcm}))

  (fcm-send-multicast! [_ payload tokens]
    (log/infof "Mock FCM: multicast to %d tokens" (count tokens))
    (mapv (fn [token]
            {:success?     true
             :message-id   (str "mock-fcm-" (UUID/randomUUID))
             :device-token token
             :platform     :fcm})
          tokens))

  (fcm-validate-token [_ token]
    (log/infof "Mock FCM: validate token %s → valid" token)
    {:valid? true :token token}))

(defrecord MockAPNsProvider []
  ports/IAPNsProvider

  (apns-send! [_ payload device-token]
    (let [apns-id (str "mock-apns-" (UUID/randomUUID))]
      (log/infof "Mock APNs: sent to %s → %s" device-token apns-id)
      {:success?     true
       :apns-id      apns-id
       :message-id   apns-id
       :device-token device-token
       :platform     :apns}))

  (apns-send-batch! [_ payload device-tokens]
    (log/infof "Mock APNs: batch to %d tokens" (count device-tokens))
    (mapv (fn [token]
            {:success?     true
             :apns-id      (str "mock-apns-" (UUID/randomUUID))
             :message-id   (str "mock-apns-" (UUID/randomUUID))
             :device-token token
             :platform     :apns})
          device-tokens)))
```

- [ ] **Step 2: Verify it compiles**

```bash
clojure -M -e "(require 'boundary.push.shell.adapters.mock)"
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add libs/push/src/boundary/push/shell/adapters/mock.clj
git commit -m "feat(push): mock FCM + APNs adapters for dev/test"
```

---

### Task 11: FCM Adapter

**Files:**
- Create: `libs/push/src/boundary/push/shell/adapters/fcm.clj`

- [ ] **Step 1: Implement FCM provider**

Uses Google FCM v1 HTTP API with OAuth2 service account authentication.

```clojure
(ns boundary.push.shell.adapters.fcm
  (:require [boundary.push.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Instant Duration]
           [com.google.auth.oauth2 GoogleCredentials]
           [java.io FileInputStream]))

(defn- load-credentials [credentials-path]
  (-> (FileInputStream. ^String credentials-path)
      (GoogleCredentials/fromStream)
      (.createScoped ["https://www.googleapis.com/auth/firebase.messaging"])))

(defn- get-access-token [^GoogleCredentials credentials]
  (.refreshIfExpired credentials)
  (-> credentials .getAccessToken .getTokenValue))

(defn- fcm-url [project-id]
  (str "https://fcm.googleapis.com/v1/projects/" project-id "/messages:send"))

(defn- http-post [url body access-token]
  (let [client  (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " access-token))
                    (.POST (HttpRequest$BodyPublishers/ofString
                             (json/generate-string body)
                             StandardCharsets/UTF_8))
                    (.timeout (Duration/ofSeconds 10))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body   (json/parse-string (.body response) true)}))

(defrecord FCMProvider [project-id credentials]
  ports/IFCMProvider

  (fcm-send! [_ payload]
    (let [token        (get-in payload [:message :token])
          access-token (get-access-token credentials)
          response     (http-post (fcm-url project-id) payload access-token)]
      (if (<= 200 (:status response) 299)
        {:success?     true
         :message-id   (get-in response [:body :name])
         :device-token token
         :platform     :fcm}
        (let [error-code (get-in response [:body :error :status])]
          (log/warnf "FCM send failed: %s %s" (:status response) error-code)
          {:success?     false
           :device-token token
           :platform     :fcm
           :error        error-code
           :token-invalid? (contains? #{"UNREGISTERED" "INVALID_ARGUMENT"} error-code)}))))

  (fcm-send-multicast! [this payload tokens]
    (mapv (fn [token]
            (ports/fcm-send! this (assoc-in payload [:message :token] token)))
          tokens))

  (fcm-validate-token [this token]
    (let [payload {:message {:token token :data {"validate_only" "true"}}}
          result  (ports/fcm-send! this payload)]
      {:valid? (:success? result) :token token})))

(defn ->FCMProvider [project-id credentials-path]
  (let [creds (load-credentials credentials-path)]
    (->FCMProvider project-id creds)))
```

Note: `com.google.auth/google-auth-library-oauth2-http` and `cheshire` are already in `deps.edn` from Task 1.

- [ ] **Step 2: Commit**

```bash
git add libs/push/src/boundary/push/shell/adapters/fcm.clj
git commit -m "feat(push): FCM adapter — Google FCM v1 API with OAuth2 service account auth"
```

---

### Task 12: APNs Adapter

**Files:**
- Create: `libs/push/src/boundary/push/shell/adapters/apns.clj`

- [ ] **Step 1: Implement APNs provider**

Uses Apple APNs HTTP/2 API with JWT (token-based) authentication.

```clojure
(ns boundary.push.shell.adapters.apns
  (:require [boundary.push.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Paths]
           [java.security KeyFactory]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.time Duration Instant]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn- load-p8-key [key-path]
  (let [raw    (String. (Files/readAllBytes (Paths/get key-path (into-array String []))))
        pem    (-> raw
                   (.replace "-----BEGIN PRIVATE KEY-----" "")
                   (.replace "-----END PRIVATE KEY-----" "")
                   (.replaceAll "\\s" ""))
        decoded (.decode (Base64/getDecoder) pem)
        spec   (PKCS8EncodedKeySpec. decoded)]
    (.generatePrivate (KeyFactory/getInstance "EC") spec)))

(defn- base64url [^bytes bs]
  (.encodeToString (Base64/getUrlEncoder) bs))

(defn- make-jwt [team-id key-id private-key]
  (let [header  (json/generate-string {:alg "ES256" :kid key-id})
        now     (.getEpochSecond (Instant/now))
        payload (json/generate-string {:iss team-id :iat now})
        signing-input (str (base64url (.getBytes header StandardCharsets/UTF_8))
                           "." (base64url (.getBytes payload StandardCharsets/UTF_8)))
        sig     (let [signer (java.security.Signature/getInstance "SHA256withECDSA")]
                  (.initSign signer private-key)
                  (.update signer (.getBytes signing-input StandardCharsets/UTF_8))
                  (.sign signer))]
    (str signing-input "." (base64url sig))))

(defn- apns-host [sandbox?]
  (if sandbox?
    "https://api.sandbox.push.apple.com"
    "https://api.push.apple.com"))

(defn- send-single! [http-client host bundle-id jwt payload device-token]
  (let [url     (str host "/3/device/" device-token)
        body    (json/generate-string payload)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "bearer " jwt))
                    (.header "apns-topic" bundle-id)
                    (.header "apns-push-type" (if (= 1 (get-in payload [:aps :content-available]))
                                               "background" "alert"))
                    (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                    (.timeout (Duration/ofSeconds 10))
                    (.build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))]
    (if (<= 200 (.statusCode response) 299)
      (let [apns-id (or (.firstValue (.headers response) "apns-id") nil)]
        {:success?     true
         :apns-id      (when apns-id (.get apns-id))
         :message-id   (when apns-id (.get apns-id))
         :device-token device-token
         :platform     :apns})
      (let [body   (try (json/parse-string (.body response) true) (catch Exception _ {}))
            reason (:reason body)]
        (log/warnf "APNs send failed: %d %s" (.statusCode response) reason)
        {:success?       false
         :device-token   device-token
         :platform       :apns
         :error          reason
         :token-invalid? (contains? #{"BadDeviceToken" "Unregistered"} reason)}))))

(defrecord APNsProvider [team-id key-id private-key bundle-id sandbox? http-client]
  ports/IAPNsProvider

  (apns-send! [_ payload device-token]
    (let [jwt  (make-jwt team-id key-id private-key)
          host (apns-host sandbox?)]
      (send-single! http-client host bundle-id jwt payload device-token)))

  (apns-send-batch! [this payload device-tokens]
    (mapv (fn [token] (ports/apns-send! this payload token)) device-tokens)))

(defn ->APNsProvider [team-id key-id key-path bundle-id sandbox?]
  (let [pk     (load-p8-key key-path)
        client (-> (HttpClient/newBuilder)
                   (.version HttpClient$Version/HTTP_2)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))]
    (->APNsProvider team-id key-id pk bundle-id sandbox? client)))
```

- [ ] **Step 2: Commit**

```bash
git add libs/push/src/boundary/push/shell/adapters/apns.clj
git commit -m "feat(push): APNs adapter — HTTP/2 with JWT token-based auth"
```

---

### Task 13: Push Service (Shell Orchestrator)

**Files:**
- Create: `libs/push/src/boundary/push/shell/service.clj`
- Create: `libs/push/test/boundary/push/shell/service_test.clj`

- [ ] **Step 1: Write integration tests for service**

```clojure
(ns boundary.push.shell.service-test
  (:require [clojure.test :refer :all]
            [boundary.push.shell.service :as service]
            [boundary.push.shell.adapters.mock :as mock]
            [boundary.push.shell.persistence :as p]
            [boundary.push.shell.persistence-test :as pt]
            [boundary.push.core.notification :as notif]
            [boundary.push.ports :as ports]
            [boundary.jobs.ports]))

(use-fixtures :each
  (fn [f]
    (notif/clear-registry!)
    (binding [pt/*db* (pt/create-test-db)]
      (f))))

(deftest ^:integration send-push-enqueues-job
  (notif/register-push!
    {:id :test-push :title "Hello" :body "World" :channels #{:fcm}})

  (let [jobs-atom    (atom [])
        mock-queue   (reify boundary.jobs.ports/IJobQueue
                       (enqueue-job! [_ queue-name job]
                         (swap! jobs-atom conj {:queue queue-name :job job})
                         (:id job)))
        device-store (p/->DeviceTokenStore pt/*db*)
        analytics    (p/->PushAnalyticsStore pt/*db*)
        svc          (service/->PushService
                       device-store analytics
                       (mock/->MockFCMProvider)
                       (mock/->MockAPNsProvider)
                       mock-queue
                       "test-callback-secret")]
    (ports/send-push! svc :test-push {:order-id "1"} {:user-id (random-uuid) :locale :en})
    (is (= 1 (count @jobs-atom)))
    (is (= :push/send (:job-type (:job (first @jobs-atom)))))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.push.shell.service-test
```

Expected: FAIL

- [ ] **Step 3: Implement push service**

```clojure
(ns boundary.push.shell.service
  (:require [boundary.push.ports :as ports]
            [boundary.push.core.notification :as notif]
            [boundary.push.core.delivery :as delivery]
            [boundary.jobs.ports :as job-ports]
            [clojure.tools.logging :as log])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.nio.charset StandardCharsets]))

(defn- hmac-sha256 [secret data]
  (let [mac (Mac/getInstance "HmacSHA256")
        key (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8) "HmacSHA256")]
    (.init mac key)
    (apply str (map #(format "%02x" (bit-and % 0xff))
                    (.doFinal mac (.getBytes ^String data StandardCharsets/UTF_8))))))

(defn generate-callback-token
  "Generate HMAC callback token for a provider-message-id."
  [callback-secret provider-message-id]
  (hmac-sha256 callback-secret provider-message-id))

(defn verify-callback-token
  "Verify HMAC callback token. Constant-time comparison."
  [callback-secret provider-message-id callback-token]
  (let [expected (hmac-sha256 callback-secret provider-message-id)]
    (java.security.MessageDigest/isEqual
      (.getBytes ^String expected StandardCharsets/UTF_8)
      (.getBytes ^String callback-token StandardCharsets/UTF_8))))

(defn deliver-to-platform!
  "Internal: send notification to devices on a specific platform."
  [{:keys [fcm-provider apns-provider]} platform notification devices callback-secret]
  (case platform
    :fcm  (let [tokens (mapv :token devices)]
            (ports/fcm-send-multicast!
              fcm-provider
              (delivery/build-fcm-payload notification (first tokens))
              tokens))
    :apns (ports/apns-send-batch!
            apns-provider
            (delivery/build-apns-payload notification)
            (mapv :token devices))))

(defrecord PushService [device-store analytics-store
                        fcm-provider apns-provider
                        job-queue callback-secret]
  ports/IPushService

  (send-push! [_ notification-id data opts]
    (let [job {:id       (random-uuid)
               :job-type :push/send
               :args     {:notification-id notification-id
                          :data            data
                          :user-id         (:user-id opts)
                          :locale          (:locale opts)}}]
      (log/infof "Push: enqueueing %s for user %s" notification-id (:user-id opts))
      (job-ports/enqueue-job! job-queue :push job)
      (:id job)))

  (schedule-push! [_ notification-id data opts scheduled-at]
    (let [job {:id           (random-uuid)
               :job-type     :push/send
               :args         {:notification-id notification-id
                              :data            data
                              :user-id         (:user-id opts)
                              :locale          (:locale opts)}
               :scheduled-at scheduled-at}]
      (log/infof "Push: scheduling %s for %s" notification-id scheduled-at)
      (job-ports/enqueue-job! job-queue :push job)
      (:id job)))

  (broadcast! [_ notification-id data opts]
    (let [job {:id       (random-uuid)
               :job-type :push/broadcast
               :args     {:notification-id notification-id
                          :data            data
                          :platform        (:platform opts)
                          :app-id          (:app-id opts)
                          :locale          (:locale opts)}}]
      (log/infof "Push: enqueueing broadcast %s" notification-id)
      (job-ports/enqueue-job! job-queue :push job)
      (:id job))))
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.push.shell.service-test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/push/src/boundary/push/shell/service.clj libs/push/test/boundary/push/shell/service_test.clj
git commit -m "feat(push): PushService orchestrator with HMAC callback tokens and job enqueueing"
```

---

### Task 14: Job Handlers

**Files:**
- Create: `libs/push/src/boundary/push/shell/jobs.clj`

- [ ] **Step 1: Implement job handlers**

```clojure
(ns boundary.push.shell.jobs
  (:require [boundary.push.core.notification :as notif]
            [boundary.push.core.delivery :as delivery]
            [boundary.push.shell.service :as service]
            [boundary.push.ports :as ports]
            [clojure.tools.logging :as log]))

(defn handle-send-push
  "Job handler for :push/send. Resolves notification, fans out to devices, delivers."
  [{:keys [push-service device-store fcm-provider apns-provider
           analytics-store callback-secret]}
   {:keys [notification-id data user-id locale]}]
  (let [push-def (notif/get-push notification-id)]
    (when-not push-def
      (throw (ex-info "Push notification not found in registry"
                      {:notification-id notification-id})))
    (let [devices   (ports/get-user-devices device-store user-id)
          active    (filter :active? devices)
          rendered  (notif/build-notification push-def data (or locale :en))
          grouped   (delivery/group-devices-by-platform active)]
      (log/infof "Push: delivering %s to %d devices for user %s"
                 notification-id (count active) user-id)
      (doseq [[platform platform-devices] grouped]
        (let [results (service/deliver-to-platform!
                        {:fcm-provider fcm-provider :apns-provider apns-provider}
                        platform rendered platform-devices callback-secret)]
          (doseq [result results]
            (ports/record-send! analytics-store
              (merge (delivery/result->analytics-event notification-id result (java.util.Date.))
                     {:id (random-uuid) :user-id user-id}))
            (when (:token-invalid? result)
              (log/infof "Push: marking invalid token %s" (:device-token result))
              (ports/mark-token-invalid! device-store (:device-token result)))))))))

(defn handle-broadcast
  "Job handler for :push/broadcast. Paginated send to all devices on platform."
  [{:keys [device-store] :as deps}
   {:keys [notification-id data platform app-id locale]}]
  (let [push-def  (notif/get-push notification-id)
        rendered  (notif/build-notification push-def data (or locale :en))
        page-size 500]
    (log/infof "Push: broadcasting %s to platform %s" notification-id platform)
    (loop [offset 0]
      (let [devices (ports/get-devices-by-platform device-store platform
                      {:limit page-size :offset offset})]
        (when (seq devices)
          (let [results (service/deliver-to-platform!
                          deps platform rendered devices (:callback-secret deps))]
            (doseq [result results]
              (ports/record-send! (:analytics-store deps)
                (merge (delivery/result->analytics-event notification-id result (java.util.Date.))
                       {:id (random-uuid)}))
              (when (:token-invalid? result)
                (ports/mark-token-invalid! device-store (:device-token result)))))
          (when (= page-size (count devices))
            (recur (+ offset page-size))))))))
```

- [ ] **Step 2: Commit**

```bash
git add libs/push/src/boundary/push/shell/jobs.clj
git commit -m "feat(push): job handlers for send and broadcast with paginated delivery"
```

---

### Task 15: HTTP Handlers & Routes

**Files:**
- Create: `libs/push/src/boundary/push/shell/handlers.clj`

- [ ] **Step 1: Implement handlers**

```clojure
(ns boundary.push.shell.handlers
  (:require [boundary.push.ports :as ports]
            [boundary.push.schema :as schema]
            [boundary.push.shell.service :as service]
            [boundary.push.core.analytics :as analytics]
            [malli.core :as m]
            [ring.util.response :as resp]))

(defn register-device-handler
  [{:keys [device-store]} request]
  (let [user-id (get-in request [:identity :user-id])
        body    (:body-params request)]
    (if-not (schema/valid-device-info? body)
      (resp/bad-request {:errors (m/explain schema/DeviceInfo body)})
      (let [device (ports/register-device! device-store user-id body)]
        (-> (resp/created (str "/api/push/devices/" (:id device)) device)
            (resp/content-type "application/json"))))))

(defn unregister-device-handler
  [{:keys [device-store]} request]
  (let [user-id (get-in request [:identity :user-id])
        token   (get-in request [:path-params :token])]
    (ports/unregister-device! device-store user-id token)
    {:status 204 :headers {} :body nil}))

(defn list-devices-handler
  [{:keys [device-store]} request]
  (let [user-id (get-in request [:identity :user-id])
        devices (ports/get-user-devices device-store user-id)]
    (resp/response {:devices devices})))

(defn analytics-callback-handler
  [{:keys [analytics-store callback-secret]} request]
  (let [body (:body-params request)]
    (cond
      (not (schema/valid-callback? body))
      (resp/bad-request {:errors "Invalid callback payload"})

      (not (service/verify-callback-token
             callback-secret
             (:provider-message-id body)
             (:callback-token body)))
      (-> (resp/response {:error "Invalid callback token"})
          (resp/status 403))

      :else
      (do
        (let [event {:id                  (random-uuid)
                     :notification-id     (keyword "callback")
                     :device-token        (:device-token body)
                     :platform            :unknown
                     :provider-message-id (:provider-message-id body)
                     :timestamp           (or (:timestamp body) (java.util.Date.))}]
          (case (:event-type body)
            :delivered (ports/record-delivery! analytics-store event)
            :opened    (ports/record-open! analytics-store event)))
        {:status 204 :headers {} :body nil}))))

(defn push-stats-handler
  [{:keys [analytics-store]} request]
  (let [notif-id (keyword (get-in request [:path-params :notification-id]))
        stats    (ports/get-push-stats analytics-store notif-id {})]
    (resp/response (analytics/calculate-rates stats))))

(defn push-routes [deps]
  ["/api/push"
   ["/devices"      {:post   (partial register-device-handler deps)
                     :get    (partial list-devices-handler deps)}]
   ["/devices/:token" {:delete (partial unregister-device-handler deps)}]
   ["/callback"      {:post   (partial analytics-callback-handler deps)}]
   ["/stats/:notification-id" {:get (partial push-stats-handler deps)}]])
```

- [ ] **Step 2: Commit**

```bash
git add libs/push/src/boundary/push/shell/handlers.clj
git commit -m "feat(push): Ring handlers — device CRUD, HMAC-secured callback, stats endpoint"
```

---

### Task 16: Integrant Module Wiring

**Files:**
- Create: `libs/push/src/boundary/push/shell/module_wiring.clj`

- [ ] **Step 1: Implement wiring**

```clojure
(ns boundary.push.shell.module-wiring
  (:require [boundary.push.shell.service :as service]
            [boundary.push.shell.persistence :as persistence]
            [boundary.push.shell.adapters.mock :as mock]
            [boundary.push.shell.adapters.fcm :as fcm]
            [boundary.push.shell.adapters.apns :as apns]
            [boundary.push.shell.handlers :as handlers]
            [boundary.push.shell.jobs :as jobs]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :boundary.push/fcm-provider
  [_ {:keys [provider project-id credentials-path]}]
  (case (or provider :mock)
    :mock (do (log/info "Push: using mock FCM provider")
              (mock/->MockFCMProvider))
    :fcm  (do (log/info "Push: initializing FCM provider" {:project-id project-id})
              (fcm/->FCMProvider project-id credentials-path))))

(defmethod ig/init-key :boundary.push/apns-provider
  [_ {:keys [provider team-id key-id key-path bundle-id sandbox?]}]
  (case (or provider :mock)
    :mock (do (log/info "Push: using mock APNs provider")
              (mock/->MockAPNsProvider))
    :apns (do (log/info "Push: initializing APNs provider" {:team-id team-id :sandbox? sandbox?})
              (apns/->APNsProvider team-id key-id key-path bundle-id sandbox?))))

(defmethod ig/init-key :boundary.push/device-store
  [_ {:keys [db]}]
  (log/info "Push: initializing device token store")
  (persistence/->DeviceTokenStore db))

(defmethod ig/init-key :boundary.push/analytics-store
  [_ {:keys [db]}]
  (log/info "Push: initializing analytics store")
  (persistence/->PushAnalyticsStore db))

(defmethod ig/init-key :boundary.push/service
  [_ {:keys [device-store analytics-store fcm-provider apns-provider job-queue callback-secret]}]
  (log/info "Push: initializing push service")
  (service/->PushService device-store analytics-store fcm-provider apns-provider job-queue callback-secret))

(defmethod ig/init-key :boundary.push/job-handlers
  [_ {:keys [push-service job-registry]}]
  (let [deps {:push-service    push-service
              :device-store    (:device-store push-service)
              :fcm-provider    (:fcm-provider push-service)
              :apns-provider   (:apns-provider push-service)
              :analytics-store (:analytics-store push-service)
              :callback-secret (:callback-secret push-service)}]
    (log/info "Push: registering job handlers")
    {:push/send      (partial jobs/handle-send-push deps)
     :push/broadcast (partial jobs/handle-broadcast deps)}))

(defmethod ig/init-key :boundary.push/routes
  [_ {:keys [device-store analytics-store callback-secret]}]
  (handlers/push-routes {:device-store    device-store
                         :analytics-store analytics-store
                         :callback-secret callback-secret}))
```

- [ ] **Step 2: Commit**

```bash
git add libs/push/src/boundary/push/shell/module_wiring.clj
git commit -m "feat(push): Integrant wiring — providers, stores, service, job handlers, routes"
```

---

### Task 17: AGENTS.md

**Files:**
- Create: `libs/push/AGENTS.md`

- [ ] **Step 1: Write AGENTS.md**

```markdown
# boundary-push — Push Notification Library

Multi-platform push notification delivery for FCM (Firebase) and APNs (Apple). Device token management, job-based async delivery, HMAC-secured analytics callbacks. Uses `defpush` macro for notification definitions.

## Key Namespaces

| Namespace | Layer | Purpose |
|-----------|-------|---------|
| `boundary.push.core.notification` | Core | `defpush` macro, registry, template rendering |
| `boundary.push.core.delivery` | Core | Payload building (FCM/APNs), error classification, retry calc |
| `boundary.push.core.device` | Core | Token validation, platform detection, staleness |
| `boundary.push.core.analytics` | Core | Rate calculations |
| `boundary.push.ports` | Ports | IPushService, IFCMProvider, IAPNsProvider, IDeviceTokenStore, IPushAnalyticsStore |
| `boundary.push.schema` | Schema | Malli schemas for all domain types |
| `boundary.push.shell.service` | Shell | IPushService impl, HMAC generation/verification |
| `boundary.push.shell.persistence` | Shell | next.jdbc/HoneySQL stores for devices + analytics |
| `boundary.push.shell.adapters.mock` | Shell | Mock FCM + APNs (dev/test) |
| `boundary.push.shell.adapters.fcm` | Shell | Google FCM v1 API adapter |
| `boundary.push.shell.adapters.apns` | Shell | Apple APNs HTTP/2 adapter |
| `boundary.push.shell.handlers` | Shell | Ring handlers for device CRUD + callback |
| `boundary.push.shell.jobs` | Shell | Job handlers for async delivery |
| `boundary.push.shell.module-wiring` | Shell | Integrant lifecycle |

## Protocol: IFCMProvider

```clojure
(fcm-send! [this payload])           ;; Single send, returns {:success? :message-id :error}
(fcm-send-multicast! [this payload tokens])  ;; Batch send
(fcm-validate-token [this token])    ;; Dry-run validation
```

## Protocol: IAPNsProvider

```clojure
(apns-send! [this payload device-token])    ;; Single send
(apns-send-batch! [this payload tokens])    ;; Sequential batch
```

Note: APNs has no dry-run validation equivalent. Token validity discovered at send time.

## Notification Definition

```clojure
(defpush order-shipped
  {:id           :order-shipped
   :title        {:en "Order Shipped" :nl "Bestelling Verzonden"}
   :body         {:en "Your order {{order-id}} is on its way!"}
   :channels     #{:fcm :apns}
   :priority     :high
   :ttl          3600
   :deep-link    "/orders/{{order-id}}"
   :silent?      false
   :collapse-key :order-status
   :retry        {:max-attempts 3 :backoff :exponential}})
```

## Sending

```clojure
;; Direct (via job queue)
(push/send-push! push-service :order-shipped
  {:order-id "ORD-123"} {:user-id user-id :locale :nl})

;; Scheduled
(push/schedule-push! push-service :appointment-reminder
  {:time "14:00"} {:user-id user-id} future-instant)

;; Broadcast
(push/broadcast! push-service :maintenance-alert
  {:message "Downtime at 2am"} {:platform :fcm})
```

## Error Classification

| Category | Action |
|----------|--------|
| `:retryable` | Re-enqueue with backoff |
| `:token-invalid` | Deactivate token, don't retry |
| `:rate-limited` | Re-enqueue with longer backoff |
| `:permanent` | Log, don't retry |

## Gotchas

1. **FCM tokens rotate** — always handle `UNREGISTERED` by marking token invalid
2. **APNs sandbox vs production** — different hosts, set `sandbox?` correctly per environment
3. **Callback HMAC** — mobile apps must send back `callback-token` from push data payload
4. **Template variables** — `{{var}}` syntax, missing vars left as-is (not stripped)
5. **Locale fallback** — requested → `:en` → first available
6. **push_send_log is write-once** — post-send states (delivered/opened) only in analytics events table

## Testing

```bash
clojure -M:test:db/h2 :push                              # All
clojure -M:test:db/h2 :push --focus-meta :unit            # Unit
clojure -M:test:db/h2 :push --focus-meta :contract        # Contract (H2)
clojure -M:test:db/h2 :push --focus-meta :integration     # Integration
```
```

- [ ] **Step 2: Commit**

```bash
git add libs/push/AGENTS.md
git commit -m "docs(push): add AGENTS.md development guide"
```

---

### Task 18: Final Verification

- [ ] **Step 1: Run all push tests**

```bash
clojure -M:test:db/h2 :push
```

Expected: all tests pass.

- [ ] **Step 2: Run linter**

```bash
clojure -M:clj-kondo --lint libs/push/src libs/push/test
```

Expected: no errors.

- [ ] **Step 3: Run FC/IS enforcement check**

```bash
bb check:fcis
```

Expected: no violations (core/ must not import shell/IO/logging/DB).

- [ ] **Step 4: Fix any issues found, commit**

```bash
git add -A libs/push/
git commit -m "fix(push): address linting and quality gate issues"
```

- [ ] **Step 5: Run paren repair on all source files**

```bash
for f in $(find libs/push/src libs/push/test -name '*.clj'); do clj-paren-repair "$f"; done
```

- [ ] **Step 6: Final commit if paren repairs needed**

```bash
git add libs/push/ && git commit -m "fix(push): paren repair" || echo "No changes"
```
