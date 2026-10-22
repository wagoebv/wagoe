(ns wagoe.core.utils.migration-id-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.core.utils.migration-id :as mid]))

(deftest ^:unit next-id-picks-a-free-even-second
  (testing "a free even timestamp is used as-is"
    (is (= "20260801120000" (mid/next-id #{} "20260801120000"))))

  (testing "an odd second is a framework id, so it steps to the even one after"
    ;; The MySQL smoke scaffolded 20261022100001-create-invoices, the id of
    ;; push's 20261022100001-device-token-binary-collation, and boot refused.
    (is (= "20261022100002" (mid/next-id #{} "20261022100001"))))

  (testing "taken ids keep stepping to the next free even second"
    (is (= "20260801120006"
           (mid/next-id #{"20260801120000" "20260801120002" "20260801120004"}
                        "20260801120000"))))

  (testing "stepping past :58 rolls over to a real time"
    (is (= "20260801120100" (mid/next-id #{"20260801120058"} "20260801120058")))
    (is (= "20260801120100" (mid/next-id #{} "20260801120059")))
    (is (= "20270101000000" (mid/next-id #{} "20261231235959"))))

  (testing "unrelated ids do not push the timestamp forward"
    (is (= "20260801120000"
           (mid/next-id #{"19990101000000" "20250101000000"} "20260801120000")))))

(deftest ^:unit id-of-reads-the-leading-digits
  (is (= "20261022100001" (mid/id-of "20261022100001-create-invoices.up.sql")))
  (is (nil? (mid/id-of "notes.txt"))))
