(ns wagoe.email.shell.adapters.queue
  "In-memory email queue implementing `EmailQueueProtocol`.

   A lightweight, single-process queued-sending mode: `queue-email!` enqueues,
   `process-queue!` drains one entry through the wrapped sender, with bounded
   retry (failed sends are re-queued up to `:max-retries` times). Backed by a
   thread-safe `ConcurrentLinkedDeque`.

   For distributed queuing across replicas use `jobs-integration` (jobs module)
   instead — this adapter's state is per-process and lost on restart."
  (:require [wagoe.email.ports :as ports]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent ConcurrentLinkedDeque]))

(defrecord InMemoryEmailQueue [sender ^ConcurrentLinkedDeque queue max-retries]
  ports/EmailQueueProtocol

  (queue-email! [_ email]
    (let [id    (or (:id email) (random-uuid))
          entry (assoc email :id id :retry-count (or (:retry-count email) 0))]
      (.addLast queue entry)
      {:queued? true :queue-id id :position (.size queue)}))

  (process-queue! [_]
    (if-let [email (.pollFirst queue)]
      (let [result (ports/send-email! sender email)]
        (if (:success? result)
          {:processed? true :email-id (:id email) :send-result result}
          (let [rc (inc (long (:retry-count email 0)))]
            (if (<= rc max-retries)
              (do
                (.addLast queue (assoc email :retry-count rc))
                (log/warn "Email send failed; re-queued for retry"
                          {:email-id (:id email) :attempt rc :max-retries max-retries})
                {:processed? true :email-id (:id email) :send-result result :retrying? true})
              {:processed?   true
               :email-id     (:id email)
               :send-result  result
               :error        {:message "Max retries exhausted" :type :max-retries-exhausted}}))))
      {:processed? false}))

  (queue-size [_] (.size queue))
  (peek-queue [_] (.peekFirst queue)))

(defn create-in-memory-queue
  "Create an in-memory email queue.

   Options:
   - :sender      - an EmailSenderProtocol implementation (required)
   - :max-retries - re-queue attempts on send failure (default 3)"
  [{:keys [sender max-retries] :or {max-retries 3}}]
  (when-not sender
    (throw (ex-info "email queue requires a :sender"
                    {:type :validation-error})))
  (->InMemoryEmailQueue sender (ConcurrentLinkedDeque.) max-retries))
