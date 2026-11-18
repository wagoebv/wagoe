(ns wagoe.core.utils.secret-keys-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.core.utils.secret-keys :as secret-keys]))

(deftest ^:unit ^:security published-example-secrets-are-placeholders
  ;; BOU-1014: the values the examples and docs ship, and a repeated few characters
  (is (every? secret-keys/placeholder-secret?
              ["change-me-min-32-chars-................" "CHANGEME-and-more-letters-to-pass"
               "dev-secret-minimum-32-characters" "your-production-secret-min-32-chars"
               "at-least-32-characters-............." "minimum-32-character-secret-key"
               "abcdeabcdeabcdeabcdeabcdeabcdeabcde" nil]))
  (is (not-any? secret-keys/placeholder-secret?
                ["q8Zt0n4mR2vJ7kPa1sXc9LbW3eYh6GdUf5Ni" "3f9a0c71e2b4d8a65f0e1c9b7d2a4e6f"
                 "abcdefabcdefabcdefabcdefabcdefabcdef"])))
