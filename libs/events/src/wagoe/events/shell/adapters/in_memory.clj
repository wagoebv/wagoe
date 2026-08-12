(ns wagoe.events.shell.adapters.in-memory
  "An event bus inside one process.

   For development, for tests, and for an application that has modules but not
   yet servers. It implements the same protocols as the Redis adapter so that
   moving between them is configuration — but it is not a smaller version of
   it: events do not leave the process, and history dies with it.

   Delivery is asynchronous here even though it need not be. A bus that
   delivers on the publisher's thread lets a caller depend on the handler
   having finished by the time `publish!` returns, and that assumption then
   fails silently against Redis. Matching the weaker guarantee is what makes
   the adapters interchangeable."
  (:require [wagoe.events.core.event :as event]
            [wagoe.events.ports :as ports]
            [clojure.tools.logging :as log])
  (:import (java.util.concurrent Executors ExecutorService TimeUnit)))

(def ^:private default-history-limit
  "Events kept per topic. Bounded because this is a process-local buffer: an
   unbounded one is a memory leak that only shows up in the longest-running
   deployment."
  1000)

(defn- deliver-to
  [handler event]
  (try
    (handler event)
    (catch Throwable t
      ;; Logged and swallowed. A throwing handler must not take down the
      ;; delivery thread — the next event, and every other subscriber, would
      ;; be lost with it.
      (log/warn t "event handler threw" {:type (:type event) :id (:id event)}))))

(defrecord InMemoryEventBus [state ^ExecutorService executor history-limit]
  ports/IEventPublisher
  (publish! [_ topic event]
    (if-let [problem (or (event/topic-problem topic) (event/event-problem event))]
      {:error {:type :events/invalid :message problem}}
      (let [topic (keyword topic)]
        (swap! state update-in [:history topic]
               (fn [events]
                 (->> (conj (vec events) event)
                      (take-last history-limit)
                      vec)))
        (doseq [[_ {:keys [topic' handler]}] (:subscriptions @state)
                :when (= topic' topic)]
          (.submit executor ^Runnable (fn [] (deliver-to handler event))))
        (:id event))))

  ports/IEventSubscriber
  (subscribe! [_ topic handler]
    (if-let [problem (event/topic-problem topic)]
      {:error {:type :events/invalid :message problem}}
      (let [id (str (random-uuid))]
        (swap! state assoc-in [:subscriptions id] {:topic' (keyword topic) :handler handler})
        id)))

  (unsubscribe! [_ subscription]
    (swap! state update :subscriptions dissoc subscription)
    nil)

  ports/IEventHistory
  (history [this topic] (ports/history this topic {}))
  (history [_ topic {:keys [limit since]}]
    (cond->> (get-in @state [:history (keyword topic)] [])
      ;; inst-ms rather than Instant interop: `:published-at` satisfies inst?,
      ;; which is java.util.Date or java.time.Instant depending on who built
      ;; the event, and only one of them has .isAfter.
      since (filter #(> (inst-ms (:published-at %)) (inst-ms since)))
      limit (take-last limit)
      true  vec)))

(defn create-in-memory-bus
  "An event bus confined to this process.

   `opts` may carry `:history-limit` (default 1000)."
  [& [{:keys [history-limit]}]]
  (->InMemoryEventBus (atom {:history {} :subscriptions {}})
                      (Executors/newSingleThreadExecutor)
                      (or history-limit default-history-limit)))

(defn stop!
  "Stop delivering and release the thread.

   Waits briefly for events already accepted: dropping them on shutdown would
   make a clean stop lossier than a crash, which is the wrong way round."
  [{:keys [^ExecutorService executor]}]
  (when executor
    (.shutdown executor)
    (when-not (.awaitTermination executor 2 TimeUnit/SECONDS)
      (log/warn "event bus did not drain within 2s; dropping what is left")
      (.shutdownNow executor))))
