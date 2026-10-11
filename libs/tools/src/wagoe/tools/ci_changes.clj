(ns wagoe.tools.ci-changes
  "Whether a pull request changes only docs that no code test reads, so CI can
   skip the code jobs (BOU-615). `bb ci:changes <base-ref>` writes `code=true`
   or `code=false` to $GITHUB_OUTPUT."
  (:require [babashka.process :as process]
            [clojure.string :as str]))

(def docs-only-paths
  "Files no test outside libs/tools reads; tools tests always run. Not
   docs/modules/** (the app suite lists its pages), AGENTS.md or a library's
   README (workflow pins its own), which code tests do read."
  [#"^dev-docs/.*\.(md|adoc)$"
   #"^(CHANGELOG|README|UPGRADING|CONTRIBUTING|BUILD|CLAUDE)\.md$"])

(defn docs-only?
  "Whether every path in `paths` is one of `docs-only-paths`. An empty diff is
   not docs-only: run everything rather than guess."
  [paths]
  (boolean (and (seq paths)
                (every? (fn [p] (some #(re-find % p) docs-only-paths)) paths))))

(defn- changed-files [base-ref]
  (let [{:keys [exit out err]} (process/shell {:out :string :err :string :continue true}
                                              "git" "diff" "--name-only" (str "origin/" base-ref "...HEAD"))]
    (when-not (zero? exit)
      (throw (ex-info (str "git diff against " base-ref " failed: " err) {:type :internal-error})))
    (remove str/blank? (str/split-lines out))))

(defn -main
  "No base ref (a push to main) always means code."
  [& [base-ref]]
  (let [code? (or (str/blank? base-ref) (not (docs-only? (changed-files base-ref))))
        line  (str "code=" code?)]
    (println line)
    (when-let [out (System/getenv "GITHUB_OUTPUT")]
      (spit out (str line "\n") :append true))))
