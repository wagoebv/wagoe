(ns wagoe.tools.check-vulns
  "Known vulnerabilities in every dependency of every deps.edn, aliases
   included (BOU-451). `bb check:vulns`; needs GITHUB_TOKEN.

   clj-watson's GitHub advisory strategy wants coordinates, so a scan per
   deps.edn would query each library ~35 times and pass GitHub's 5000/hour
   limit. Instead the resolved jars of every classpath become a few synthetic
   deps.edn files, one version of each library per file, scanned once each."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [wagoe.tools.deps :as deps]))

(def ^:private watson-alias
  {:aliases {:clj-watson {:replace-paths []
                          :replace-deps  {'io.github.clj-holmes/clj-watson
                                          {:git/tag "v6.1.0" :git/sha "be98e4d"}}
                          :main-opts     ["-m" "clj-watson.cli"]}}})

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

(defn synthetic-deps
  "One deps map per round: round i pins the i-th version of each library."
  [coords]
  (let [by-lib (update-vals (group-by first coords) #(sort (distinct (map second %))))
        rounds (reduce max 0 (map count (vals by-lib)))]
    (for [i (range rounds)]
      {:deps (into (sorted-map)
                   (for [[lib vs] by-lib :let [v (nth vs i nil)] :when v]
                     [lib {:mvn/version v}]))})))

(defn- classpath-jars
  "Jars on `deps-file`'s classpath under `alias` (nil for the base)."
  [deps-file alias]
  (let [{:keys [exit out err]} (apply process/shell {:dir (str (fs/parent deps-file)) :out :string
                                                     :err :string :continue true}
                                    (cond-> ["clojure" "-Spath"] alias (conj (str "-A" alias))))]
    (when-not (zero? exit)
      (throw (ex-info (str "cannot resolve " deps-file " " alias ": " (first (str/split-lines err)))
                      {:type :internal-error})))
    (filter #(str/ends-with? % ".jar") (str/split (str/trim out) #":"))))

(defn- users
  "{[lib version] #{\"libs/x :alias\"}} across every deps.edn and alias."
  [m2]
  (apply merge-with into
         (for [f     (deps/find-deps-files)
               alias (cons nil (keys (:aliases (edn/read-string (slurp f)))))
               :let  [who (str (deps/relative-label f) (when alias (str " " alias)))]
               coord (keep #(jar-coord m2 %) (classpath-jars f alias))]
           {coord #{who}})))

(defn- scan
  "clj-watson's findings for one synthetic deps map."
  [deps-map]
  (let [f (str (fs/create-temp-file {:prefix "check-vulns-" :suffix ".edn"}))]
    (spit f (pr-str deps-map))
    (let [{:keys [out err]} (process/shell {:out :string :err :string :continue true}
                                           "clojure" "-Sdeps" (pr-str watson-alias) "-M:clj-watson"
                                           "scan" "-p" f "-t" "github-advisory" "-o" "json")
          ;; The JSON comes first, then a plain-text summary.
          parsed (try (first (json/parsed-seq (java.io.StringReader. out) true))
                      (catch Exception _ ::unreadable))]
      (when (= ::unreadable parsed)
        (throw (ex-info (str "clj-watson gave no readable report:\n" (str/join "\n" (take-last 5 (str/split-lines err))))
                        {:type :internal-error})))
      parsed)))

(defn findings
  "Rows {:lib :version :id :severity :fixed} for advisories on a library
   version something here actually uses; the synthetic files' own transitive
   resolution can add others."
  [reports used]
  (distinct
   (for [report reports
         {:keys [dependency vulnerabilities] :as d} report
         :let  [coord [(symbol dependency) (:mvn/version d)]]
         :when (contains? used coord)
         v     vulnerabilities
         :let  [ids (map :value (get-in v [:advisory :identifiers]))]]
     {:lib      (first coord)
      :version  (second coord)
      :id       (first ids)
      :cve      (first (filter #(str/starts-with? % "CVE") ids))
      :severity (some-> (get-in v [:advisory :severity]) str/lower-case keyword)
      :fixed    (get-in v [:firstPatchedVersion :identifier])})))

(defn- allowlist []
  (let [entries (:allow (when (fs/exists? allowlist-file) (edn/read-string (slurp allowlist-file))))]
    (doseq [e entries :when (str/blank? (:why e))]
      (throw (ex-info (str allowlist-file ": " (:id e) " has no :why") {:type :validation-error})))
    (set (map :id entries))))

(defn users-line
  "Who uses a library version, an alias shared by many files said once:
   `libs/admin, libs/ai :build (31 files)` rather than 31 entries."
  [who]
  (->> (group-by #(second (str/split % #" " 2)) who)
       (sort-by (comp str key))
       (map (fn [[alias ws]]
              (if (and alias (> (count ws) 3))
                (str alias " (" (count ws) " files)")
                (str/join ", " (sort ws)))))
       (str/join "; ")))

(def ^:private severity-order {:critical 0 :high 1 :medium 2 :low 3})

(defn -main [& _]
  (when (str/blank? (System/getenv "GITHUB_TOKEN"))
    (println "GITHUB_TOKEN is not set; clj-watson queries GitHub's advisory database with it.")
    (System/exit 2))
  (let [m2      (str (fs/path (System/getProperty "user.home") ".m2" "repository") "/")
        used    (users m2)
        allowed (allowlist)
        rows    (->> (findings (map scan (synthetic-deps (keys used))) used)
                     (remove #(allowed (:id %)))
                     (sort-by (juxt #(severity-order (:severity %) 9) (comp str :lib) :version)))]
    (println (count used) "library versions checked across" (count (deps/find-deps-files)) "deps.edn files.")
    (doseq [{:keys [lib version id cve severity fixed]} rows]
      (println (format "%-8s %s %s  %s %s  fixed in %s\n         used by %s"
                       (name (or severity :unknown)) lib version id (or cve "") (or fixed "-")
                       (users-line (used [lib version])))))
    (if (seq rows)
      (do (println (count rows) "advisories. Upgrade, or allowlist with a :why in" allowlist-file)
          (System/exit 1))
      (println "No known vulnerabilities."))))
