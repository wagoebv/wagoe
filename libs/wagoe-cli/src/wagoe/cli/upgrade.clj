(ns wagoe.cli.upgrade
  "`wagoe upgrade`: rewrite what a release deprecated, driven by upgrades.edn.

   rewrite-clj edits the parsed text, so formatting and comments survive."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [wagoe.cli.add :as add]))

(def ^:private table-path "wagoe/cli/upgrades.edn")

(def rewrite-kinds #{:config-key :config-value :ns-rename :fn-rename})

(defn load-table []
  (edn/read-string (slurp (io/resource table-path))))

(defn table-problems
  "Why each entry of `table` is malformed, as strings. Empty when it is fine."
  [table]
  (for [{:keys [ticket since note manual config-key] :as entry} table
        problem [(when-not (and ticket since note) "needs :ticket, :since and :note")
                 (when (= (some? manual) (boolean (some entry rewrite-kinds)))
                   "needs a rewrite or a :manual reason, not both")
                 (when-not (every? (fn [[from to]] (= (butlast from) (butlast to))) config-key)
                   ":config-key renames a key within its map; the paths must share a parent")]
        :when problem]
    (str (or ticket (pr-str entry)) ": " problem)))

(defn- sexpr [loc]
  (try (z/sexpr loc) (catch Exception _ nil)))

;; ─── config.edn ──────────────────────────────────────────────────────────────

(def ^:private config-sections [[] [:active] [:inactive]])

(defn- skip-uneval
  "`loc`, or the first sibling to its right that is not a `#_` form."
  [loc]
  (some-> loc (z/find z/right #(not= :uneval (z/tag %)))))

(defn- key-loc
  "Location of key `k` in the map at `loc`, or nil."
  [loc k]
  (when (and loc (= :map (z/tag loc)))
    (loop [kloc (skip-uneval (z/down loc))]
      (when kloc
        (if (= k (sexpr kloc))
          kloc
          (recur (some-> kloc z/right skip-uneval z/right skip-uneval)))))))

(defn- path-loc [loc path]
  (reduce (fn [loc k] (or (some-> (key-loc loc k) z/right skip-uneval) (reduced nil))) loc path))

(defn- edit-config
  "`text` with `f` applied at the first location `path` reaches, else unchanged.
   `f` returns a location, or nil to leave the text alone."
  [text path f]
  (or (some-> (z/of-string text) (path-loc path) f z/root-string) text))

(defn- config-edits
  "Each rewrite in `table` as [path f], once per config section."
  [table]
  (for [section config-sections
        entry   table
        edit    (concat
                 (for [[path renames] (:config-value entry)]
                   [(into section path)
                    #(when-let [new (get renames (sexpr %))] (z/replace % new))])
                 (for [[from to] (:config-key entry)]
                   [(into section (butlast from))
                    #(when-not (key-loc % (last to))
                       (some-> (key-loc % (last from)) (z/replace (last to))))]))]
    edit))

(defn rewrite-config
  "`text` of a config.edn with every config rewrite in `table` applied."
  [table text]
  (reduce (fn [text [path f]] (edit-config text path f)) text (config-edits table)))

;; ─── Clojure source ──────────────────────────────────────────────────────────

(defn- ns-form-loc [loc]
  (z/find loc z/right #(and (= :list (z/tag %)) (= 'ns (some-> % z/down sexpr)))))

(defn- require-loc [ns-loc]
  (some-> ns-loc z/down
          (z/find z/right #(and (= :list (z/tag %)) (= :require (some-> % z/down sexpr))))))

(defn- libspecs
  "The ns form's vector requires, as {:lib :as :refer}."
  [loc]
  (for [spec (some-> (require-loc (ns-form-loc loc)) sexpr rest)
        :when (and (vector? spec) (symbol? (first spec)) (even? (count (rest spec))))
        :let [opts (apply hash-map (rest spec))]]
    {:lib (first spec) :as (or (:as opts) (:as-alias opts)) :refer (:refer opts)}))

(defn- target
  "How the file will spell `new-fn`: [symbol require-to-add-or-nil].

   An alias it already has for the namespace, else the namespace's last segment
   when that alias is free, else the fully qualified name."
  [specs new-fn]
  (let [new-ns  (symbol (namespace new-fn))
        aliases (set (keep :as specs))
        short   (symbol (last (str/split (str new-ns) #"\.")))]
    (if-let [existing (some #(when (and (= new-ns (:lib %)) (:as %)) (:as %)) specs)]
      [(symbol (str existing) (name new-fn)) nil]
      (if (aliases short)
        [new-fn [new-ns]]
        [(symbol (str short) (name new-fn)) [new-ns :as short]]))))

(defn- symbol-renames
  "{old-symbol [new-symbol require-to-add-or-nil]} for one file, given its requires."
  [table specs]
  (let [ns-renames (apply merge (keep :ns-rename table))
        fn-renames (apply merge (keep :fn-rename table))]
    {:ns-renames ns-renames
     :fns        (into {}
                       (for [[old-fn new-fn] fn-renames
                             :let [old-ns (symbol (namespace old-fn))]
                             old (cons old-fn
                                       (for [{:keys [lib as]} specs
                                             :when (and as (= lib old-ns))]
                                         (symbol (str as) (name old-fn))))]
                         [old (if (= old old-fn)
                                [new-fn nil]
                                (target specs new-fn))]))}))

(defn- rename-symbol
  "[new-symbol require-or-nil] for `sym`, or nil when nothing renames it."
  [{:keys [ns-renames fns]} sym]
  (or (get fns sym)
      (when-let [new (get ns-renames sym)] [new nil])
      (when-let [new (some->> (namespace sym) symbol (get ns-renames))]
        [(symbol (str new) (name sym)) nil])))

(defn- add-requires
  "`text` with each libspec in `specs` appended to its ns form's :require."
  [text specs]
  (reduce (fn [text spec]
            (let [req    (require-loc (ns-form-loc (z/of-string text {:track-position? true})))
                  indent (some-> req z/down z/right z/position second dec)]
              (z/root-string
               (-> req
                   (z/append-child* (n/newlines 1))
                   (z/append-child* (n/spaces (or indent 1)))
                   (z/append-child* (n/coerce spec))))))
          text specs))

(defn- referred-notices [table specs path]
  (for [[old-fn new-fn] (apply merge (keep :fn-rename table))
        {:keys [lib refer]} specs
        :when (and (= lib (symbol (namespace old-fn)))
                   (or (= :all refer) (some #{(symbol (name old-fn))} refer)))]
    (str path ": refers " (name old-fn) " from " lib "; change it to " new-fn " by hand")))

(defn rewrite-source
  "{:text :notices} — `text` of a .clj(c) file with the ns and fn renames in
   `table` applied. `path` only labels the notices."
  [table text path]
  (let [root    (z/of-string text)
        specs   (when root (libspecs root))
        renames (symbol-renames table specs)]
    (if-not root
      {:text text :notices []}
      (loop [loc root, adds []]
        (if (z/end? loc)
          (let [have (set (map :lib (libspecs (z/of-string (z/root-string loc)))))
                adds (->> adds distinct (remove #(have (first %))))]
            {:text    (cond-> (z/root-string loc)
                        (and (seq adds) (require-loc (ns-form-loc root)))
                        (add-requires adds))
             :notices (vec (referred-notices table specs path))})
          (let [sym (when (= :token (z/tag loc)) (sexpr loc))
                [new add] (when (symbol? sym) (rename-symbol renames sym))]
            (if new
              (recur (z/next (z/replace loc new)) (cond-> adds add (conj add)))
              (recur (z/next loc) adds))))))))

;; ─── Unified diff ────────────────────────────────────────────────────────────

(defn- diff-ops
  "Edit script from `a` to `b` as [[:= line] [:- line] [:+ line] ...]."
  [a b]
  (let [a (vec a), b (vec b)
        pre  (count (take-while true? (map = a b)))
        suf  (count (take-while true? (map = (rseq (subvec a pre)) (rseq (subvec b pre)))))
        ma   (subvec a pre (- (count a) suf))
        mb   (subvec b pre (- (count b) suf))
        n    (count ma), m (count mb)
        ;; lcs[i][j]: longest common subsequence of ma[i..] and mb[j..]
        lcs  (let [t (make-array Long/TYPE (inc n) (inc m))]
               (doseq [i (range (dec n) -1 -1), j (range (dec m) -1 -1)]
                 (aset t i j (long (if (= (ma i) (mb j))
                                     (inc (aget t (inc i) (inc j)))
                                     (max (aget t (inc i) j) (aget t i (inc j)))))))
               t)
        mid  (loop [i 0, j 0, out []]
               (cond
                 (and (< i n) (< j m) (= (ma i) (mb j))) (recur (inc i) (inc j) (conj out [:= (ma i)]))
                 (and (< i n) (or (= j m) (>= (aget lcs (inc i) j) (aget lcs i (inc j)))))
                 (recur (inc i) j (conj out [:- (ma i)]))
                 (< j m) (recur i (inc j) (conj out [:+ (mb j)]))
                 :else out))]
    (concat (map #(vector := %) (subvec a 0 pre))
            mid
            (map #(vector := %) (subvec a (- (count a) suf))))))

(defn unified-diff
  "`old` → `new` as a unified diff with three lines of context."
  [path old new]
  (let [ops     (vec (diff-ops (str/split-lines old) (str/split-lines new)))
        ctx     3
        ;; 1-based [old new] line number at each op
        starts  (vec (reductions (fn [[i j] [t]] [(if (= t :+) i (inc i)) (if (= t :-) j (inc j))])
                                 [1 1] ops))
        changed (keep-indexed (fn [k [t]] (when (not= t :=) k)) ops)
        groups  (reduce (fn [gs k]
                          (if (and (seq gs) (<= (- k (peek (peek gs)) 1) (* 2 ctx)))
                            (conj (pop gs) (conj (peek gs) k))
                            (conj gs [k])))
                        [] changed)
        hunk    (fn [g]
                  (let [s   (max 0 (- (first g) ctx))
                        e   (min (dec (count ops)) (+ (peek g) ctx))
                        hop (subvec ops s (inc e))
                        [oi ni] (starts s)
                        oc  (count (remove #(= :+ (first %)) hop))
                        nc  (count (remove #(= :- (first %)) hop))]
                    (cons (format "@@ -%d,%d +%d,%d @@"
                                  (if (zero? oc) (dec oi) oi) oc (if (zero? nc) (dec ni) ni) nc)
                          (for [[t line] hop] (str ({:= " " :- "-" :+ "+"} t) line)))))]
    (str/join "\n" (concat [(str "--- a/" path) (str "+++ b/" path)] (mapcat hunk groups)))))

;; ─── Files ───────────────────────────────────────────────────────────────────

(defn- files-under [dir pred]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (sort-by str (filter #(and (.isFile ^java.io.File %) (pred (.getName ^java.io.File %)))
                           (file-seq d))))))

(defn- relative [dir f]
  (str (.relativize (.toPath (.getCanonicalFile (io/file dir)))
                    (.toPath (.getCanonicalFile (io/file f))))))

(defn plan
  "What `table` would change under project `dir`:
   {:changes [{:path :file :old :new}] :notices [...]}."
  [dir table]
  (let [configs (files-under (io/file dir "resources") #(= "config.edn" %))
        sources (mapcat #(files-under (io/file dir %) (fn [n] (re-find #"\.cljc?$" n)))
                        ["src" "test" "dev"])
        results (concat
                 (for [f configs]
                   (let [old (slurp f)] [f old {:text (rewrite-config table old) :notices []}]))
                 (for [f sources]
                   (let [old (slurp f)] [f old (rewrite-source table old (relative dir f))])))]
    {:changes (vec (for [[f old {:keys [text]}] results :when (not= old text)]
                     {:path (relative dir f) :file f :old old :new text}))
     :notices (vec (mapcat (comp :notices #(nth % 2)) results))}))

;; ─── Command ─────────────────────────────────────────────────────────────────

(def usage
  ["Usage: wagoe upgrade [--dry-run | --apply]"
   ""
   "Rewrite what Wagoe deprecated in this project's config.edn files and"
   "src/, test/ and dev/ sources. Formatting and comments are kept."
   "  --dry-run  print a diff per file and write nothing (the default)"
   "  --apply    write the changes"])

(defn run
  "Plans and prints the upgrade of `dir`; writes the files when `apply?`.
   Returns the plan."
  [dir table apply?]
  (let [{:keys [changes notices] :as result} (plan dir table)]
    (doseq [{:keys [path file old new]} changes]
      (if apply?
        (do (spit file new) (println (str "Rewrote " path)))
        (do (println (unified-diff path old new)) (println))))
    (cond
      (empty? changes) (println "Nothing to rewrite.")
      apply?           (println (str (count changes) " file(s) rewritten."))
      :else            (println (str (count changes) " file(s) would change. "
                                     "Run 'wagoe upgrade --apply' to write them.")))
    (doseq [n notices] (println n))
    (when-let [manual (seq (filter :manual table))]
      (println)
      (println "Not rewritten — check by hand:")
      (doseq [{:keys [since note manual]} manual]
        (println (str "  [" since "] " note))
        (println (str "    " manual))))
    result))

(defn -main [args]
  (let [flags (set args)
        bad   (remove #{"--dry-run" "--apply"} args)]
    (cond
      (or (seq bad) (and (flags "--dry-run") (flags "--apply")))
      (do (run! println usage) (System/exit 1))

      (not (add/wagoe-project? "."))
      (do (println "Not a Wagoe project: no deps.edn naming com.wagoe here.")
          (System/exit 1))

      :else
      (run "." (load-table) (contains? flags "--apply")))))
