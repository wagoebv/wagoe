(ns wagoe.devtools.core.stacktrace
  "Stack trace filtering and reordering for development error output.
   Pure functions — no I/O, no side effects."
  (:require [clojure.string :as str]))

(def ^:private framework-prefixes
  "Namespace prefixes classified as framework code."
  #{"wagoe.platform." "wagoe.observability." "wagoe.devtools."
    "wagoe.core." "ring." "reitit." "integrant." "malli."})

(def ^:private jvm-prefixes
  "Namespace prefixes classified as JVM internals."
  #{"java." "javax." "clojure.lang." "clojure.core"})

(defn classify-frame
  "Classify a namespace string as :user, :framework, or :jvm."
  [ns-str]
  (cond
    (some #(str/starts-with? ns-str %) jvm-prefixes)       :jvm
    (some #(str/starts-with? ns-str %) framework-prefixes)  :framework
    (str/starts-with? ns-str "wagoe.")                   :user
    :else                                                   :framework))

(defn- stack-element->map
  "Convert a StackTraceElement to a map."
  [^StackTraceElement element]
  {:ns      (.getClassName element)
   :fn-name (.getMethodName element)
   :file    (.getFileName element)
   :line    (.getLineNumber element)})

(defn filter-stacktrace
  "Filter and reorder an exception's stack trace.
   Returns {:user-frames [...] :framework-frames [...] :jvm-frames [...] :total-hidden N}"
  [^Throwable exception]
  (let [frames    (map stack-element->map (.getStackTrace exception))
        grouped   (group-by #(classify-frame (:ns %)) frames)
        user      (vec (get grouped :user []))
        framework (vec (get grouped :framework []))
        jvm       (vec (get grouped :jvm []))]
    {:user-frames      user
     :framework-frames framework
     :jvm-frames       jvm
     :total-hidden     (+ (count framework) (count jvm))}))

(defn- format-frame
  "Format a single stack frame as a string."
  [{:keys [ns fn-name file line]}]
  (str ns "/" fn-name " (" file ":" line ")"))

(defn format-stacktrace
  "Format a filtered stack trace for display."
  [{:keys [user-frames total-hidden]}]
  (let [user-section (if (seq user-frames)
                       (str "\u2500\u2500 Your code \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                            (str/join "\n" (map #(str "  " (format-frame %)) user-frames)))
                       "No user code frames found")
        hidden-section (when (pos? total-hidden)
                         (str "\n\n\u2500\u2500 Framework (" total-hidden " frames) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                              "  (expand with (explain *e :verbose))"))]
    (str user-section hidden-section)))
