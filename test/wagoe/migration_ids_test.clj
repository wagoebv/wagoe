(ns wagoe.migration-ids-test
  "No two migrations on the classpath may share an id.

   Migratus keys applied migrations by their numeric id across every directory
   it reads, so two libraries that happen to pick the same timestamp collide.
   It does not report this: it records the id once, runs one and skips the
   other. Push and geo were given the same id and geo's schema change silently
   never ran (BOU-431 review). `migrate up` and boot now refuse such a pair
   (BOU-717); this catches one between framework libraries before it ships.

   A library author cannot see the collision — it needs every library on one
   classpath, which is here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.database.migrations :as mig]))

(def ^:private library-ids
  "Every framework migration id, one per migration."
  (delay (->> (remove #{mig/project-migration-dir} (mig/discover-migration-dirs))
              (mapcat #'mig/migrations-in)
              (map first))))

(deftest ^:unit no-two-migrations-share-an-id
  (testing "the runner sees at least the libraries that ship migrations"
    (is (seq @library-ids)
        "no migrations discovered at all — this test would pass vacuously"))

  (testing "and none of them collide"
    (let [clashes (mig/duplicate-migration-ids (mig/discover-migration-dirs))]
      (is (empty? clashes)
          (str "these ids are claimed twice, so one of each pair silently never runs: "
               (str/join "; " (for [[id files] clashes]
                                (str id " in " (str/join " and " files)))))))))

(def ^:private released-round-ids
  "Framework migration ids from before the odd-seconds convention. Renaming one
   would re-run it on every database that recorded it."
  #{"20260311100000" "20260312000000" "20260313000000" "20260324010000"
    "20260524000000" "20260524000002" "20260911100000" "20260911100100"
    "20260911100200" "20260926100000" "20260926100100" "20260927200000"
    "20260928100000" "20261010100000" "20261010110000" "20261010120000"
    "20261016000000" "20261016120000" "20261016130000" "20261016180000"
    "20261017120000" "20261017140000" "20261018090000" "20261019091500"
    "20261019091600"})

(deftest ^:unit framework-migration-ids-end-in-an-odd-second
  ;; An application picks round timestamps by hand; framework migrations stay
  ;; off them, so the two do not share an id (BOU-717).
  (let [even (sort (remove #(or (released-round-ids %) (odd? (parse-long (subs % 12))))
                           @library-ids))]
    (is (empty? even)
        (str "framework migration ids must end in an odd second (…120017, not …120000): "
             (str/join ", " even)))))
