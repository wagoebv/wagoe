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

(def ^:private rewrite-kinds #{:config-key :config-value :ns-rename :fn-rename})

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

(defn key-loc
  "Location of key `k` in the map at `loc`, or nil."
  [loc k]
  (when (and loc (= :map (z/tag loc)))
    (loop [kloc (skip-uneval (z/down loc))]
      (when kloc
        (if (= k (sexpr kloc))
          kloc
          (recur (some-> kloc z/right skip-uneval z/right skip-uneval)))))))

(defn path-loc [loc path]
  (reduce (fn [loc k] (or (some-> (key-loc loc k) z/right skip-uneval) (reduced nil))) loc path))

(defn- edit-config
  "`root` with `f` applied at the first location `path` reaches, else unchanged.
   `f` returns a location, or nil to leave it alone."
  [root path f]
  (or (some-> root (path-loc path) f z/root z/of-node) root))

(defn- renamed-literal
  "What `v` becomes under `renames`, for a keyword or a string naming one."
  [renames v]
  (cond (keyword? v) (get renames v)
        (string? v)  (some-> (get renames (keyword v)) name)))

(defn- map-key? [loc]
  (and (= :map (some-> loc z/up z/tag))
       (even? (count (take-while some? (iterate z/left (z/left loc)))))))

(defn- literal-locs
  "Keyword and string locations inside `sub`, a subzip, map keys excluded.
   Lazy over a zipper, so walk it before editing."
  [sub]
  (->> (iterate z/next sub)
       (take-while (complement z/end?))
       (filter #(and (= :token (z/tag %)) (not (map-key? %))
                     (let [v (sexpr %)] (or (keyword? v) (string? v)))))))

(defn- rename-value
  "`loc` with its deprecated value renamed, or nil. Inside an Aero tag such as
   `#or [#env X \"database\"]` the literal default is what gets renamed."
  [renames loc]
  (if (= :token (z/tag loc))
    (some->> (sexpr loc) (renamed-literal renames) (z/replace loc))
    (let [edited (z/subedit-node
                  loc (fn [sub]
                        (loop [l sub]
                          (cond (z/end? l) l
                                (and (= :token (z/tag l)) (not (map-key? l)))
                                (recur (z/next (if-let [new (renamed-literal renames (sexpr l))]
                                                 (z/replace l new)
                                                 l)))
                                :else (recur (z/next l))))))]
      (when (not= (z/node edited) (z/node loc)) edited))))

(defn- config-edits
  "Each rewrite in `table` as [path f], once per config section."
  [table]
  (for [section config-sections
        entry   table
        edit    (concat
                 (for [[path renames] (:config-value entry)]
                   [(into section path)
                    #(rename-value renames %)])
                 (for [[from to] (:config-key entry)]
                   [(into section (butlast from))
                    #(when-not (key-loc % (last to))
                       (some-> (key-loc % (last from)) (z/replace (last to))))]))]
    edit))

(defn config-notices
  "A notice for each deprecated config value set only through an Aero tag, such
   as `#keyword #env JOBS_PROVIDER`: there is no literal to rewrite, and the
   value the environment supplies may still be an old name."
  [table text path]
  (if-let [root (z/of-string text)]
    (for [section config-sections
          entry   table
          [kpath renames] (:config-value entry)
          :let [loc (path-loc root (into section kpath))]
          :when (and loc (not= :token (z/tag loc))
                     (empty? (literal-locs (z/subzip loc))))]
      (str path ": " (pr-str (into section kpath)) " is set through "
           (z/string loc) "; make sure it is not "
           (str/join " or " (map pr-str (sort (keys renames))))))
    []))

(defn rewrite-config
  "`text` of a config.edn with every config rewrite in `table` applied."
  [table text]
  (if-let [root (z/of-string text)]
    (z/root-string (reduce (fn [root [path f]] (edit-config root path f)) root (config-edits table)))
    text))

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
    {:lib (first spec) :as (:as opts) :as-alias (:as-alias opts) :refer (:refer opts)}))

