(ns wagoe.search.shell.module-wiring-test
  "The search component picks its strategies from the adapter's capabilities
   (ADR-039)."
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.platform.shell.adapters.database.postgresql.core :as postgresql]
            [wagoe.search.shell.module-wiring]))

(defn- store-for [adapter]
  (select-keys (:store (ig/init-key :wagoe/search {:db-ctx {:adapter adapter :datasource ::unused}}))
               [:full-text? :on-conflict?]))

(deftest ^:unit strategies-follow-the-adapters-capabilities
  (is (= {:full-text? true :on-conflict? true} (store-for (postgresql/new-adapter))))
  (is (= {:full-text? false :on-conflict? false} (store-for (h2/new-adapter)))))
