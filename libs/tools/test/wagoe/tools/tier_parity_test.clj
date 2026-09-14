(ns wagoe.tools.tier-parity-test
  "The tier a library carries is a promise, and it is written in two places:
   the catalogue the CLI ships and the stability page users read. Those are the
   two halves that drift — a documented count said 23 while the code said 29
   for months, which is what `check:doc-counts` exists to catch (BOU-432).

   Here the same shape: a library quietly promoted in the catalogue and still
   listed as incubating on the page would make the page a lie in the direction
   that matters — someone deciding whether to depend on it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.deploy :as deploy]))

(defn- repo-file [path]
  (or (some #(when (.exists (io/file %)) (io/file %)) [path (str "../../" path)])
      (throw (ex-info (str path " not found — cannot compare") {}))))

(def ^:private catalogue
  (delay (edn/read-string (slurp (repo-file "libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn")))))

(def ^:private stability
  (delay (slurp (repo-file "docs/modules/ROOT/pages/stability.adoc"))))

(defn- documented-incubating
  "The libraries the stability page lists as incubating, from its table."
  []
  (let [section (second (str/split @stability #"\.Incubating at "))]
    (->> (str/split (or section "") #"\|===")
         second
         (re-seq #"(?m)^\| `([a-z-]+)`")
         (map second)
         set)))

(defn- catalogue-tiers []
  (into {} (map (juxt :name #(:tier % :stable))) (:modules @catalogue)))

(deftest ^:unit every-catalogued-module-names-a-tier
  (testing "no module is silently stable by omission"
    (doseq [m (:modules @catalogue)]
      (is (contains? #{:stable :incubating :tooling} (:tier m))
          (str (:name m) " has no tier, or one nobody defined")))))

(deftest ^:unit the-catalogue-and-the-stability-page-agree
  (let [tiers    (catalogue-tiers)
        in-cat   (set (keys (filter #(= :incubating (val %)) tiers)))
        in-docs  (documented-incubating)]

    (testing "the page parsed — otherwise this passes vacuously"
      (is (seq in-docs) "found no incubating table in stability.adoc"))

    (testing "the page lists nothing the catalogue calls stable"
      (is (empty? (set/difference in-docs in-cat (set (remove tiers in-docs))))
          (str "stability.adoc calls " (pr-str (set/difference in-docs in-cat))
               " incubating; the catalogue does not")))

    (testing "and the catalogue hides no incubating library from the page"
      (is (empty? (set/difference in-cat in-docs))
          (str "the catalogue calls " (pr-str (set/difference in-cat in-docs))
               " incubating; stability.adoc does not say so")))))

(deftest ^:unit tooling-carries-no-stability-tier
  ;; stability.adoc puts tools, devtools, wagoe-cli and wagoe-mcp outside the
  ;; promise entirely. A tier on them would claim a guarantee that section
  ;; explicitly withholds.
  (let [tiers (catalogue-tiers)]
    (is (= :tooling (get tiers "devtools")))
    (is (not-any? #(contains? #{:stable :incubating} (get tiers %))
                  ["tools" "devtools" "wagoe-cli" "wagoe-mcp"]))))

(deftest ^:unit a-published-library-is-not-missing-from-the-catalogue-silently
  ;; The catalogue is what `wagoe add` can install, so a published library
  ;; absent from it cannot be added and carries no tier anywhere. Named rather
  ;; than merely counted, so adding a library makes this fail with the name.
  (let [known    (set (keys (catalogue-tiers)))
        expected #{"scaffolder" "shared-ui" "tools" "wagoe-cli" "wagoe-mcp"}
        missing  (set/difference (set deploy/all-libs) known)]
    (is (= expected missing)
        (str "published libraries outside the catalogue changed: " (pr-str missing)))))
