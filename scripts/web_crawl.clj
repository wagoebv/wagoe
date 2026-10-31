#!/usr/bin/env bb
;; Sign in and open every page the web UI links to, failing on any that answers
;; 4xx or 5xx (BOU-919, BOU-920). A fresh app's users page 500'd on SQLite and
;; a created user landed on a 404, and the first-run smoke never opened a page.
;;
;;   bb scripts/web_crawl.clj http://localhost:3000 admin@demo.test 'password' [urls.txt]
;;
;; Follows href, hx-get and form GET actions under /web, so a table's sort,
;; filter and page links are opened too. Exits 1 naming each failure.
(require '[babashka.http-client :as http]
         '[clojure.string :as str])

(def max-pages 600)
;; A table emits one link per sort, page and filter; the shape is what breaks,
;; so a few of each path are enough.
(def max-per-path 25)

(defn- client []
  (http/client {:follow-redirects :normal
                :cookie-handler   (java.net.CookieManager.)}))

(defn- unescape [s]
  (-> s (str/replace "&amp;" "&") (str/replace "&quot;" "\"") (str/replace "&#39;" "'")))

(defn- links [html]
  (->> (concat (re-seq #"(?:href|hx-get)=\"([^\"]+)\"" html)
               (re-seq #"<form[^>]*method=\"get\"[^>]*action=\"([^\"]+)\"" html)
               (re-seq #"<form[^>]*action=\"([^\"]+)\"[^>]*method=\"get\"" html))
       (map (comp unescape second))))

(defn- follow? [url]
  (and (str/starts-with? url "/web")
       (not (re-find #"/logout|/sign-out|\{" url))))

(defn- path-of [url] (first (str/split url #"\?")))

(def junk-params
  "Asked of every page once: what a stale bookmark, a tracking link or a curious
   user sends. None of it may answer 5xx."
  ["sort=nope" "sort=password-hash" "dir=sideways" "utm_source=newsletter" "page=-1"
   "page-size=100000" "q=%27%22%3B" "search=%27%22%3B%25_" "updated-at=2026-01-01"
   "filters%5Bpassword-hash%5D%5Bop%5D=contains&filters%5Bpassword-hash%5D%5Bvalue%5D=%24"])

(defn- with-param [url param]
  (str url (if (str/includes? url "?") "&" "?") param))

(defn- error-page? [body]
  ;; The app's own error page, whatever status it was served with
  (boolean (re-find #"<title>(Something went wrong|Error)</title>" body)))

(defn crawl! [base email password urls-file]
  (let [c     (client)
        get!  #(http/get (str base %) {:client c :throw false
                                       :headers {"Accept" "text/html"}})
        login (http/post (str base "/web/login")
                         {:client c :throw false
                          :form-params {"email" email "password" password "remember" "false"}})]
    (when (or (>= (:status login) 400) (str/includes? (str (:uri login)) "/web/login"))
      (println "FAIL sign-in:" (:status login) (str (:uri login)))
      (System/exit 1))
    (loop [queue    (into clojure.lang.PersistentQueue/EMPTY ["/web/admin/" "/web/users" "/web/profile"])
           seen     #{}
           per-path {}
           failures []]
      (if-let [url (peek queue)]
        (let [queue (pop queue)
              p     (path-of url)]
          (if (or (seen url) (>= (count seen) max-pages) (>= (get per-path p 0) max-per-path))
            (recur queue seen per-path failures)
            (let [{:keys [status body]} (get! url)
                  body     (str body)
                  bad      (or (>= status 400) (error-page? body))
                  next-urls (filter follow? (links body))]
              (recur (into queue (remove seen next-urls))
                     (conj seen url)
                     (update per-path p (fnil inc 0))
                     (cond-> failures bad (conj [status url]))))))
        (let [shapes   (vals (reduce (fn [m u] (update m (path-of u) #(or % u))) {} (sort seen)))
              fuzzed   (for [u shapes p junk-params
                             :let [url (with-param (path-of u) p)
                                   {:keys [status body]} (get! url)]
                             :when (or (>= status 500) (error-page? (str body)))]
                         [status url])
              failures (into failures fuzzed)]
          (println (str "Opened " (count seen) " pages under " base
                        ", and " (count shapes) " of them with " (count junk-params) " odd parameters each"))
            (when urls-file (spit urls-file (str/join "\n" (sort seen))))
            (doseq [[status url] failures] (println "FAIL" status url))
            (when (< (count seen) 10)
              (println "FAIL only" (count seen) "pages reached; the crawl proves nothing")
              (System/exit 1))
            (when (seq failures) (System/exit 1)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [[base email password urls-file] *command-line-args*]
    (when-not password
      (println "Usage: bb scripts/web_crawl.clj <base-url> <email> <password>")
      (System/exit 2))
    (crawl! base email password urls-file)))
