(ns wagoe.core.utils.redirect
  "Is a user-supplied redirect target a path on this site? Shared by every
   `return-to`, so the rule is written once (BOU-553)."
  (:require [clojure.string :as str])
  (:import [java.net URI URISyntaxException URLDecoder]
           [java.nio.charset StandardCharsets]))

(defn- decode-once
  "Percent-decode `s`, keeping `+` literal. nil when the escapes are malformed."
  [s]
  (try
    (URLDecoder/decode (str/replace s "+" "%2B") StandardCharsets/UTF_8)
    (catch IllegalArgumentException _ nil)))

(defn- relative-uri?
  "Parses as a URI with neither scheme nor authority."
  [s]
  (try
    (let [uri (URI. s)]
      (and (nil? (.getScheme uri)) (nil? (.getRawAuthority uri))))
    (catch URISyntaxException _ false)))

(defn- plain-path?
  "Starts with one `/`, and holds no backslash — browsers read it as `/` — and
   no control character."
  [s]
  (and (str/starts-with? s "/")
       (not (str/starts-with? s "//"))
       (not (str/includes? s "\\"))
       (not (re-find #"\p{Cntrl}" s))))

(defn local-path?
  "True when `s` is a path on this site, safe to redirect to: checked as given
   and once percent-decoded, so `/%5Cevil.com` is refused like `/\\evil.com`.
   Raw whitespace is refused too; a browser strips it, and `/\t/evil.com`
   becomes `//evil.com`."
  [s]
  (boolean
   (and (string? s)
        (not (re-find #"\s" s))
        (plain-path? s)
        (relative-uri? s)
        (some-> (decode-once s) plain-path?))))
