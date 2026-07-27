(ns wagoe.e2e.helpers.cookies
  "Set-Cookie parsing for e2e assertions. session-token must always be HttpOnly."
  (:require [clojure.string :as str]))

(defn- set-cookie-strings [headers]
  (let [raw (or (get headers "set-cookie")
                (get headers "Set-Cookie"))]
    (cond
      (nil? raw)    []
      (string? raw) [raw]
      :else         raw)))

(defn- find-cookie [headers cookie-name]
  (some (fn [line]
          (when (str/starts-with? (str/lower-case line)
                                  (str (str/lower-case cookie-name) "="))
            line))
        (set-cookie-strings headers)))

(defn session-token
  "Parses the session-token value from response headers, asserting HttpOnly.
   Throws ex-info if absent or if HttpOnly is missing."
  [headers]
  (let [line (find-cookie headers "session-token")]
    (when-not line
      (throw (ex-info "session-token cookie not found in Set-Cookie"
                      {:headers headers})))
    (when-not (str/includes? (str/lower-case line) "httponly")
      (throw (ex-info "session-token missing HttpOnly flag"
                      {:cookie line})))
    (-> line (str/split #";") first (str/split #"=" 2) second)))

(defn remembered-email
  "Returns the URL-decoded value of the remembered-email cookie, or nil."
  [headers]
  (when-let [line (find-cookie headers "remembered-email")]
    (let [raw (-> line (str/split #";") first (str/split #"=" 2) second)]
      (java.net.URLDecoder/decode raw "UTF-8"))))

(defn no-session-token?
  "True if Set-Cookie does NOT set session-token."
  [headers]
  (nil? (find-cookie headers "session-token")))
