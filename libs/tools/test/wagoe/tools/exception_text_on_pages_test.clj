(ns wagoe.tools.exception-text-on-pages-test
  "An exception's message can carry driver, SQL or config detail, so a web
   handler logs it and shows generic text (BOU-555). This reads every shell
   web/http namespace that renders HTML and fails on a `.getMessage` or
   `ex-message` that is not inside a log call."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [edamame.core :as e]))

(def ^:private allowed
  "Top-level vars that may pass an exception message on, and why."
  {["libs/admin/src/wagoe/admin/shell/http/handlers/crud.clj" 'client-safe-error-message]
   "typed ex-info mapped to a 4xx only (BOU-182)"
   ["libs/user/src/wagoe/user/shell/web_handlers.clj" 'user-facing-message]
   "typed ex-info the handlers throw with curated text"
   ["libs/audience/src/wagoe/audience/shell/http.clj" 'handle-audience-error]
   "JSON, typed filter errors only"})

(defn- html-handler-file? [path]
  (let [p (str/replace (str path) "\\" "/")]
    (and (re-find #"^libs/[^/]+/src/.*/shell/" p)
         (re-find #"(web|http)[^/]*(/|\.clj$)" (subs p (str/index-of p "/shell/")))
         (re-find #"text/html|html-response|ui/error-message" (slurp p)))))

(defn- message-call? [x]
  (and (symbol? x) (contains? #{".getMessage" "ex-message"} (name x))))

(defn- log-call? [form]
  (and (seq? form) (symbol? (first form))
       (contains? #{"log" "clojure.tools.logging"} (namespace (first form)))))

(defn- leaks
  "Message calls in `form` that no enclosing log call swallows."
  [form]
  (cond
    (log-call? form)   []
    (message-call? form) [form]
    (coll? form)       (mapcat leaks (if (map? form) (mapcat identity form) form))
    :else              []))

(defn- violations [path src]
  (for [form (e/parse-string-all src {:all true :read-cond :allow :features #{:clj}
                                      :readers (fn [_] identity)
                                      :auto-resolve (fn [a] (symbol (str a)))})
        :let [var-name (when (and (seq? form) (symbol? (second form))) (second form))]
        :when (and (seq (leaks form))
                   (not (contains? allowed [path var-name])))]
    {:file path :var var-name :line (:row (meta form))}))

(deftest ^:unit the-scan-sees-a-leak
  (testing "a message rendered into a page is reported"
    (is (= 1 (count (violations "x.clj" "(defn h [r] (try 1 (catch Exception e (ui/error-message (.getMessage e)))))")))))
  (testing "a message inside a log call is not"
    (is (empty? (violations "x.clj" "(defn h [r] (try 1 (catch Exception e (log/error {:m (ex-message e)}) :x)))")))))

(deftest ^:unit no-exception-text-on-pages
  (let [files (->> (fs/glob "libs" "*/src/**.clj")
                   (map #(str (fs/relativize (fs/cwd) (fs/absolutize %))))
                   (filter html-handler-file?)
                   sort)]
    (is (seq files) "the scope found no files; the glob is broken")
    (is (= [] (mapv (juxt :file :var :line) (mapcat #(violations % (slurp %)) files)))
        "Log the exception and show [:t :common/error-generic]; see wagoe.user.shell.web-handlers/server-error.")))

(deftest ^:unit the-allowlist-is-not-stale
  (doseq [[[path var-name] _why] allowed]
    (let [defined? (str/includes? (slurp path) (str "(defn- " var-name))]
      (is defined? (str path " no longer defines " var-name)))))
