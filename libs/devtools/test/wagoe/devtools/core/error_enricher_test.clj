(ns wagoe.devtools.core.error-enricher-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.core.error-enricher :as enricher]))

(deftest ^:unit enrich-classified-error-test
  (let [ex (ex-info "validation failed" {:boundary/error-code "BND-201"})
        classified {:code "BND-201"
                    :category :validation
                    :exception ex
                    :data {}
                    :source :ex-data}
        enriched (enricher/enrich classified)]

    (testing "enriched error has stacktrace"
      (is (contains? enriched :stacktrace)))

    (testing "enriched error has fix info when available"
      (is (nil? (:fix enriched))))

    (testing "enriched error has dashboard-url"
      (is (string? (:dashboard-url enriched))))

    (testing "enriched error has docs-url"
      (is (string? (:docs-url enriched))))))

(deftest ^:unit enrich-with-fix-test
  (let [ex (ex-info "migration" {:boundary/error-code "BND-301"})
        classified {:code "BND-301"
                    :category :persistence
                    :exception ex
                    :data {}
                    :source :ex-data}
        enriched (enricher/enrich classified)]

    (testing "enriched error has fix descriptor for BND-301"
      (is (some? (:fix enriched)))
      (is (= :apply-migration (get-in enriched [:fix :fix-id]))))))

(deftest ^:unit enrich-nil-code-test
  (testing "unclassified error (nil code) is enriched gracefully"
    (let [ex (Exception. "unknown")
          classified {:code nil :category nil :exception ex :data {} :source :unclassified}
          enriched (enricher/enrich classified)]
      (is (contains? enriched :stacktrace))
      (is (nil? (:fix enriched)))
      (is (nil? (:docs-url enriched))))))

(deftest ^:unit enrich-self-protection-test
  (testing "enricher survives when stacktrace filtering throws"
    (let [classified {:code "BND-201" :category :validation :exception nil :data {} :source :ex-data}
          enriched (enricher/enrich classified)]
      (is (map? enriched))
      (is (= "BND-201" (:code enriched))))))
