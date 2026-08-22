#!/usr/bin/env bb
(ns example-regen
  "Regenerate examples/shop from the CLI and scaffolder in this checkout.

   The example is committed so a reader can open it on GitHub, and generated so
   it cannot drift from what `wagoe new` + `bb scaffold` actually produce. The
   external wagoe-examples repo is what an unregenerated example becomes: two
   workaround sections in AGENTS.md and no version it is known to work with.

   Regenerate:  bb example:regen
   Verify:      bb example:regen --check   (CI; fails if the tree has drifted)

   The one deliberate difference from raw generator output is the dependency
   coordinates: `wagoe new` pins published `com.wagoe/*` versions, and the
   committed copy points at `../../libs/*` instead. An example that resolved
   the last release would go green while this checkout was broken — the same
   trap `scripts/first-run-smoke.sh` documents at length."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]))

(def repo-root (str (fs/absolutize (fs/parent (fs/parent *file*)))))
(def target    (fs/file repo-root "examples" "shop"))

(def module
  "The module the example ships, and the flags that produced it."
  {:name   "product"
   :entity "Product"
   :fields ["name:string:required" "sku:string:required" "price:decimal"]})

(def excluded
  "Generated paths the committed copy leaves out.

   `.git` because the example is not its own repository. `.env` because it
   holds a generated JWT secret — `.env.example` is committed in its place.
   The build caches because they are build caches.

   `.mcp.json` and `.vscode` because this repository's own .gitignore excludes
   them everywhere, so they cannot be committed under examples/ either. A
   generated project does get them; the example simply cannot show them."
  #{".git" ".env" "target" ".cpcache" ".clj-kondo/.cache" "logs" "data"
    ".mcp.json" ".vscode"})

