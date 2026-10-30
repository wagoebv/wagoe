(ns wagoe.cli.profile
  "Reading resources/conf/<env>/config.edn as Aero would, as far as the keys
   go. `wagoe add` and `wagoe upgrade authorization` both need a profile that
   is only an `#include` read as the file it includes (BOU-867, BOU-910)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn profile-file [env] (str "resources/conf/" env "/config.edn"))

(defn- include-target
  "The existing file `target`, included from `path`, names, relative to `dir`
   with forward slashes: next to `path`, else on the classpath under
   resources/, the order wagoe.config resolves it in. Nil when neither exists."
  [dir path target]
  (let [root (.normalize (.toAbsolutePath (.toPath (io/file dir))))
        from (.getParent (.resolve root ^String path))]
    (some (fn [^java.nio.file.Path p]
            (let [p (.normalize p)]
              (when (.exists (.toFile p))
                (str/replace (str (.relativize root p)) "\\" "/"))))
          (cond-> [(.resolve from ^String target)]
            (not (.isAbsolute (io/file target))) (conj (.resolve root (str "resources/" target)))))))

(defn- read-tagged
  "`text` read as EDN with its tags left as tagged literals, or nil."
  [text]
  (try (edn/read-string {:default tagged-literal} text) (catch Exception _ nil)))

(defn include-of
  "What `text` includes when it is only an `#include`, else nil."
  [text]
  (let [v (some-> text read-tagged)]
    (when (and (tagged-literal? v) (= 'include (:tag v)) (string? (:form v)))
      (:form v))))

(defn- read-config*
  [dir path text seen cache]
  (when-let [form (some-> text read-tagged)]
    (letfn [(include [to]
              (if (contains? @cache to)
                (get @cache to)
                (let [v (when-not (seen to)
                          (read-config* dir to (slurp (io/file dir to)) (conj seen to) cache))]
                  (vswap! cache assoc to v)
                  v)))
            (walk [root x]
              (cond
                (tagged-literal? x)
                (let [v (walk root (:form x))]
                  (case (:tag x)
                    include (some->> (when (string? v) (include-target dir path v)) include)
                    merge   (when (and (sequential? v) (every? #(or (nil? %) (map? %)) v))
                              (apply merge v))
                    ref     (when (sequential? v) (get-in root v))
                    v))
                (map? x)    (into {} (map (fn [[k v]] [(walk root k) (walk root v)])) x)
                (vector? x) (mapv #(walk root %) x)
                :else       x))]
      ;; Refs resolve against the whole file, so twice: the second pass looks
      ;; them up in the first. Included files are read once, cached.
      (walk (walk nil form) form))))

(defn read-config
  "Config `text` of the file at `path` in `dir`: `#include` read from the file
   it names, `#merge` merged, `#ref` looked up; any other tag is its value.
   Nil when it does not read."
  [dir path text]
  (try (read-config* dir path text #{path} (volatile! {}))
       (catch Exception _ nil)))

(defn profile
  "{:path :text :include :config} of config `text` at `path`, :config read
   only when asked for."
  [dir path text]
  {:path    path
   :text    text
   :include (include-of text)
   :config  (delay (some->> text (read-config dir path)))})

(defn reader
  "A function of a path in `dir` to its `profile`, each file read once."
  [dir]
  (memoize #(profile dir % (try (slurp (io/file dir %)) (catch Exception _ nil)))))

(defn profile-path
  "Profile `env`'s config.edn relative to `dir`, or, when that file is only an
   `#include`, the file it includes, followed as Aero does (BOU-867)."
  ([dir env] (profile-path dir env (reader dir)))
  ([dir env read]
   (loop [path (profile-file env) seen #{}]
     (let [to (some->> (:include (read path)) (include-target dir path))]
       (if (and to (not (seen to)))
         (recur to (conj seen path))
         path)))))
