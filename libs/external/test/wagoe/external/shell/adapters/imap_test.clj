(ns wagoe.external.shell.adapters.imap-test
  "Integration tests for the IMAP mailbox adapter.
   Tests verify record creation, protocol satisfaction, and graceful error handling
   against an unreachable host — no real IMAP server required."
  (:require [wagoe.external.ports :as ports]
            [wagoe.external.shell.adapters.imap :as imap]
            [clojure.test :refer [deftest is testing]]))

(def ^:private test-config
  {:host     "localhost-nonexistent.invalid"
   :port     993
   :username "inbox@example.com"
   :password "secret"
   :ssl?     true
   :folder   "INBOX"})

(deftest ^:integration create-imap-mailbox-test
  (testing "returns a record satisfying IImapMailbox"
    (let [mailbox (imap/create-imap-mailbox test-config)]
      (is (satisfies? ports/IImapMailbox mailbox))
      (is (= "localhost-nonexistent.invalid" (:host mailbox)))
      (is (= "INBOX" (:folder mailbox))))))

(deftest ^:integration fetch-messages-unreachable-test
  (testing "fetch-messages! on unreachable host returns error map"
    (let [mailbox (imap/create-imap-mailbox test-config)
          result  (ports/fetch-messages! mailbox)]
      (is (false? (:success? result)))
      (is (some? (:error result)))
      (is (= [] (:messages result)))
      (is (= 0  (:count result))))))

(deftest ^:integration fetch-unread-unreachable-test
  (testing "fetch-unread! on unreachable host returns error map"
    (let [mailbox (imap/create-imap-mailbox test-config)
          result  (ports/fetch-unread! mailbox)]
      (is (false? (:success? result))))))

(deftest ^:integration close-returns-true-test
  (testing "close! always returns true (connection-per-call adapter)"
    (let [mailbox (imap/create-imap-mailbox test-config)]
      (is (true? (ports/close! mailbox))))))
