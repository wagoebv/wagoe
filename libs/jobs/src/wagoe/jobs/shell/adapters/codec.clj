(ns wagoe.jobs.shell.adapters.codec
  "How the :db and :redis queues store a job: transit, so a handler gets back
   the keywords, UUIDs and instants it was given, as the in-memory queue does.
   They stored JSON until BOU-689, and jobs written then still decode."
  (:require [cheshire.core :as json]
            [wagoe.platform.transit :as transit])
  (:import [java.time Instant]))

(defn- legacy-json->job
  "A job written as JSON before BOU-689. Only the job's own fields are typed
   again; keywords in `:args` stay the strings JSON made them."
  [s]
  (letfn [(kw [v] (when v (keyword v)))
          (instant [v] (when v (Instant/ofEpochMilli v)))]
    (-> (json/parse-string s true)
        (update :id #(java.util.UUID/fromString %))
        (update :job-type kw)
        (update :status kw)
        (update :queue kw)
        (update :priority kw)
        (update :execute-at instant)
        (update :created-at instant)
        (update :updated-at instant)
        (update :started-at instant)
        (update :completed-at instant)
        ;; :error :type is a keyword too (ADR-036 §3). Jobs stored before that
        ;; migration carry the old spelling ("NoHandlerError" -> :NoHandlerError).
        (update :error #(when % (cond-> % (:type %) (update :type keyword)))))))

(defn encode-job
  "`job` as the string the queue stores."
  [job]
  (transit/encode job))

(defn decode-job
  "The job `s` holds. Transit JSON at the top level is an array, so a string
   opening with `{` is a JSON job from before BOU-689."
  [^String s]
  (when s
    (if (.startsWith (.stripLeading s) "{")
      (legacy-json->job s)
      (transit/decode s))))
