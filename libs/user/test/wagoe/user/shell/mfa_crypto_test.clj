(ns wagoe.user.shell.mfa-crypto-test
  "Security tests for MFA secret encryption and backup-code hashing at rest."
  (:require [wagoe.user.shell.mfa-crypto :as sut]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit ^:security totp-secret-encryption-test
  (let [secret "JBSWY3DPEHPK3PXP"
        enc    (sut/encrypt-secret secret)]
    (testing "the stored value is not the plaintext secret"
      (is (not= secret enc))
      (is (not (str/includes? enc secret)))
      (is (sut/encrypted-secret? enc))
      (is (str/starts-with? enc "enc:v1:")))
    (testing "it decrypts back to the original secret"
      (is (= secret (sut/decrypt-secret enc))))
    (testing "encryption is non-deterministic (random IV per call)"
      (is (not= enc (sut/encrypt-secret secret))))
    (testing "a legacy plaintext value is not treated as encrypted and decrypts to nil"
      (is (not (sut/encrypted-secret? secret)))
      (is (nil? (sut/decrypt-secret secret))))
    (testing "nil in, nil out"
      (is (nil? (sut/encrypt-secret nil)))
      (is (nil? (sut/decrypt-secret nil))))))

(deftest ^:unit ^:security backup-code-hashing-test
  (let [code "ABCD-1234-EFGH"
        hash (sut/hash-backup-code code)]
    (testing "the stored value is a bcrypt hash, not the plaintext code"
      (is (not= code hash))
      (is (not (str/includes? hash code)))
      (is (str/starts-with? hash "bcrypt+sha512$")))
    (testing "the correct code matches its hash, a wrong one does not"
      (is (sut/backup-code-matches? code hash))
      (is (not (sut/backup-code-matches? "WRONG-CODE-0000" hash))))
    (testing "a malformed / legacy (non-bcrypt) stored value never matches and never throws"
      (is (not (sut/backup-code-matches? code "CODE1")))
      (is (not (sut/backup-code-matches? code nil)))
      (is (not (sut/backup-code-matches? nil hash))))))
