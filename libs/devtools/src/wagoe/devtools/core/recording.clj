(ns wagoe.devtools.core.recording
  "Pure functions for recording session data structures.
   No I/O, no atoms — just data transformations."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(defn create-session [] {:entries [] :started-at (java.util.Date.) :stopped-at nil})

(defn add-entry [session request response duration-ms]
  (let [idx (count (:entries session))]
    (update session :entries conj
            {:idx idx :request request :response response
             :duration-ms duration-ms :timestamp (java.util.Date.)})))

(defn get-entry [session idx] (get (:entries session) idx))

(defn stop-session [session] (assoc session :stopped-at (java.util.Date.)))

(defn entry-count [session] (count (:entries session)))

(defn- deep-merge
  "Recursively merge maps. Non-map values in b override those in a."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn merge-request-modifications
  "Deep-merge overrides into a recorded request's body.
   If the body is a raw JSON string (as captured), it is parsed first
   so the merge preserves untouched fields."
  [request overrides]
  (let [body (:body request)
        parsed (if (string? body)
                 (try (clojure.edn/read-string body)
                      (catch Exception _
                        (try (cheshire.core/parse-string body true)
                             (catch Exception _ body))))
                 body)]
    (assoc request :body (deep-merge parsed overrides))))

(defn- map-diff [a b]
  (let [all-keys (set (concat (keys a) (keys b)))]
    (reduce
     (fn [acc k]
       (let [va (get a k) vb (get b k)]
         (cond
           (and (nil? va) (some? vb)) (conj acc [:added k vb])
           (and (some? va) (nil? vb)) (conj acc [:removed k va])
           (not= va vb) (conj acc [:changed k va vb])
           :else acc)))
     [] (sort all-keys))))

(defn diff-entries [session idx-a idx-b]
  (let [a (get-entry session idx-a) b (get-entry session idx-b)]
    (when (and a b)
      {:request-diff (map-diff (:request a) (:request b))
       :response-diff (map-diff (:response a) (:response b))})))

(defn format-entry-table [session]
  (let [entries (:entries session)
        header (format "%-5s %-7s %-30s %-8s %-10s" "IDX" "METHOD" "PATH" "STATUS" "DURATION")
        sep (apply str (repeat (count header) "─"))
        rows (map (fn [{:keys [idx request response duration-ms]}]
                    (format "%-5d %-7s %-30s %-8d %-10s"
                            idx (str/upper-case (name (:method request)))
                            (:uri request) (:status response) (str duration-ms "ms")))
                  entries)]
    (str/join "\n" (concat [header sep] rows))))

(defn serialize-session [session] (pr-str session))
(defn deserialize-session [s] (edn/read-string {:readers {}} s))
