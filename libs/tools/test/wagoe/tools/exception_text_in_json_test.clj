(ns wagoe.tools.exception-text-in-json-test
  "The JSON sibling of exception-text-on-pages (BOU-555): a JSON API handler
   lets an untyped exception reach the platform, which logs it and answers a
   generic 500 (BOU-557). This fails on a `.getMessage` or `ex-message` outside
   a log call in any shell web/http namespace that does not render HTML."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [edamame.core :as e]))

(def ^:private allowed
  "Top-level vars that may pass an exception message on, and why."
  {["libs/platform/src/wagoe/platform/shell/http/interceptors.clj" 'http-error-handler]
   "typed 4xx only; a 5xx gets the generic body"
   ["libs/tenant/src/wagoe/tenant/shell/membership_http.clj" 'invite-user-handler]
   "typed :conflict/:not-found only"
   ["libs/tenant/src/wagoe/tenant/shell/membership_http.clj" 'update-membership-handler]
   "typed :validation-error only"
   ["libs/tenant/src/wagoe/tenant/shell/membership_http.clj" 'accept-invitation-handler]
   "typed :validation-error only"})

(defn- json-handler-file? [path]
  (let [p   (str/replace (str path) "\\" "/")
        src (delay (slurp p))]
    (and (re-find #"^libs/[^/]+/src/.*/shell/" p)
         (re-find #"(web|http)[^/]*(/|\.clj$)" (subs p (str/index-of p "/shell/")))
         ;; HTML handlers are exception-text-on-pages' scope.
         (not (re-find #"text/html|html-response|ui/error-message" @src))
         (re-find #"application/json|problem-details|generate-string" @src))))

(defn- message-call? [x]
  (and (symbol? x) (contains? #{".getMessage" "ex-message"} (name x))))

(defn- log-call? [form]
  (and (seq? form) (symbol? (first form))
       (contains? #{"log" "logging" "log-ports" "clojure.tools.logging"}
                  (namespace (first form)))))

(defn- leaks
  "Message calls in `form` that no enclosing log call swallows."
  [form]
  (cond
    (log-call? form)     []
    (message-call? form) [form]
    (coll? form)         (mapcat leaks (if (map? form) (mapcat identity form) form))
    :else                []))

(defn- violations [path src]
  (for [form (e/parse-string-all src {:all true :read-cond :allow :features #{:clj}
                                      :readers (fn [_] identity)
                                      :auto-resolve (fn [a] (symbol (str a)))})
        :let [var-name (when (and (seq? form) (symbol? (second form))) (second form))]
        :when (and (seq (leaks form))
                   (not (contains? allowed [path var-name])))]
    {:file path :var var-name :line (:row (meta form))}))

(defn- scoped-files []
  (->> (fs/glob "libs" "*/src/**.clj")
       (map #(str (fs/relativize (fs/cwd) (fs/absolutize %))))
       (filter json-handler-file?)
       sort))

(deftest ^:unit the-scan-sees-a-leak
  (testing "a message put in a JSON body is reported"
    (is (= 1 (count (violations "x.clj" "(defn h [r] (try 1 (catch Exception e {:status 500 :body {:error (.getMessage e)}})))")))))
  (testing "a message inside a log call is not"
    (is (empty? (violations "x.clj" "(defn h [r] (try 1 (catch Exception e (logging/error lg \"x\" {:m (ex-message e)}) :x)))")))))

(deftest ^:unit the-scope-holds-the-json-handlers
  (let [files (set (scoped-files))]
    (is (contains? files "libs/user/src/wagoe/user/shell/http.clj"))
    (is (contains? files "libs/storage/src/wagoe/storage/shell/http_handlers.clj"))))

(deftest ^:unit no-exception-text-in-json-bodies
  (is (= [] (mapv (juxt :file :var :line) (mapcat #(violations % (slurp %)) (scoped-files))))
      "Let an untyped exception reach the platform's http-error-handler; see wagoe.user.shell.http/mfa-setup-handler."))

(deftest ^:unit the-allowlist-is-not-stale
  (doseq [[[path var-name] _why] allowed]
    (let [defined? (re-find (re-pattern (str "\\((def|defn|defn-) " var-name "\\b"))
                            (slurp path))]
      (is defined? (str path " no longer defines " var-name)))))
