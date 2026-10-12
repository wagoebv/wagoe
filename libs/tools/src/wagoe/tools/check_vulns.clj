(ns wagoe.tools.check-vulns
  "Known vulnerabilities in every deps.edn and alias, from GitHub's advisory
   API with GITHUB_TOKEN. A reply that is not an answer fails the run rather
   than reading as \"none\" (BOU-451, BOU-618)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [wagoe.tools.deps :as deps]))

(def ^:private advisories-url "https://api.github.com/advisories")

(def ^:private batch-size
  "Packages per request: the API takes up to 1000, a URL far fewer."
  100)

(def ^:private allowlist-file ".wagoe/check-vulns.edn")

(defn jar-coord
  "[lib version] for a jar under the Maven repository `m2`, or nil."
  [m2 jar]
  (when (str/starts-with? jar m2)
    (let [parts (str/split (subs jar (count m2)) #"/")
          n     (count parts)]
      (when (>= n 4)
        [(symbol (str/join "." (take (- n 3) parts)) (nth parts (- n 3)))
         (nth parts (- n 2))]))))

(defn batches
  "{lib version} maps of at most `size` libraries, no library twice in one:
   GitHub answers which advisories affect *any* listed package@version, so a
   reply can only be traced to a version when its package appears once."
  [coords size]
  (let [by-lib (update-vals (group-by first coords) #(sort (distinct (map second %))))
        rounds (reduce max 0 (map count (vals by-lib)))]
    (for [i     (range rounds)
          batch (partition-all size (sort (keep (fn [[lib vs]] (when-let [v (nth vs i nil)] [lib v])) by-lib)))]
      (into {} batch))))

(defn- classpath-jars
  "Jars on `deps-file`'s classpath under `alias` (nil for the base).

   Serial (-Sthreads 1) and retried, as CI's prefetch is: on a cold ~/.m2,
   parallel resolution trips a Maven HashMap crash, and Central answers 429
   (BOU-250). Either would fail the scan as if a dependency could not exist."
  [deps-file alias]
  (loop [attempt 1]
    (let [{:keys [exit out err]} (apply process/shell {:dir (str (fs/parent deps-file)) :out :string
                                                       :err :string :continue true}
                                        (cond-> ["clojure" "-Sthreads" "1" "-Spath"]
                                          alias (conj (str "-A" alias))))]
      (cond
        (zero? exit) (filter #(str/ends-with? % ".jar") (str/split (str/trim out) #":"))
        (< attempt 3) (do (Thread/sleep (* 5000 attempt)) (recur (inc attempt)))
        :else
        (let [why (->> (str/split-lines err) (remove #(str/starts-with? % "Picked up")) (str/join " "))]
          (throw (ex-info (str "cannot resolve " deps-file " " alias ": " why) {:type :internal-error})))))))

(defn- users
  "{[lib version] #{[\"libs/x\" \":alias\"]}} across `files` and their aliases;
   the alias is nil for a file's base classpath."
  [m2 files]
  (apply merge-with into
         (for [f     files
               alias (cons nil (keys (:aliases (edn/read-string (slurp f)))))
               coord (keep #(jar-coord m2 %) (classpath-jars f alias))]
           {coord #{[(deps/relative-label f) (some-> alias str)]}})))

(defn- package [lib] (str (namespace lib) ":" (name lib)))

(defn- next-link
  "The `rel=\"next\"` URL of a Link header, or nil."
  [link]
  (some->> link (re-find #"<([^>]+)>;\s*rel=\"next\"") second))

(defn- advisories
  "Every advisory affecting a package@version in `batch`, all pages."
  [token batch]
  (loop [url  (str advisories-url "?ecosystem=maven&per_page=100&affects="
                   ;; Each encoded on its own: a `+` in a version would read as a space.
                   (str/join "," (for [[lib v] batch]
                                   (java.net.URLEncoder/encode (str (package lib) "@" v) "UTF-8"))))
         acc []]
    (let [{:keys [status body headers]} (http/get url {:headers {"Authorization" (str "Bearer " token)
                                                                 "Accept" "application/vnd.github+json"}
                                                       :throw false})]
      (when-not (= 200 status)
        (throw (ex-info (str "GitHub's advisory API answered " status ", so nothing was checked: "
                             (subs (str body) 0 (min 300 (count (str body)))))
                        {:type :internal-error :status status})))
      (let [acc (into acc (json/parse-string body true))]
        (if-let [nxt (next-link (get headers "link"))]
          (recur nxt acc)
          acc)))))

(defn findings
  "Rows {:lib :version :id :cve :severity :fixed} for `advisories` returned for
   `batch`; withdrawn ones are not findings. An advisory naming several packages
   of the batch may affect only one of them, so those rows carry `:ambiguous?`."
  [batch advisories]
  (let [by-package (into {} (for [[lib v] batch] [(package lib) [lib v]]))]
    (for [{:keys [ghsa_id cve_id severity withdrawn_at vulnerabilities]} advisories
          :when (nil? withdrawn_at)
          :let  [hits    (group-by #(get-in % [:package :name])
                                   (filter #(by-package (get-in % [:package :name])) vulnerabilities))
                 several (> (count hits) 1)]
          [pkg vs] hits
          :let  [[lib v] (by-package pkg)]]
      (cond-> {:lib lib :version v :id ghsa_id :cve cve_id
               :severity (some-> severity keyword)
               :fixed    (some->> (keep :first_patched_version vs) distinct seq (str/join " or "))}
        several (assoc :ambiguous? true)))))

(defn- allowlist []
  (let [entries (:allow (when (fs/exists? allowlist-file) (edn/read-string (slurp allowlist-file))))]
    (doseq [e entries :when (not (and (string? (:why e)) (not (str/blank? (:why e)))))]
      (throw (ex-info (str allowlist-file ": " (or (:id e) (:alias e)) " has no :why")
                      {:type :validation-error})))
    entries))

(defn allowed?
  "Whether `entries` excuse `finding`: by advisory `:id`, or by `:alias` when
   every use of that library version comes through excused aliases. An alias
   entry with `:while [lib version]` lapses once the alias stops using that
   version, so upgrading the tool asks for the excuse again."
  [entries used {:keys [lib version id]}]
  ;; Per file: one library still on the old tool version must not excuse
  ;; another that has moved on.
  (let [excused? (fn [[_ alias :as who]]
                   (some (fn [{[wl wv] :while :as e}]
                           (and alias (= alias (:alias e))
                                (or (nil? wl) (contains? (used [wl wv]) who))))
                         entries))]
    (or (contains? (set (keep :id entries)) id)
        (every? excused? (used [lib version])))))

(defn users-line
  "Who uses a library version, an alias shared by many files said once:
   `libs/admin, libs/ai :build (31 files)` rather than 31 entries."
  [who]
  (->> (group-by second who)
       (sort-by (comp str key))
       (map (fn [[alias ws]]
              (if (and alias (> (count ws) 3))
                (str alias " (" (count ws) " files)")
                (str/join ", " (sort (map (fn [[label a]] (str label (when a (str " " a)))) ws))))))
       (str/join "; ")))

(def ^:private severity-order {:critical 0 :high 1 :medium 2 :low 3})

(defn -main [& _]
  (let [token (System/getenv "GITHUB_TOKEN")]
    (when (str/blank? token)
      (println "GITHUB_TOKEN is not set; GitHub's advisory API needs it.")
      (System/exit 2))
    (let [m2    (str (fs/path (System/getProperty "user.home") ".m2" "repository") "/")
          files (deps/find-deps-files)
          used  (users m2 files)
          _     (when (empty? used)
                  (println "No jars found under" m2 "- nothing was checked.")
                  (System/exit 2))
          allow (allowlist)
          ;; Asked alone, the reply names the advisory only if this version is affected.
          confirmed? (fn [{:keys [lib version id]}]
                       (some #(= id (:ghsa_id %)) (advisories token {lib version})))
          ;; A few requests, each paged: in parallel rather than one by one.
          rows  (->> (batches (keys used) batch-size)
                     (pmap #(findings % (advisories token %)))
                     (apply concat)
                     (filter #(or (not (:ambiguous? %)) (confirmed? %)))
                     (map #(dissoc % :ambiguous?))
                     distinct
                     (remove #(allowed? allow used %))
                     (sort-by (juxt #(severity-order (:severity %) 9) (comp str :lib) :version :id)))]
      (println (count used) "library versions checked across" (count files) "deps.edn files.")
      (doseq [{:keys [lib version id cve severity fixed]} rows]
        (println (format "%-8s %s %s  %s %s  fixed in %s\n         used by %s"
                         (name (or severity :unknown)) lib version id (or cve "") (or fixed "-")
                         (users-line (used [lib version])))))
      (if (seq rows)
        (do (println (count rows) "advisories. Upgrade, or allowlist with a :why in" allowlist-file)
            (System/exit 1))
        (println "No known vulnerabilities.")))))
