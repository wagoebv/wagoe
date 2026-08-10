#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_versions.clj
;;
;; Every place that names the library-suite version must name the same one.

(ns wagoe.tools.check-versions
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private root-dir (fs/file (System/getProperty "user.dir")))

(def ^:private version-pattern
  "A suite version: 1.0.0-beta-4, 1.0.1-alpha-32, 2.0.0."
  #"\d+\.\d+\.\d+(?:-[a-z]+-\d+)?")

(defn- version-sources
  "Every file that hard-codes the suite version, and the version it names.

   Discovered by pattern rather than listed: a hand-kept list is how this
   drifted in the first place — three repositories shipped a bb.edn pinning
   wagoe-tools at alpha-20 and alpha-32 while their deps.edn had moved on,
   because the bump routine covered deps.edn and nobody had written bb.edn
   down.

   Returns a seq of {:file str :version str :what str}."
  []
  (let [read* (fn [f] (try (slurp (fs/file f)) (catch Exception _ "")))
        rel   (fn [f] (str (fs/relativize root-dir (fs/absolutize f))))
        find1 (fn [content re]
                (when-let [m (re-find re content)]
                  (re-find version-pattern (if (vector? m) (first m) m))))]
    (concat
     ;; Each publishable library's build.clj.
     (for [f    (sort (map str (fs/glob root-dir "libs/*/build.clj")))
           :let [v (find1 (read* f) #"\(def version \"[^\"]+\"")]
           :when v]
       {:file (rel f) :version v :what "build.clj"})

     ;; The CLI's pin of the tools artifact it writes into generated projects.
     (for [f    ["libs/wagoe-cli/src/wagoe/cli/new.clj"]
           :let [c (read* (fs/file root-dir f))]
           [_ v] (re-seq (re-pattern (str "-version\\s+\"(" version-pattern ")\"")) c)]
       {:file f :version v :what "generated-project pin"})

     ;; The module catalogue shipped with the CLI.
     (for [f    ["libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn"]
           :let [v (find1 (read* (fs/file root-dir f)) #":catalogue-version\s+\"[^\"]+\"")]
           :when v]
       {:file f :version v :what "catalogue-version"})

     ;; Any com.wagoe/* Maven pin, in deps.edn or bb.edn, anywhere in the tree.
     ;; This is the shape the ticket is named for: bb.edn pins are separate
     ;; from deps.edn pins and were bumped separately, which is to say not
     ;; bumped.
     (for [f    (concat (map str (fs/glob root-dir "**/deps.edn"))
                        (map str (fs/glob root-dir "**/bb.edn")))
           :when (not (str/includes? f "/target/"))
           :let  [c (read* f)]
           [_ v] (re-seq (re-pattern (str "com\\.wagoe/[a-z0-9-]+\\s*\\{:mvn/version\\s+\"("
                                          version-pattern ")\"")) c)]
       {:file (rel f) :version v :what "com.wagoe pin"}))))

(defn disagreements
  "The sources that do not name the majority version.

   Pure, so the gate can be proven to fire without a repository to break. The
   majority wins because a bump touches most locations and misses a few — that
   is the shape the ticket describes, and naming the stragglers is more useful
   than naming the many.

   Returns {:consensus str :offenders seq}, or nil when everything agrees."
  [sources]
  (let [versions (frequencies (map :version sources))]
    (when (< 1 (count versions))
      (let [consensus (key (apply max-key val versions))]
        {:consensus consensus
         :offenders (->> sources
                         (remove #(= consensus (:version %)))
                         (sort-by :file))}))))

(defn check
  "Fail when the repository names more than one suite version."
  []
  (println "Verifying every hard-coded suite version agrees")
  (let [sources (version-sources)]
    (if (empty? sources)
      (do (binding [*out* *err*]
            (println "  ✗ found no version strings at all — the check is not looking at anything"))
          (System/exit 1))
      (if-let [{:keys [consensus offenders]} (disagreements sources)]
        (do (binding [*out* *err*]
              (println (str "  ✗ " (count offenders) " location(s) disagree with the other "
                            (- (count sources) (count offenders))))
              (doseq [{:keys [file version what]} offenders]
                (println (str "      " file "  (" what ") names " version
                              ", the rest name " consensus))))
            (System/exit 1))
        (println (str "  ✓ " (count sources) " location(s) all name "
                      (:version (first sources))))))))

(defn -main [& _] (check))
