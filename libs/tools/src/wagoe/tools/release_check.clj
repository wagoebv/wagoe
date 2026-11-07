(ns wagoe.tools.release-check
  "What must hold before a release publishes (BOU-899): no open Urgent or High
   security issue or bug in the Security & stability project, no known
   vulnerability, and a ### Security section in the release's CHANGELOG entry,
   even one that says none."
  (:require [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [wagoe.tools.deploy :as deploy]))

(def ^:private project "Security & stability — every release")

(def ^:private open-issues-query
  "query($project: String!) {
     issues(first: 100, filter: {
       project: {name: {eq: $project}},
       state: {type: {nin: [\"completed\", \"canceled\"]}},
       priority: {in: [1, 2]},
       labels: {some: {name: {in: [\"security\", \"Bug\"]}}}}) {
       nodes { identifier title }}}")

(defn changelog-section
  "The text under `## [version]`, up to the next `## [` heading; nil without one."
  [changelog version]
  (second (re-find (re-pattern (str "(?s)\\n## \\[" (java.util.regex.Pattern/quote version)
                                    "\\][^\\n]*\\n(.*?)(?=\\n## \\[|\\z)"))
                   changelog)))

(defn security-section? [changelog version]
  (boolean (some->> (changelog-section changelog version) (re-find #"(?m)^### Security\s*$"))))

(defn open-issues
  "Open Urgent/High issues labelled security or Bug in the project. A reply that
   is not an answer throws: an unread gate must not read as an empty one."
  [token]
  (let [resp (http/post "https://api.linear.app/graphql"
                        {:headers {"Authorization" token "Content-Type" "application/json"}
                         :body    (json/generate-string {:query     open-issues-query
                                                         :variables {:project project}})
                         :throw   false})
        body (some-> (:body resp) (json/parse-string true))
        nodes (get-in body [:data :issues :nodes])]
    (when (or (not= 200 (:status resp)) (:errors body) (nil? nodes))
      (throw (ex-info (str "Linear did not answer: HTTP " (:status resp) " " (:errors body))
                      {:type :internal-error})))
    nodes))

(defn -main [& [version]]
  (let [version   (or version (deploy/read-version "core"))
        token     (System/getenv "LINEAR_API_KEY")
        problems  (cond-> []
                    (str/blank? token)
                    (conj "LINEAR_API_KEY is not set; open security issues cannot be checked.")

                    (not (str/blank? token))
                    (into (map (fn [{:keys [identifier title]}] (str "Open: " identifier " " title)))
                          (open-issues token))

                    (not (security-section? (slurp "CHANGELOG.md") version))
                    (conj (str "CHANGELOG.md: ## [" version "] has no ### Security section. "
                               "Add one, saying None. if there is nothing to report."))

                    (not (zero? (:exit (process/shell {:continue true} "bb" "check:vulns"))))
                    (conj "bb check:vulns is red."))]
    (if (seq problems)
      (do (println (str "Release " version " is blocked:"))
          (doseq [p problems] (println " -" p))
          (System/exit 1))
      (println (str "Release " version ": no open security issues, no known vulnerabilities, "
                    "and CHANGELOG.md has a ### Security section.")))))
