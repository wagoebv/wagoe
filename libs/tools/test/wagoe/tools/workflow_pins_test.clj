(ns wagoe.tools.workflow-pins-test
  "A tag can be moved to other code; a commit SHA cannot. The publish job holds
   the Clojars credentials, so every action a workflow runs is pinned (BOU-1017)."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

(def ^:private github-dir
  (first (filter fs/exists? [".github" "../../.github"])))

(defn- unpinned
  "Each `uses:` in `text` that names a remote action by anything but a commit SHA,
   and each `bb: latest`."
  [text]
  (concat (->> (re-seq #"(?m)^[\s-]*uses:\s*([^\s#]+)" text)
               (map second)
               (remove #(or (re-find #"^\./" %) (re-find #"@[0-9a-f]{40}$" %))))
          (re-seq #"bb:\s*latest" text)))

(deftest ^:unit the-check-reads-a-tag-as-unpinned
  (is (= ["actions/checkout@v6" "bb: latest"]
         (unpinned "uses: actions/checkout@v6\nuses: ./.github/actions/x\nbb: latest\nuses: a/b@d23441a48e516b6c34aea4fa41551a30e30af803 # v6.1.0"))))

(deftest ^:unit ^:security every-workflow-action-is-pinned-to-a-commit
  (is github-dir)
  (doseq [f (fs/glob github-dir "**.yml")]
    (is (empty? (unpinned (slurp (str f)))) (str f))))
