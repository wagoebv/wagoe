(ns wagoe.tools.workflow-pins-test
  "A tag can be moved to other code; a commit SHA cannot. The publish job holds
   the Clojars credentials, so every action a workflow runs is pinned (BOU-1017)."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

(def ^:private github-dir
  (first (filter fs/exists? [".github" "../../.github"])))

(defn- unpinned
  "Each `uses:` in `text` that names a remote action by anything but a commit SHA
   with its version in a comment, and each `bb: latest`."
  [text]
  (concat (->> (re-seq #"(?m)(?:^[\s-]*|\{\s*)uses:\s*[\"']?([^\s#\"'},]+)[\"']?([^\n]*)" text)
               (remove (fn [[_ ref tail]]
                         (or (re-find #"^\./" ref)
                             (and (re-find #"@[0-9a-f]{40}$" ref) (re-find #"#\s*v?\d" tail)))))
               (map second))
          (re-seq #"bb:\s*[\"']?latest" text)))

(deftest ^:unit the-check-reads-a-tag-as-unpinned
  (is (= ["actions/checkout@v6" "a/b@d23441a48e516b6c34aea4fa41551a30e30af803" "c/d@v1" "bb: latest"]
         (unpinned (str "uses: actions/checkout@v6\nuses: ./.github/actions/x\nbb: latest\n"
                        "uses: a/b@d23441a48e516b6c34aea4fa41551a30e30af803\n"
                        "- {uses: c/d@v1}\n"
                        "uses: 'e/f@d23441a48e516b6c34aea4fa41551a30e30af803' # v6.1.0"))))
  (is (= ["bb: 'latest"] (unpinned "bb: 'latest'"))))

(deftest ^:unit ^:security every-workflow-action-is-pinned-to-a-commit
  (is github-dir)
  (doseq [f (fs/glob github-dir "**.{yml,yaml}")]
    (is (empty? (unpinned (slurp (str f)))) (str f))))

(deftest ^:unit ^:security publish-fetches-bb-by-digest
  ;; A release asset can be replaced under its version; publish holds the Clojars credentials
  (let [publish (slurp (str (fs/path github-dir "workflows" "publish.yml")))]
    (is (not (re-find #"(?m)^\s+bb:" publish)) "no bb: input to setup-clojure")
    (is (re-find #"sha256sum -c" publish))))
