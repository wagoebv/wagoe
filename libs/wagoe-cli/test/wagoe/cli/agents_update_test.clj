(ns wagoe.cli.agents-update-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.cli.agents-update :as agents-update]
            [wagoe.cli.templates :as templates]))

(def ^:private template
  (str "# {{project-name}} — Developer Reference\n"
       "intro\n"
       "<!-- gen:fc-is -->\nNEW fc-is rules for {{project-ns}}\n<!-- /gen:fc-is -->\n"
       "middle\n"
       "<!-- gen:naming -->\nNEW naming\n<!-- /gen:naming -->\n"
       "<!-- gen:pitfalls -->\nNEW pitfalls: never run `wagoe add payments` twice\n<!-- /gen:pitfalls -->\n"
       "<!-- wagoe:available-modules -->\n"
       "| payments   | PSP abstraction | wagoe add payments |\n"
       "| search     | Full-text       | wagoe add search   |\n"
       "| geo        | Geocoding (new) | wagoe add geo      |\n"
       "<!-- /wagoe:available-modules -->\n"
       "<!-- wagoe:installed-modules -->\n- core\n<!-- /wagoe:installed-modules -->\n"))

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

(deftest ^:unit update-refreshes-stale-blocks-test
  (let [{:keys [content updated missing]} (agents-update/update-agents-content project-agents template substitutions)]
    (testing "stale blocks are refreshed with rendered template content"
      (is (str/includes? content "NEW fc-is rules for shop"))
      (is (str/includes? content "NEW naming"))
      (is (str/includes? content "wagoe add geo")
          "a module added to the framework since generation appears after update")
      (is (= ["gen:fc-is" "gen:naming" "gen:pitfalls" "wagoe:available-modules"] updated)))
    (testing "no markers are missing in a generated project"
      (is (empty? missing)))))

(deftest ^:unit update-preserves-user-content-and-project-state-test
  (let [{:keys [content]} (agents-update/update-agents-content project-agents template substitutions)]
    (testing "text outside markers is untouched"
      (is (str/includes? content "## My custom team notes\ndo not lose this")))
    (testing "installed-modules block is project state — never synced from template"
      (is (str/includes? content "- payments (`com.wagoe/wagoe-payments`)")))
    (testing "installed modules are re-removed from the refreshed available table"
      (is (not (str/includes? content "| payments")))
      (is (str/includes? content "wagoe add search")))
    (testing "prose mentioning `wagoe add <installed>` outside the table survives"
      (is (str/includes? content "never run `wagoe add payments` twice")
          "row removal must be scoped to the available-modules block"))))

(deftest ^:unit update-is-idempotent-test
  (let [first-pass  (:content (agents-update/update-agents-content project-agents template substitutions))
        second-pass (agents-update/update-agents-content first-pass template substitutions)]
    (is (= first-pass (:content second-pass)))
    (is (empty? (:updated second-pass)))))

(deftest ^:unit missing-markers-are-reported-not-fatal-test
  (let [no-markers "# shop — Developer Reference\nhand-rolled file\n"
        {:keys [content missing]} (agents-update/update-agents-content no-markers template substitutions)]
    (is (= content no-markers))
    (is (= ["gen:fc-is" "gen:naming" "gen:pitfalls" "wagoe:available-modules"] missing))))

(deftest ^:unit duplicated-markers-touch-first-pair-only-test
  (let [doubled (str project-agents
                     "\n## user copy\n"
                     "<!-- gen:naming -->\nUSER COPY of naming\n<!-- /gen:naming -->\n")
        {:keys [content]} (agents-update/update-agents-content doubled template substitutions)]
    (is (str/includes? content "NEW naming") "first pair refreshed")
    (is (str/includes? content "USER COPY of naming") "user-duplicated pair untouched")))

(deftest ^:unit template-missing-marker-never-empties-project-block-test
  ;; If the shipped template ever drops a marker pair, the project's block
  ;; must be skipped (reported missing) — not spliced empty.
  (let [template-sans-naming (str/replace template
                                          #"(?s)<!-- gen:naming -->.*?<!-- /gen:naming -->\n"
                                          "")
        {:keys [content updated missing]}
        (agents-update/update-agents-content project-agents template-sans-naming substitutions)]
    (is (str/includes? content "OLD naming") "project block body preserved")
    (is (some #{"gen:naming"} missing))
    (is (not (some #{"gen:naming"} updated)))))

(deftest ^:unit project-name-parsing-test
  (is (= "shop" (agents-update/project-name-from-agents project-agents)))
  (is (nil? (agents-update/project-name-from-agents "no title here"))))

(deftest ^:unit installed-module-names-parsing-test
  (is (= #{"core" "payments"} (agents-update/installed-module-names project-agents)))
  (is (= #{} (agents-update/installed-module-names "no blocks"))))

(deftest ^:unit module-row-pattern-word-boundary-test
  (let [row          "| search | Full-text | wagoe add search |\n"
        advanced-row "| search-advanced | Fancy | wagoe add search-advanced |\n"]
    (testing "matches the module's own row"
      (is (= "" (str/replace row (templates/module-row-pattern "search") ""))))
    (testing "does not match a row whose name merely starts with the module name"
      (is (= advanced-row
             (str/replace advanced-row (templates/module-row-pattern "search") ""))))))
