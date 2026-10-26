(ns wagoe.push.shell.adapters.concurrent
  "Per-device sends over one HTTP client, shared by the FCM and APNs adapters."
  (:require [wagoe.push.core.device :as device]
            [clojure.tools.logging :as log])
  (:import [java.net.http HttpClient HttpResponse$BodyHandlers]
           [java.util.concurrent CompletableFuture]))

(defn send-all
  "Each `[token payload]` sent concurrently as `(request token payload)`, each
   response read by `(parse response token)`; per-token results, in order. A
   send that throws is a failed result for `platform`, not an exception."
  [^HttpClient http-client platform request parse sends]
  (let [futures (mapv (fn [[token payload]]
                        [token (.sendAsync http-client (request token payload)
                                           (HttpResponse$BodyHandlers/ofString))])
                      sends)]
    (mapv (fn [[token ^CompletableFuture future]]
            (try
              (parse (.get future) token)
              (catch Exception e
                (log/warnf "%s async send failed for %s: %s"
                           (if (= :fcm platform) "FCM" "APNs")
                           (device/token-preview token) (.getMessage e))
                {:success?     false
                 :device-token token
                 :platform     platform
                 :error        {:type :push-send-failed :message (.getMessage e)}})))
          futures)))
