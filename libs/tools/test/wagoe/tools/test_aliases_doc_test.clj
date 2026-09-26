(ns wagoe.tools.test-aliases-doc-test
  "Every documented `clojure -M:test...` command must name the aliases its
   suites load, or it fails on ClassNotFoundException before a test runs
   (BOU-536). A command naming no suite loads all of them, so it needs
   :test/all."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- repo-root []
  (let [cwd (System/getProperty "user.dir")]
    (or (first (filter #(.isDirectory (io/file % "libs"))
                       [cwd (str cwd "/../..")]))
        (throw (ex-info "cannot locate repo root (no libs/ dir)" {:cwd cwd})))))

(def ^:private alias-markers
  "What a test source has to contain to need each alias from deps.edn."
  {:test/pg   #"io\.zonky|embedded-pg"
   :test/otel #"io\.opentelemetry\.sdk\.testing"
   :test/http #"clj-http\.lite"})

;; Historical documents quote the commands of their day on purpose; the rest
;; document a generated project or a standalone lib, whose own deps.edn has a
;; :test alias that loads nothing from tests.edn.
(def ^:private excluded-prefixes
  ["CHANGELOG.md" "dev-docs/adr/" "docs/superpowers/"
   "examples/" "libs/wagoe-cli/" "libs/wagoe-mcp/" "claude-plugin/"
   "UPGRADING.md" "docs/modules/getting-started/"
   "docs/modules/ROOT/pages/index.adoc" "libs/tools/AGENTS.md"])

(defn- suite-test-paths []
  (let [cfg (edn/read-string {:default (fn [_ v] v)}
                             (slurp (io/file (repo-root) "tests.edn")))]
    (into {} (map (juxt :id :test-paths)) (:tests cfg))))

(defn- needed-aliases [test-paths]
  (set (for [dir   test-paths
             f     (file-seq (io/file (repo-root) dir))
             :when (str/ends-with? (str f) ".clj")
             :let  [src (slurp f)]
             [alias re] alias-markers
             :when (re-find re src)]
         alias)))

(def ^:private flags-with-arg
  #{"--focus" "--focus-meta" "--skip" "--skip-meta" "-m" "--plugin" "-i" "-e"})

(defn parse-command
  "The aliases and suite ids of the command after `clojure -M:test`, or nil
   when it is not one to check: a placeholder, or its own --config-file."
  [aliases args]
  (loop [[t & more] (str/split (str/trim args) #"\s+")
         suites     []]
    (cond
      (or (nil? t) (= "" t))           {:aliases aliases :suites suites}
      (re-find #"[{<]" t)              nil
      (= "--config-file" t)            nil
      (flags-with-arg t)               (recur (rest more) suites)
      (re-matches #":[a-z0-9-]+" t)    (recur more (conj suites (keyword (subs t 1))))
      (str/starts-with? t "-")         (recur more suites)
      :else                            {:aliases aliases :suites suites})))

(def ^:private command-re
  #"clojure -M:test((?::[a-z]+/[a-z-]+)*)([^`#|&;\n]*)")

(defn doc-commands
  "[line command] for each checked `clojure -M:test...` in `text`. A command
   under `cd libs/<x>` in the same code block runs that lib's own alias, and
   a bare `clojure -M:test` in backticks names the alias rather than running
   it."
  [text]
  (let [text (str/replace text #"\\\n\s*" " ")]
    (loop [[line & more] (str/split-lines text)
           in-lib? false
           out     []]
      (if (nil? line)
        out
        (let [fence?  (re-matches #"\s*(```.*|----|\.\.\.\.)" line)
              in-lib? (cond fence? false
                            (re-find #"^\s*cd libs/" line) true
                            :else in-lib?)
              line'   (str/replace line "`clojure -M:test`" "")
              cmds    (when-not in-lib?
                        (keep (fn [[_ as args]]
                                (parse-command
                                 (set (map keyword (re-seq #"[a-z]+/[a-z-]+" as)))
                                 args))
                              (re-seq command-re line')))]
          (recur more in-lib? (into out (map (fn [c] [line c])) cmds)))))))

(defn- documented-commands
  "[file line command] across every tracked *.md and *.adoc in the repo."
  []
  (let [root  (repo-root)
        files (-> (sh/sh "git" "ls-files" "*.md" "*.adoc" :dir root)
                  :out str/split-lines)]
    (for [f     files
          :when (not-any? #(str/starts-with? f %) excluded-prefixes)
          [line cmd] (doc-commands (slurp (io/file root f)))]
      [f line cmd])))

(defn- missing-aliases [paths needs {:keys [aliases suites]}]
  (when-not (contains? aliases :test/all)
    (let [needed (if (seq suites)
                   (into #{} (mapcat needs) suites)
                   (into #{} (mapcat needs) (keys paths)))]
      (seq (remove aliases needed)))))

(deftest ^:unit documented-commands-name-the-aliases-they-need
  (let [paths    (suite-test-paths)
        needs    (memoize (comp needed-aliases paths))
        commands (documented-commands)]
    (is (< 50 (count commands)) "the doc scan found the commands")
    (doseq [[f line cmd] commands]
      (testing (str f ": " (str/trim line))
        (doseq [suite (:suites cmd)]
          (is (contains? paths suite) (str suite " is not a suite in tests.edn")))
        (let [missing (missing-aliases paths needs cmd)]
          (is (empty? missing) (str "runs without " (vec missing))))))))

(deftest ^:unit parser-reads-the-shapes-docs-use
  (let [cmd #(second (first (doc-commands %)))]
    (is (= {:aliases #{:test/pg} :suites [:admin]}
           (cmd "clojure -M:test:test/pg :admin   # Admin")))
    (is (= {:aliases #{} :suites [:user]}
           (cmd "clojure -M:test :user --focus-meta :unit")))
    (is (= {:aliases #{} :suites []}
           (cmd "clojure -M:test --focus-meta :unit")))
    (is (= {:aliases #{} :suites []}
           (cmd "UPDATE_SNAPSHOTS=true clojure -M:test \\\n  --focus some-test"))
        "a continued line is one command")
    (is (nil? (cmd "clojure -M:test --watch :{module-name}")))
    (is (nil? (cmd "Bare `clojure -M:test` runs the lean set")))
    (is (nil? (cmd "```bash\ncd libs/core\nclojure -M:test\n```")))))

(deftest ^:unit markers-still-find-the-known-users
  (testing "a marker that stops matching would pass every command"
    (let [paths (suite-test-paths)]
      (is (contains? (needed-aliases (paths :admin)) :test/pg))
      (is (contains? (needed-aliases (paths :observability)) :test/otel))
      (is (contains? (needed-aliases (paths :devtools)) :test/http)))))
