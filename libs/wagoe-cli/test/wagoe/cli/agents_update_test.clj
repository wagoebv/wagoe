(ns wagoe.cli.agents-update-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.cli.agents-update :as agents-update]
            [wagoe.cli.catalogue :as cat]))

(def ^:private template
  (str "# {{project-name}} — Developer Reference\n"
       "intro\n"
       "<!-- gen:fc-is -->\nNEW fc-is rules for {{project-ns}}\n<!-- /gen:fc-is -->\n"
       "middle\n"
       "<!-- gen:naming -->\nNEW naming\n<!-- /gen:naming -->\n"
       "<!-- gen:pitfalls -->\nNEW pitfalls: never run `wagoe add payments` twice\n<!-- /gen:pitfalls -->\n"
       "<!-- wagoe:available-modules -->\n<!-- /wagoe:available-modules -->\n"
       "<!-- wagoe:installed-modules -->\n<!-- /wagoe:installed-modules -->\n"))

(def ^:private project-agents
  (str "# shop — Developer Reference\n"
       "intro\n"
       "<!-- gen:fc-is -->\nOLD fc-is rules\n<!-- /gen:fc-is -->\n"
       "middle\n"
       "## My custom team notes\ndo not lose this\n"
       "<!-- gen:naming -->\nOLD naming\n<!-- /gen:naming -->\n"
       "<!-- gen:pitfalls -->\nOLD pitfalls\n<!-- /gen:pitfalls -->\n"
       "<!-- wagoe:available-modules -->\n"
       "| search     | Full-text       | wagoe add search   |\n"
       "<!-- /wagoe:available-modules -->\n"
       "<!-- wagoe:installed-modules -->\n"
       "- core\n"
       "- payments (`com.wagoe/wagoe-payments`) — [docs](https://x)\n"
       "<!-- /wagoe:installed-modules -->\n"))

(def ^:private substitutions {:project-name "shop" :project-ns "shop"})

(def ^:private states
  "core and payments switched on, search in deps.edn only, geo absent."
  (for [[n state] [["core" :enabled] ["payments" :enabled] ["search" :configurable] ["geo" :absent]]]
    [(cat/find-module n) state]))

(defn- update-agents [current tmpl]
  (agents-update/update-agents-content current tmpl substitutions states))

(deftest ^:unit update-refreshes-stale-blocks-test
  (let [{:keys [content updated missing]} (update-agents project-agents template)]
    (testing "stale blocks are refreshed with rendered template content"
      (is (str/includes? content "NEW fc-is rules for shop"))
      (is (str/includes? content "NEW naming"))
      (is (= ["gen:fc-is" "gen:naming" "gen:pitfalls"
              "wagoe:available-modules" "wagoe:installed-modules"] updated)))
    (testing "module blocks come from the project, not the template"
      (is (re-find #"(?s)In deps\.edn but not switched on.*wagoe add search.*Not in deps\.edn.*wagoe add geo" content))
      (is (str/includes? content "- payments (`com.wagoe/wagoe-payments`)")))
    (testing "no markers are missing in a generated project"
      (is (empty? missing)))))

(deftest ^:unit update-preserves-user-content-and-project-state-test
  (let [{:keys [content]} (update-agents project-agents template)]
    (testing "text outside markers is untouched"
      (is (str/includes? content "## My custom team notes\ndo not lose this")))
    (testing "an enabled module is not offered for `wagoe add`"
      (is (not (str/includes? content "| payments"))))
    (testing "prose outside the module blocks survives"
      (is (str/includes? content "never run `wagoe add payments` twice")))))

(deftest ^:unit update-is-idempotent-test
  (let [first-pass  (:content (update-agents project-agents template))
        second-pass (update-agents first-pass template)]
    (is (= first-pass (:content second-pass)))
    (is (empty? (:updated second-pass)))))

(deftest ^:unit missing-markers-are-reported-not-fatal-test
  (let [no-markers "# shop — Developer Reference\nhand-rolled file\n"
        {:keys [content missing]} (update-agents no-markers template)]
    (is (= content no-markers))
    (is (= ["gen:fc-is" "gen:naming" "gen:pitfalls"
            "wagoe:available-modules" "wagoe:installed-modules"] missing))))

(deftest ^:unit without-states-the-module-blocks-are-left-alone
  (let [{:keys [content]} (agents-update/update-agents-content project-agents template substitutions nil)]
    (is (str/includes? content "| search     | Full-text       | wagoe add search   |"))))

(deftest ^:unit duplicated-markers-touch-first-pair-only-test
  (let [doubled (str project-agents
                     "\n## user copy\n"
                     "<!-- gen:naming -->\nUSER COPY of naming\n<!-- /gen:naming -->\n")
        {:keys [content]} (update-agents doubled template)]
    (is (str/includes? content "NEW naming") "first pair refreshed")
    (is (str/includes? content "USER COPY of naming") "user-duplicated pair untouched")))

(deftest ^:unit template-missing-marker-never-empties-project-block-test
  ;; If the shipped template ever drops a marker pair, the project's block
  ;; must be skipped (reported missing) — not spliced empty.
  (let [template-sans-naming (str/replace template
                                          #"(?s)<!-- gen:naming -->.*?<!-- /gen:naming -->\n"
                                          "")
        {:keys [content updated missing]}
        (update-agents project-agents template-sans-naming)]
    (is (str/includes? content "OLD naming") "project block body preserved")
    (is (some #{"gen:naming"} missing))
    (is (not (some #{"gen:naming"} updated)))))

(deftest ^:unit project-name-parsing-test
  (is (= "shop" (agents-update/project-name-from-agents project-agents)))
  (is (nil? (agents-update/project-name-from-agents "no title here"))))