(defn- target
  "How the file will spell `new-fn`: [symbol require-to-add-or-nil], given the
   requires it has so far as {:lib :as :as-alias}. `qualified?` keeps the fully
   qualified name.

   An alias it already has for the namespace, else the namespace's last segment
   when that alias is free, else the fully qualified name."
  [specs new-fn qualified?]
  (let [new-ns   (symbol (namespace new-fn))
        mine     (filter #(= new-ns (:lib %)) specs)
        existing (some :as mine)
        taken    (set (mapcat (juxt :as :as-alias) specs))
        short    (symbol (last (str/split (str new-ns) #"\.")))
        full     [new-fn (when-not (some #(not (:as-alias %)) mine) {:lib new-ns})]]
    (cond
      qualified?    full
      existing      [(symbol (str existing) (name new-fn)) nil]
      (taken short) full
      :else         [(symbol (str short) (name new-fn)) {:lib new-ns :as short}])))

(defn- source-renames
  "The table's ns and fn renames, each merged into one map."
  [table]
  {:ns-renames (apply merge (keep :ns-rename table))
   :fn-renames (apply merge (keep :fn-rename table))})

(defn- fn-renames-in
  "{old-symbol [new-symbol qualified?]} for one file, given its requires."
  [fn-renames specs]
  (into {}
        (for [[old-fn new-fn] fn-renames
              :let [old-ns (symbol (namespace old-fn))]
              [old qualified?] (cons [old-fn true]
                                     (for [{:keys [lib as]} specs
                                           :when (and as (= lib old-ns))]
                                       [(symbol (str as) (name old-fn)) false]))]
          [old [new-fn qualified?]])))

(defn- rename-ns
  "`sym` with its namespace, or itself, renamed; nil when nothing renames it."
  [ns-renames sym]
  (or (get ns-renames sym)
      (some->> (namespace sym) symbol (get ns-renames) str (#(symbol % (name sym))))))

(defn- needed-requires
  "`adds` minus what `specs`, the requires after renaming, or an aliased add
   already loads."
  [specs adds]
  (let [adds    (distinct adds)
        aliased (set (keep #(when (:as %) (:lib %)) adds))]
    (remove (fn [{:keys [lib as]}]
              (if as
                (some #(and (= lib (:lib %)) (= as (:as %))) specs)
                (or (aliased lib) (some #(and (= lib (:lib %)) (not (:as-alias %))) specs))))
            adds)))

(defn- add-requires
  "`text` with each libspec in `specs` appended to its ns form's :require."
  [text specs]
  (let [req    (require-loc (ns-form-loc (z/of-string text {:track-position? true})))
        indent (or (some-> req z/down z/right z/position second dec) 1)]
    (z/root-string
     (reduce (fn [req spec]
               (-> req
                   (z/append-child* (n/newlines 1))
                   (z/append-child* (n/spaces indent))
                   (z/append-child* (n/coerce spec))))
             req specs))))

(defn- referred-notices [fn-renames specs path]
  (for [[old-fn new-fn] fn-renames
        {:keys [lib refer]} specs
        :when (and (= lib (symbol (namespace old-fn)))
                   (or (= :all refer) (some #{(symbol (name old-fn))} refer)))]
    (str path ": refers " (name old-fn) " from " lib "; change it to " new-fn " by hand")))

(defn rewrite-source
  "{:text :notices} — `text` of a .clj(c) file with the ns and fn renames in
   `table` applied. `path` only labels the notices."
  [table text path]
  (if-let [root (z/of-string text)]
    (let [{:keys [ns-renames fn-renames]} (source-renames table)
          specs (libspecs root)
          fns   (fn-renames-in fn-renames specs)]
      (loop [loc root, adds []]
        (if (z/end? loc)
          (let [adds (when (seq adds) (needed-requires (libspecs (z/of-node (z/root loc))) adds))]
            {:text    (cond-> (z/root-string loc)
                        (and (seq adds) (require-loc (ns-form-loc root)))
                        (add-requires (map (fn [{:keys [lib as]}] (cond-> [lib] as (conj :as as))) adds)))
             :notices (vec (referred-notices fn-renames specs path))})
          (let [sym (when (= :token (z/tag loc)) (sexpr loc))
                [new add] (when (symbol? sym)
                            (if-let [[new-fn qualified?] (get fns sym)]
                              (target (concat specs adds) new-fn qualified?)
                              [(rename-ns ns-renames sym)]))]
            (if new
              (recur (z/next (z/replace loc new)) (cond-> adds add (conj add)))
              (recur (z/next loc) adds))))))
    {:text text :notices []}))

;; ─── Unified diff ────────────────────────────────────────────────────────────

(defn- diff-ops
  "Edit script from `a` to `b` as [[:= line] [:- line] [:+ line] ...].

   Myers' O(ND) diff: an upgrade changes few lines, and a full LCS table took
   minutes on a 3000-line file under Babashka."
  [a b]
  (let [a (vec a), b (vec b), n (count a), m (count b)
        ;; furthest x on diagonal k = x - y, reached from k+1 (insert) or k-1 (delete)
        from  (fn [v d k] (if (or (= k (- d)) (and (not= k d) (< (v (dec k)) (v (inc k)))))
                            (inc k)
                            (dec k)))
        round (fn [v d]
                (loop [k (- d), v v]
                  (if (> k d)
                    [v false]
                    (let [x (loop [x (if (= (from v d k) (inc k)) (v (inc k)) (inc (v (dec k))))]
                              (if (and (< x n) (< (- x k) m) (= (a x) (b (- x k)))) (recur (inc x)) x))
                          v (assoc v k x)]
                      (if (and (>= x n) (>= (- x k) m)) [v true] (recur (+ k 2) v))))))
        ;; trace[d]: the frontier before round d
        trace (loop [d 0, v {1 0}, trace []]
                (let [[v' done?] (round v d)]
                  (if done? (conj trace v) (recur (inc d) v' (conj trace v)))))]
    (loop [d (dec (count trace)), x n, y m, out ()]
      (if (neg? d)
        out
        (let [v  (trace d)
              k  (- x y)
              pk (from v d k)
              px (v pk), py (- px pk)
              snake (min (- x (max px 0)) (- y (max py 0)))
              out (into out (map #(vector := (a %))) (range (dec x) (- x snake 1) -1))]
          (recur (dec d) px py
                 (cond (zero? d)         out
                       (= (- x snake) px) (conj out [:+ (b py)])
                       :else              (conj out [:- (a px)]))))))))

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

(defn relative [dir f]
  (str (.relativize (.toPath (.getCanonicalFile (io/file dir)))
                    (.toPath (.getCanonicalFile (io/file f))))))

(defn- rewrite-file
  "{:path :file :old :new :notices} for `f`, `rewrite` returning {:new :notices}.
   A file that does not parse is left alone, with a notice."
  [dir f rewrite]
  (let [old (slurp f), path (relative dir f)]
    (merge {:path path :file f :old old :new old :notices []}
           (try (rewrite old path)
                (catch Exception e
                  {:notices [(str path ": not rewritten, it does not parse: " (ex-message e))]})))))

(defn plan
  "What `table` would change under project `dir`:
   {:changes [{:path :file :old :new}] :notices [...]}."
  [dir table]
  (let [configs (files-under (io/file dir "resources") #(= "config.edn" %))
        sources (when (some (some-fn :ns-rename :fn-rename) table)
                  (mapcat #(files-under (io/file dir %) (fn [n] (re-find #"\.cljc?$" n)))
                          ["src" "test" "dev"]))
        results (concat
                 (for [f configs]
                   (rewrite-file dir f (fn [old path]
                                         (let [new (rewrite-config table old)]
                                           {:new new :notices (vec (config-notices table new path))}))))
                 (for [f sources]
                   (rewrite-file dir f (fn [old path]
                                         (let [{:keys [text notices]} (rewrite-source table old path)]
                                           {:new text :notices notices})))))]
    {:changes (into [] (comp (remove #(= (:old %) (:new %))) (map #(dissoc % :notices))) results)
     :notices (into [] (mapcat :notices) results)}))

;; ─── Command ─────────────────────────────────────────────────────────────────

(def usage
  ["Usage: wagoe upgrade [--dry-run | --apply]"
   ""
   "Rewrite what Wagoe deprecated in this project's config.edn files and"
   "src/, test/ and dev/ sources. Formatting and comments are kept."
   "  --dry-run  print a diff per file and write nothing (the default)"
   "  --apply    write the changes"
   ""
   "wagoe upgrade authorization <step> moves the app from :wagoe/authorization"
   ":off to :enforce: check, prepare, report, enforce. --help lists the options."])

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
      (not (add/wagoe-project? "."))
      (do (println "Not a Wagoe project: no deps.edn naming com.wagoe here.")
          (System/exit 1))

      (= "authorization" (first args))
      (do (require 'wagoe.cli.upgrade-authorization)
          (let [code ((resolve 'wagoe.cli.upgrade-authorization/run) "." (rest args))]
            (when-not (zero? code) (System/exit code))))

      (or (seq bad) (and (flags "--dry-run") (flags "--apply")))
      (do (run! println usage) (System/exit 1))

      :else
      (run "." (load-table) (contains? flags "--apply")))))
