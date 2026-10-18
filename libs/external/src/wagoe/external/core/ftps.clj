(ns wagoe.external.core.ftps
  "Pure functions for the FTPS file source: file selection, which failures to
   retry and error values. No I/O."
  (:require [clojure.string :as str]))

;; =============================================================================
;; File selection
;; =============================================================================

(defn- name-matcher
  "Predicate on a file name for m: a string (exact name), a regex (whole name)
   or a collection of names. A nil m matches everything."
  [m]
  (cond
    (nil? m)                              (constantly true)
    (string? m)                           #{m}
    (instance? java.util.regex.Pattern m) #(re-matches m %)
    :else                                 (set m)))

(defn select-files
  "Files whose :name matches :match and is not in :skip, sorted by name.

  Args:
    files - seq of RemoteFile maps
    opts  - {:match string|regex|coll, :skip coll of names already processed}"
  [files {:keys [match skip]}]
  (let [match? (name-matcher match)
        skip   (set skip)]
    (->> files
         (filter #(match? (:name %)))
         (remove #(contains? skip (:name %)))
         (sort-by :name)
         vec)))

(defn remote-path
  "Join a directory and a file name with exactly one slash."
  [dir file-name]
  (cond
    (str/blank? dir)         file-name
    (str/ends-with? dir "/") (str dir file-name)
    :else                    (str dir "/" file-name)))

;; =============================================================================
;; Retry policy
;; =============================================================================

(defn retryable?
  "Whether a failed attempt is worth repeating. A 5xx reply (rejected
   credentials, missing file) and an untrusted certificate are permanent; a 4xx
   reply and an I/O failure without a reply (DNS, refused connection, TLS
   handshake or read timeout) are transient.

  Args:
    failure - {:reply-code int-or-nil, :certificate? boolean}"
  [{:keys [reply-code certificate?]}]
  (cond
    certificate? false
    reply-code   (< reply-code 500)
    :else        true))

;; =============================================================================
;; Error values
;; =============================================================================

(defn ftps-error
  "The :error map of a failed operation, naming host, attempts and cause."
  [{:keys [host operation attempts cause reply-code]}]
  (cond-> {:type     :external-error
           :message  (format "FTPS %s on %s failed after %d attempt%s: %s"
                             (name operation) host attempts (if (= 1 attempts) "" "s") cause)
           :host     host
           :attempts attempts
           :cause    cause}
    reply-code (assoc :reply-code reply-code)))
