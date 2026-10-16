(ns wagoe.cli.upgrade-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.cli.upgrade :as upgrade]))

(def ^:private table
  [{:ticket "T-1" :since "1.0.0" :note "providers"
    :config-value {[:wagoe/cache :provider] {:in-memory :memory}
                   [:wagoe/jobs :provider]  {:database :db}}}
   {:ticket "T-2" :since "1.0.0" :note "key"
    :config-key {[:wagoe/search :old-index] [:wagoe/search :index]}}
   {:ticket "T-3" :since "1.0.0" :note "ns"
    :ns-rename '{acme.old acme.new}}
   {:ticket "T-4" :since "1.0.0" :note "fn"
    :fn-rename '{acme.adapters.db/enqueue! acme.ports/enqueue!}}
   {:ticket "T-5" :since "1.0.0" :note "by hand" :manual "no rewrite"}])

(def ^:private config
  "{;; dev profile
 :active
 {:wagoe/cache   {:provider :in-memory   ; aligned on purpose
                  :ttl      #or [#env CACHE_TTL 60]}
  :wagoe/jobs    {:provider :database}
  :wagoe/search  {#_ :ignored :old-index \"docs\"}
  ;; metrics have their own provider vocabulary
  :wagoe/metrics {:provider :in-memory}}

 :inactive
 {:wagoe/cache {:provider :in-memory}}}
")

(deftest ^:unit config-value-and-key-rewrites-keep-formatting
  (let [out (upgrade/rewrite-config table config)]
    (is (= (-> config
               (str/replace ":provider :in-memory   ; aligned" ":provider :memory   ; aligned")
               (str/replace ":provider :database" ":provider :db")
               (str/replace ":old-index" ":index")
               (str/replace "{:wagoe/cache {:provider :in-memory}}" "{:wagoe/cache {:provider :memory}}"))
           out)
        "only the listed paths change, under :active and :inactive; comments, tags and spacing stay")
    (is (str/includes? out ":wagoe/metrics {:provider :in-memory}"))
    (is (= out (upgrade/rewrite-config table out)) "a second run changes nothing")))

(deftest ^:unit config-key-rename-leaves-a-map-that-has-both-alone
  (let [both "{:wagoe/search {:old-index \"a\" :index \"b\"}}"]
    (is (= both (upgrade/rewrite-config table both)))))

(deftest ^:unit values-behind-aero-tags
  (testing "a literal default inside the tag is renamed"
    (let [cfg "{:wagoe/jobs  {:provider #keyword #or [#env JOBS_PROVIDER \"database\"]}
 :wagoe/cache {:provider #profile {:dev :in-memory :prod :redis}}}"
          out (upgrade/rewrite-config table cfg)]
      (is (= "{:wagoe/jobs  {:provider #keyword #or [#env JOBS_PROVIDER \"db\"]}
 :wagoe/cache {:provider #profile {:dev :memory :prod :redis}}}"
             out))
      (is (empty? (upgrade/config-notices table out "c.edn")))
      (is (= out (upgrade/rewrite-config table out)))))
  (testing "a tag with no literal is reported, not skipped"
    (let [cfg "{:active {:wagoe/jobs {:provider #keyword #env JOBS_PROVIDER}}}"]
      (is (= cfg (upgrade/rewrite-config table cfg)))
      (is (= ["c.edn: [:active :wagoe/jobs :provider] is set through #keyword #env JOBS_PROVIDER; make sure it is not :database"]
             (upgrade/config-notices table cfg "c.edn"))))))

(def ^:private source
  "(ns my.app.jobs
  \"Docstring.\"
  (:require [acme.old :as old]       ; legacy
            [acme.adapters.db :as db]
            [clojure.string :as str]))

;; Enqueue after commit.
(defn run [tx job]
  (db/enqueue!   tx job)            ; spacing kept
  (acme.adapters.db/enqueue! tx job)
  (old/thing (acme.old/other))
  (db/other tx)
  (str/trim \"x\"))
")

(deftest ^:unit ns-and-fn-renames-follow-aliases
  (let [{:keys [text notices]} (upgrade/rewrite-source table source "src/my/app/jobs.clj")]
    (is (= "(ns my.app.jobs
  \"Docstring.\"
  (:require [acme.new :as old]       ; legacy
            [acme.adapters.db :as db]
            [clojure.string :as str]
            [acme.ports :as ports]))

;; Enqueue after commit.
(defn run [tx job]
  (ports/enqueue!   tx job)            ; spacing kept
  (acme.ports/enqueue! tx job)
  (old/thing (acme.new/other))
  (db/other tx)
  (str/trim \"x\"))
"
           text))
    (is (empty? notices))
    (testing "idempotent"
      (is (= text (:text (upgrade/rewrite-source table text "src/my/app/jobs.clj")))))))

(deftest ^:unit fn-rename-reuses-an-existing-alias-and-avoids-a-taken-one
  (testing "the file already requires the new namespace"
    (let [src "(ns a (:require [acme.adapters.db :as db] [acme.ports :as p]))\n(db/enqueue! 1)\n"]
      (is (= "(ns a (:require [acme.adapters.db :as db] [acme.ports :as p]))\n(p/enqueue! 1)\n"
             (:text (upgrade/rewrite-source table src "a.clj"))))))
  (testing "the short alias means something else here"
    (let [src "(ns a\n  (:require [acme.adapters.db :as db]\n            [other.ports :as ports]))\n(db/enqueue! 1)\n"]
      (is (= "(ns a\n  (:require [acme.adapters.db :as db]\n            [other.ports :as ports]\n            [acme.ports]))\n(acme.ports/enqueue! 1)\n"
             (:text (upgrade/rewrite-source table src "a.clj")))))))

(deftest ^:unit fn-renames-never-leave-an-unresolved-or-doubled-alias
  (let [table '[{:ticket "T" :since "1" :note "n"
                 :fn-rename {acme.db/enqueue! acme.jobs.ports/enqueue!
                             acme.old/put!    acme.cache.ports/put!}}]]
    (testing "two new namespaces ending in the same segment"
      (is (= "(ns a (:require [acme.db :as db] [acme.old :as c]\n                [acme.jobs.ports :as ports]\n                [acme.cache.ports]))\n(ports/enqueue! 1)\n(acme.cache.ports/put! 2)\n"
             (:text (upgrade/rewrite-source table "(ns a (:require [acme.db :as db] [acme.old :as c]))\n(db/enqueue! 1)\n(c/put! 2)\n" "a.clj")))))
    (testing "the new namespace is required, but under no alias"
      (is (= "(ns a (:require [acme.db :as db] [acme.jobs.ports :refer [x]]\n                [acme.jobs.ports :as ports]))\n(ports/enqueue! 1)\n"
             (:text (upgrade/rewrite-source table "(ns a (:require [acme.db :as db] [acme.jobs.ports :refer [x]]))\n(db/enqueue! 1)\n" "a.clj")))))
    (testing "a fully qualified call requires the new namespace"
      (is (= "(ns a (:require [acme.db]\n                [acme.jobs.ports]))\n(acme.jobs.ports/enqueue! 1)\n"
             (:text (upgrade/rewrite-source table "(ns a (:require [acme.db]))\n(acme.db/enqueue! 1)\n" "a.clj")))))))

(deftest ^:unit a-referred-fn-is-reported-not-rewritten
  (let [src "(ns a (:require [acme.adapters.db :refer [enqueue!]]))\n(enqueue! 1)\n"
        {:keys [text notices]} (upgrade/rewrite-source table src "src/a.clj")]
    (is (= src text))
    (is (= ["src/a.clj: refers enqueue! from acme.adapters.db; change it to acme.ports/enqueue! by hand"]
           notices))))

(deftest ^:unit unified-diff-shows-only-changed-hunks
  (let [old (str/join "\n" (map #(str "line " %) (range 1 21)))
        new (str/replace old "line 10" "line ten")]
    (is (= (str/join "\n" ["--- a/f.clj" "+++ b/f.clj" "@@ -7,7 +7,7 @@"
                           " line 7" " line 8" " line 9" "-line 10" "+line ten"
                           " line 11" " line 12" " line 13"])
           (upgrade/unified-diff "f.clj" old new)))))

(deftest ^:unit the-shipped-table-is-well-formed
  (is (empty? (upgrade/table-problems (upgrade/load-table))))
  (is (= ["X: needs a rewrite or a :manual reason, not both"
          "Y: :config-key renames a key within its map; the paths must share a parent"]
         (upgrade/table-problems
          [{:ticket "X" :since "1" :note "n"}
           {:ticket "Y" :since "1" :note "n" :config-key {[:a :b] [:c :b]}}]))))

(defn- scratch-project []
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "wagoe-upgrade-" (System/nanoTime)))]
    (io/make-parents (io/file dir "resources/conf/dev/config.edn"))
    (io/make-parents (io/file dir "src/my/app/jobs.clj"))
    (spit (io/file dir "resources/conf/dev/config.edn") config)
    (spit (io/file dir "src/my/app/jobs.clj") source)
    (spit (io/file dir "src/my/app/untouched.clj") "(ns my.app.untouched)\n")
    dir))

(deftest ^:integration dry-run-writes-nothing-and-apply-writes
  (let [dir (scratch-project)
        cfg (io/file dir "resources/conf/dev/config.edn")
        src (io/file dir "src/my/app/jobs.clj")]
    (try
      (let [out (with-out-str (upgrade/run dir table false))]
        (is (= config (slurp cfg)))
        (is (= source (slurp src)))
        (is (str/includes? out "--- a/resources/conf/dev/config.edn"))
        (is (str/includes? out "-  (db/enqueue!   tx job)"))
        (is (str/includes? out "2 file(s) would change"))
        (is (str/includes? out "no rewrite") "manual entries are listed"))
      (with-out-str (upgrade/run dir table true))
      (is (= (upgrade/rewrite-config table config) (slurp cfg)))
      (is (str/includes? (slurp src) "(ports/enqueue!   tx job)"))
      (is (empty? (:changes (upgrade/plan dir table))) "nothing left after --apply")
      (spit (io/file dir "src/my/app/broken.clj") "(ns my.app.broken)\n(foo]\n")
      (is (= ["src/my/app/broken.clj"]
             (map #(first (str/split % #":")) (:notices (upgrade/plan dir table))))
          "a file that does not parse is named, and the rest still planned")
      (finally (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))
