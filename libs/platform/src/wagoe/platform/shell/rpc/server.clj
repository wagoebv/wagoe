(ns wagoe.platform.shell.rpc.server
  "Expose a module's protocol over HTTP, so another process can call it.

   The counterpart to `…rpc.client`: that turns protocol calls into requests,
   this turns requests back into protocol calls against the local
   implementation. Together they are what lets a module run as its own service
   without its callers changing (BOU-90).

   FC/IS: shell. The envelope contract is pure and lives in
   `wagoe.platform.core.rpc`."
  (:require [wagoe.platform.core.rpc :as rpc]
            [clojure.tools.logging :as log]))

(defn- operation->fn
  "The protocol function implementing `operation`, or nil.

   Resolved from the protocol's `:sigs`, so an endpoint exposes exactly the
   methods its protocol declares — no more. An operation the protocol does not
   name cannot be invoked, which is what stops the envelope from becoming a
   general-purpose remote eval."
  [protocol operation]
  (some (fn [{:keys [name]}]
          (when (= (clojure.core/name operation) (clojure.core/name name))
            (ns-resolve (-> protocol :var meta :ns)
                        name)))
        (vals (:sigs protocol))))

(defn handle-envelope
  "Invoke one envelope against `implementation`. Returns a response envelope.

   Errors are returned, never thrown: the client reads status and body, and an
   exception escaping here would surface as a 500 with no operation named — the
   caller would know something failed but not what it had asked for."
  [protocol implementation {:keys [operation args correlation-id] :as _envelope}]
  (if-let [f (operation->fn protocol operation)]
    (try
      (let [result (apply f implementation args)]
        (cond-> {:result result}
          correlation-id (assoc :correlation-id correlation-id)))
      (catch Exception e
        (log/warn e "rpc operation threw" {:operation operation})
        (cond-> {:error {:type      :rpc/remote-error
                         :message   (or (.getMessage e) (str (class e)))
                         :operation (keyword (name operation))}}
          correlation-id (assoc :correlation-id correlation-id))))
    (do
      (log/warn "rpc unknown operation" {:operation operation})
      {:error {:type      :rpc/unknown-operation
               :message   (str "This service exposes no operation " operation)
               :operation (when operation (keyword (name operation)))}})))

(defn rpc-handler
  "A Ring handler serving `protocol` backed by `implementation`.

   Mount at `/rpc` — `…rpc.client` posts there.

   The context an inbound request carries (correlation-id, tenant, auth) is
   read from the headers and merged onto the envelope, so a downstream call
   made while handling this one keeps the same correlation-id rather than
   starting a fresh trace.

   Args:
     protocol       - the protocol map, e.g. wagoe.payments.ports/IPaymentProvider
     implementation - a value satisfying it, in this process

   Example:
     {:path \"/rpc\"
      :methods {:post {:handler (rpc-handler payments-ports/IPaymentProvider provider)}}}"
  [protocol implementation]
  (fn [{:keys [body-params headers] :as _request}]
    (let [envelope (merge (rpc/headers->context headers) body-params)
          response (handle-envelope protocol implementation envelope)]
      {:status  (if (:error response) 500 200)
       :headers {"content-type" "application/json"}
       :body    response})))
