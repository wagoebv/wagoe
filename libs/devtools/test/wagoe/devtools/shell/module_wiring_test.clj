(ns wagoe.devtools.shell.module-wiring-test
  "The enricher platform calls on the HTTP error path.

   Platform must not depend on devtools, so it takes a plain function and knows
   nothing about this namespace. This test is the other half of
   `wagoe.platform.shell.http.interceptors-test/dev-error-enrichment-test`,
   which uses a stub (BOU-321)."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.devtools.shell.module-wiring :as sut]))

(deftest ^:unit a-validation-failure-gets-its-code-and-its-fix
  (let [enrich (sut/error-enricher)
        info   (enrich (ex-info "Validation failed"
                                {:type   :validation-error
                                 :errors {:title ["missing required key"]}}))]
    (is (= "BND-201" (:code info)))
    (is (= :validation (:category info)))

    (testing "and it says where to read more"
      (is (= "bb guide error BND-201" (:docs-url info))))

    (testing "nothing the HTTP layer should not hand out"
      ;; The formatted terminal block, the stacktrace and the raw exception stay
      ;; server-side: this map goes into a response body. Keys with no value are
      ;; dropped rather than sent as null.
      (is (empty? (remove #{:code :category :fix :docs-url} (keys info))))
      (is (not-any? nil? (vals info))))))

(deftest ^:unit an-exception-the-pipeline-cannot-place-adds-nothing
  ;; nil, not a map with nil values: the interceptor asks "is there anything to
  ;; say", and an empty :dev key in a response says "we tried".
  (is (nil? ((sut/error-enricher) (RuntimeException. "no idea")))))

(deftest ^:unit the-integrant-key-yields-a-callable-enricher
  (let [enrich (ig/init-key :wagoe/dev-error-enricher {})]
    (is (fn? enrich))
    (is (= "BND-201" (:code (enrich (ex-info "x" {:type :validation-error :errors {}})))))
    (is (nil? (ig/halt-key! :wagoe/dev-error-enricher enrich)))))
