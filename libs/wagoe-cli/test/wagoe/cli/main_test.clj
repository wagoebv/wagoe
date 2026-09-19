(ns wagoe.cli.main-test
  "`wagoe version` reported 1.0.0-beta-5 four releases after beta-5.

   The number came from a string literal, and `bb check:versions` reads
   coordinates, `(def … -version)` forms, git tags and documented claims — a
   printed banner is none of those. So the one version a user asks the tool for
   directly was the only one nothing verified, and `bb bump` never touched it.

   It now comes from the catalogue the CLI already ships, which the gate reads."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.cli.catalogue :as cat]
            [wagoe.cli.main :as main]))

(deftest ^:unit version-command-reports-the-shipped-version
  (let [out (with-out-str (main/-main "version"))]
    (testing "the command answers with the version in the catalogue"
      (is (= (str "wagoe CLI version " (:cli-version (cat/load-catalogue)))
             (str/trim out))))

    (testing "and that version is a release, not a placeholder"
      ;; A catalogue read that silently yielded nil would print
      ;; "wagoe CLI version " and this test would still compare equal.
      (is (re-find #"\d+\.\d+\.\d+" out)))))