(def preserved
  "Committed files the generators do not write, kept across a regeneration.

   Only README.md: `wagoe new` writes no README, and regenerating replaces the
   tree wholesale, so without this the one hand-written page explaining what
   the directory is would be deleted by the command that maintains it."
  #{"README.md"})

(defn- lib-dirs
  "Artifact id -> directory name under libs/, for every library in this checkout.

   `libs/core` publishes as `wagoe-core` and `libs/wagoe-cli` as `wagoe-cli`,
   so the prefix is added only when it is not already there."
  []
  (into {}
        (for [d (fs/list-dir (fs/file repo-root "libs"))
              :when (fs/directory? d)
              :let  [n (fs/file-name d)]]
          [(if (str/starts-with? n "wagoe-") n (str "wagoe-" n)) n])))

(defn- rewrite-deps!
  "Point every com.wagoe dependency in `dir` at `libs-prefix`.

   Runs twice over the same tree — once in the temp directory, where the roots
   must be absolute because the scaffolder runs from there, and once after
   copying, where they become `../../libs/…` so the committed file is readable
   and resolves from any clone. So it rewrites both published pins and roots
   that are already local."
  [dir libs-prefix]
  (doseq [f ["deps.edn" "bb.edn"]
          :let [file (fs/file dir f)]
          :when (fs/exists? file)]
    (let [text (reduce (fn [s [art dir-name]]
                         (str/replace s
                                      (re-pattern (str "com\\.wagoe/" art "(\\s+)\\{:mvn/version \"[^\"]+\"\\}"))
                                      (str "com.wagoe/" art "$1{:local/root \"" libs-prefix dir-name "\"}")))
                       (slurp file)
                       (lib-dirs))]
      (spit file (str/replace text (str repo-root "/libs/") libs-prefix))))
  ;; Assert on what is left rather than on what was written. One artifact still
  ;; pinned is enough to make the example test the release, and it would look
  ;; exactly as green as a correct run.
  (doseq [f ["deps.edn" "bb.edn"]
          :let [file (fs/file dir f)]
          :when (fs/exists? file)]
    (when-let [left (seq (re-seq #"com\.wagoe/[a-z0-9-]+\s*\{:mvn/version" (slurp file)))]
      (throw (ex-info (str f " still pins published artifacts: " (pr-str left)) {})))))

(defn- generate!
  "Run `wagoe new` and the scaffolder into `work`, returning the project dir."
  [work]
  (let [cli (str (fs/file repo-root "libs" "wagoe-cli"))]
    (println "  wagoe new shop")
    (shell {:dir work}
           "clojure" "-Sdeps" (str "{:deps {com.wagoe/wagoe-cli {:local/root \"" cli "\"}}}")
           "-M" "-m" "wagoe.cli.main" "new" "shop")
    (let [proj (fs/file work "shop")]
      ;; Absolute roots for now: the scaffolder runs from this temp directory,
      ;; and ../../libs would not resolve from there.
      (rewrite-deps! proj (str repo-root "/libs/"))
      (println "  bb scaffold generate" (:name module))
      (apply shell
             {:dir proj
              :extra-env {"WAGOE_SCAFFOLDER_ROOT" (str (fs/file repo-root "libs" "scaffolder"))}}
             (concat ["bb" "scaffold" "generate"
                      "--module-name" (:name module)
                      "--entity" (:entity module)]
                     (mapcat (fn [f] ["--field" f]) (:fields module))))
      (println "  bb scaffold integrate" (:name module))
      (shell {:dir proj
              :extra-env {"WAGOE_SCAFFOLDER_ROOT" (str (fs/file repo-root "libs" "scaffolder"))}}
             "bb" "scaffold" "integrate" (:name module))
      proj)))

(defn- keep-migration-names!
  "Restore the committed migration filenames in `dir`.

   Migratus stamps a filename with the moment it ran, so regenerating renames
   every migration and git reports a delete plus an add each time. The
   timestamp only has to order migrations against each other, so keeping the
   committed one makes `bb example:regen` a no-op when nothing changed."
  [dir previous]
  (doseq [p    (when (fs/exists? (fs/file dir "migrations"))
                 (fs/list-dir (fs/file dir "migrations")))
          :let [n   (fs/file-name p)
                key (str/replace n #"^\d{14}" "")]
          :when (and (re-find #"^\d{14}" n) (previous key) (not= n (previous key)))]
    (fs/move p (fs/file dir "migrations" (previous key)) {:replace-existing true})))

(defn- migration-names
  "suffix -> committed filename, for the migrations already in `dir`."
  [dir]
  (into {}
        (for [p    (when (fs/exists? (fs/file dir "migrations"))
                     (fs/list-dir (fs/file dir "migrations")))
              :let [n (fs/file-name p)]
              :when (re-find #"^\d{14}" n)]
          [(str/replace n #"^\d{14}" "") n])))

(defn- copy-tree!
  "Copy `from` to `to`, dropping `excluded` and re-pointing the deps."
  [from to]
  (let [previous (migration-names to)
        keep     (into {} (for [f preserved
                                :let [file (fs/file to f)]
                                :when (fs/exists? file)]
                            [f (slurp file)]))]
    (fs/delete-tree to)
    (fs/create-dirs to)
    (doseq [p (fs/list-dir from)
            :let [n (fs/file-name p)]
            :when (not (excluded n))]
      (if (fs/directory? p)
        (fs/copy-tree p (fs/file to n))
        (fs/copy p (fs/file to n))))
    (keep-migration-names! to previous)
    (doseq [[f text] keep] (spit (fs/file to f) text))
    (rewrite-deps! to "../../libs/")))

(defn- undate
  "Blank the migration timestamp so two runs can be compared.

   Migratus filenames are `<yyyyMMddHHmmss>-create-products.up.sql`, so every
   regeneration produces a new name. Comparing raw paths would report drift on
   every run — a check that always fails is a check nobody reads."
  [s]
  (str/replace s #"\d{14}" "<timestamp>"))

(defn- tree
  "path -> contents, for comparing two generated trees.

   Excludes on the first path segment, matched whole. Written as
   `str/starts-with?` over the whole relative path, `.env.example` matched the
   `.env` exclusion and dropped out of the comparison on both sides — so a
   change to it was reported as no change at all, by the check whose job is to
   notice."
  [dir]
  (into (sorted-map)
        (for [p (file-seq (fs/file dir))
              :when (.isFile p)
              :let  [rel (str (fs/relativize dir (fs/path p)))]
              :when (and (not (excluded (first (str/split rel #"/"))))
                         (not (preserved rel)))]
          [(undate rel) (undate (slurp p))])))

(defn- line-diff
  "The first few lines where `committed` and `fresh` disagree.

   Naming a file and not saying how it differs sends whoever hit the failure
   off to reproduce a two-minute regeneration by hand to find out."
  [committed fresh]
  (let [a (str/split-lines committed)
        b (str/split-lines fresh)]
    (->> (map vector (range) (concat a (repeat nil)) (concat b (repeat nil)))
         (take (max (count a) (count b)))
         (remove (fn [[_ x y]] (= x y)))
         (take 5)
         (mapcat (fn [[n x y]]
                   [(str "    " (inc n) " committed: " (pr-str x))
                    (str "    " (inc n) " generated: " (pr-str y))]))
         (str/join "\n"))))

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        work   (str (fs/create-temp-dir {:prefix "wagoe-example-"}))]
    (try
      (println (if check? "── Verifying examples/shop is what the generators produce"
                   "── Regenerating examples/shop"))
      (let [proj  (generate! work)
            fresh (fs/file work "fresh")]
        (copy-tree! proj fresh)
        (if check?
          (let [want (tree fresh)
                have (tree target)
                diff (->> (concat (keys want) (keys have))
                          distinct
                          sort
                          (keep (fn [k]
                                  (cond
                                    (not (contains? have k)) (str "  missing:  " k)
                                    (not (contains? want k)) (str "  extra:    " k)
                                    (not= (want k) (have k))
                                    (str "  differs:  " k "\n" (line-diff (have k) (want k)))))))]
            (if (seq diff)
              (do (println "\nexamples/shop no longer matches generator output:")
                  (println (str/join "\n" diff))
                  (println "\nRun `bb example:regen` and commit the result.")
                  (System/exit 1))
              (println "  ✓ examples/shop matches what wagoe new + bb scaffold produce")))
          (do (copy-tree! proj target)
              (println "  ✓ wrote" (str (fs/relativize repo-root (fs/path target))))
              (println "    Review the diff — a change here is a change in what every"
                       "\n    new project gets."))))
      (finally
        (fs/delete-tree work)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
