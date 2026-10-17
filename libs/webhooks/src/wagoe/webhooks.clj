(ns wagoe.webhooks
  "What a receiver needs to check a Wagoe webhook, with no other part of the
   library loaded.

     (webhooks/verify-signature secret raw-body
                                (get-in request [:headers \"wagoe-signature\"]))

   Verify against the raw body, before parsing it: re-serialised JSON is not
   the bytes that were signed. Use the `Wagoe-Event-Id` header to drop
   deliveries already handled — a retry after a lost response sends the same
   event again."
  (:require [wagoe.webhooks.core.signature :as signature])
  (:import (java.time Instant)))

(defn sign
  "The `Wagoe-Signature` header value for `body` under `secret`, now or at
   `timestamp` (unix seconds)."
  ([secret body] (sign secret body (.getEpochSecond (Instant/now))))
  ([secret body timestamp] (signature/signature-header secret timestamp body)))

(defn signature-problem
  "Why `header` does not authenticate `body`, or nil: `:malformed`, `:stale`
   or `:mismatch`. `opts`: `:tolerance-s` (default 300), `:now` (unix seconds)."
  ([secret body header] (signature-problem secret body header {}))
  ([secret body header {:keys [now] :as opts}]
   (signature/signature-problem secret body header
                                (or now (.getEpochSecond (Instant/now)))
                                opts)))

(defn verify-signature
  "True when `header` is a valid, fresh signature of `body` under `secret`."
  ([secret body header] (verify-signature secret body header {}))
  ([secret body header opts]
   (nil? (signature-problem secret body header opts))))
