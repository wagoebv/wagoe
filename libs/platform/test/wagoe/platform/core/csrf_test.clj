(ns wagoe.platform.core.csrf-test
  "Unit tests for pure CSRF token functions."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [wagoe.platform.core.csrf :as csrf]
            [buddy.core.nonce :as nonce]))

(def ^:private secret "test-secret-at-least-32-chars-long!!")
(def ^:private session "session-token-abc-123")

(defn- nonce16 [] (nonce/random-bytes 16))

(deftest ^:unit ^:security generate-and-validate-roundtrip-test
  (testing "a freshly generated token validates against its binding"
    (let [token (csrf/generate-token secret session (nonce16))]
      (is (csrf/valid-token? secret session token))))

  (testing "token format is base64url(nonce).base64url(mac)"
    (let [token (csrf/generate-token secret session (nonce16))
          parts (str/split token #"\.")]
      (is (= 2 (count parts)))
      (is (every? seq parts)))))

(deftest ^:unit ^:security binding-isolation-test
  (testing "token bound to one session is invalid for another (CSRF defense)"
    (let [token (csrf/generate-token secret session (nonce16))]
      (is (not (csrf/valid-token? secret "different-session" token)))))

  (testing "pre-session token (nil binding) validates only against nil binding"
    (let [token (csrf/generate-token secret nil (nonce16))]
      (is (csrf/valid-token? secret nil token))
      (is (not (csrf/valid-token? secret session token))))))

(deftest ^:unit ^:security wrong-secret-test
  (testing "token is invalid under a different secret"
    (let [token (csrf/generate-token secret session (nonce16))]
      (is (not (csrf/valid-token? "another-secret-32-chars-minimum!!" session token))))))

(deftest ^:unit ^:security tamper-test
  (testing "flipping the mac portion invalidates the token"
    (let [token        (csrf/generate-token secret session (nonce16))
          [nonce _mac] (str/split token #"\.")
          forged       (str nonce "." "AAAAAAAAAAAAAAAAAAAAAA")]
      (is (not (csrf/valid-token? secret session forged)))))

  (testing "swapping nonce while keeping mac invalidates the token"
    (let [token        (csrf/generate-token secret session (nonce16))
          other        (csrf/generate-token secret session (nonce16))
          [_n1 mac1]   (str/split token #"\.")
          [n2 _mac2]   (str/split other #"\.")
          frankentoken (str n2 "." mac1)]
      (is (not (csrf/valid-token? secret session frankentoken))))))

(deftest ^:unit ^:security malformed-input-is-safe-test
  (testing "nil, blank, and malformed tokens return false without throwing"
    (is (not (csrf/valid-token? secret session nil)))
    (is (not (csrf/valid-token? secret session "")))
    (is (not (csrf/valid-token? secret session "   ")))
    (is (not (csrf/valid-token? secret session "no-separator")))
    (is (not (csrf/valid-token? secret session "too.many.parts")))
    (is (not (csrf/valid-token? secret session "!!!.@@@")))
    (is (not (csrf/valid-token? secret session 12345)))))

(deftest ^:unit nonce-uniqueness-test
  (testing "distinct nonces yield distinct tokens for the same binding"
    (let [t1 (csrf/generate-token secret session (nonce16))
          t2 (csrf/generate-token secret session (nonce16))]
      (is (not= t1 t2))
      (is (csrf/valid-token? secret session t1))
      (is (csrf/valid-token? secret session t2)))))

(deftest ^:unit extract-token-test
  (testing "extracts from form-params (string key)"
    (is (= "tok" (csrf/extract-token {:form-params {"__anti-forgery-token" "tok"}}))))

  (testing "extracts from form-params (keyword key)"
    (is (= "tok" (csrf/extract-token {:form-params {:__anti-forgery-token "tok"}}))))

  (testing "extracts from multipart-params (file-upload forms)"
    (is (= "tok" (csrf/extract-token {:multipart-params {"__anti-forgery-token" "tok"}}))))

  (testing "extracts from header x-csrf-token"
    (is (= "tok" (csrf/extract-token {:headers {"x-csrf-token" "tok"}}))))

  (testing "form param wins over header"
    (is (= "form" (csrf/extract-token {:form-params {"__anti-forgery-token" "form"}
                                       :headers {"x-csrf-token" "hdr"}}))))

  (testing "nil when absent"
    (is (nil? (csrf/extract-token {})))
    (is (nil? (csrf/extract-token {:headers {} :form-params {}})))))

(deftest ^:unit ^:security hx-headers-test
  (testing "1-arity returns a mergeable {:hx-headers <json>} attr map"
    (let [token "nonce123.mac456"
          attrs (csrf/hx-headers token)]
      (is (map? attrs))
      (is (contains? attrs :hx-headers))
      (testing "the json value is {\"x-csrf-token\": <token>}"
        (is (= {"x-csrf-token" token} (json/parse-string (:hx-headers attrs)))))))

  (testing "nil token returns nil (callers can merge unconditionally)"
    (is (nil? (csrf/hx-headers nil))))

  (testing "0-arity reads the request-bound *token*"
    (binding [csrf/*token* "bound.tok"]
      (is (= {"x-csrf-token" "bound.tok"}
             (json/parse-string (:hx-headers (csrf/hx-headers)))))))
  (testing "0-arity returns nil when *token* is unbound"
    (is (nil? (csrf/hx-headers)))))
