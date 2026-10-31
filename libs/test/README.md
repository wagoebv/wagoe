# wagoe-test

Integration tests for a Wagoe application, without reverse-engineering its
wiring. Boots the app's own system on the `test` profile, keeps every adapter
in process, and makes the asynchronous parts deterministic.

```clojure
;; deps.edn
:test {:extra-deps {com.wagoe/wagoe-test {:mvn/version "1.1.1"}}}
```

```clojure
(ns my-app.orders-test
  (:require [clojure.test :refer [deftest is]]
            [my-app.system-config :as app]
            [wagoe.test :as t]))

(deftest placing-an-order
  (t/with-test-system [sys app/ig-config]
    (let [c (t/client sys)]
      (t/sign-in-as! c {:roles [:orders/editor]})
      (is (= 201 (:status (t/request c :post "/api/v1/orders" {:json {:sku "x"}}))))
      (is (= 1 (t/run-pending-jobs! sys)))
      (is (= [:order/placed] (map :type (t/drain-events! sys :orders)))))))
```

| Helper | What it does |
|---|---|
| `with-test-system`, `start-system`, `stop-system` | Boot the app's `ig-config` on its test config. No port is bound; each system gets its own H2 database. Cache, events, realtime and jobs run in memory, jobs with no worker threads. Pass `{:ig-config f :config tweak}` to change the config first. |
| `client`, `request` | A Ring client on the app's handler. Keeps cookies and sends a valid CSRF token with every POST/PUT/PATCH/DELETE. `request` takes `:form`, `:json`, `:body`, `:query`, `:headers`. |
| `sign-in!` | Signs in through `/web/login`; throws if no session came back. |
| `create-user!`, `sign-in-as!` | Registers a user with `:roles` on a group of their own, each a keyword or `{:role kw :scope map}`, and returns it with its `:password`. `sign-in-as!` signs the client in as them. A new app runs `:wagoe/authorization :enforce`, so a user without a role gets 403. |
| `with-test-tenant`, `call-with-tenant` | A tenant for the body, deleted with its schema afterwards. Send `{"x-tenant-slug" (:slug tenant)}` via `(client sys {:headers …})`. |
| `run-pending-jobs!` | Runs every due job on the test's thread, including jobs those jobs enqueue. |
| `drain-events!` | Events published to a topic since the last drain, after every subscriber has handled them. |
| `drain-realtime!` | Realtime envelopes sent since the last drain: `{:route :target :message}`. |

clj-kondo: the jar exports `:lint-as` for both macros.
