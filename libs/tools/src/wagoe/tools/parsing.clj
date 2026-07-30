#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/parsing.clj
;;
;; Shared Clojure source-file parsing utilities used by the quality-gate
;; checkers (check_fcis, check_deps, check_tests).

(ns wagoe.tools.parsing
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Source stripping — remove comments and string interiors so regex
;; scanning only matches executable code, not docstrings or comments.
;; ---------------------------------------------------------------------------

(defn strip-comments-and-strings
  "Replace comment text and string contents with spaces (preserving line
   structure) so that regex matches only apply to executable code.
   - Standalone character literals (\\\" outside strings) are replaced first
   - String literals: contents between double-quotes -> spaces
     (handles escaped quotes inside strings)
   - Comment lines: everything from ; to end of line -> spaces"
  [content]
  (-> content
      ;; Replace standalone \" character literal (outside strings) with spaces.
      ;; Lookbehind ensures the backslash-quote appears after whitespace, open
      ;; paren, or open bracket — positions where a character literal can occur
      ;; in Clojure code, not inside a string.
      (str/replace #"(?<=[\s(,\[])\\\"" "  ")
      ;; Replace string contents with spaces (preserve newlines for line counting).
      ;; Matches "..." including escaped quotes inside.
      (str/replace #"\"(?:[^\"\\]|\\.)*\""
                   (fn [m] (str/replace m #"[^\n]" " ")))
      ;; Replace comment text with spaces
      (str/replace #";[^\n]*" (fn [m] (apply str (repeat (count m) \space))))))

;; ---------------------------------------------------------------------------
;; ns form parsing
;; ---------------------------------------------------------------------------

(defn extract-ns-form-text
  "Extract the raw text of the (ns ...) form from file content using
   balanced-paren counting. Avoids read-string on the full file which
   fails on auto-resolved keywords like ::jdbc/opts.

   Handles:
   - String literals (skips their contents)
   - Line comments (skips to end of line)
   - Character literals like \\(, \\), \\\", \\; (skips the 2-char sequence)
   - Whitespace variants after (ns (space, tab, newline)"
  [content]
  (let [matcher (re-matcher #"\(ns[\s]" content)
        idx     (if (.find matcher) (.start matcher) -1)]
    (when (>= idx 0)
      (loop [i idx depth 0]
        (when (< i (count content))
          (let [c (.charAt ^String content i)]
            (cond
              (= c \() (recur (inc i) (inc depth))
              (= c \))
              (if (= depth 1)
                (subs content idx (inc i))
                (recur (inc i) (dec depth)))
              ;; Skip character literals — \x is always 2 chars.
              ;; Must be checked before \" and \; cases.
              (= c \\) (recur (+ i 2) depth)
              ;; Skip string contents (avoid counting parens inside strings)
              (= c \")
              (let [end (loop [j (inc i)]
                          (if (>= j (count content)) j
                              (let [ch (.charAt ^String content j)]
                                (cond
                                  (= ch \\) (recur (+ j 2))
                                  (= ch \") (inc j)
                                  :else     (recur (inc j))))))]
                (recur end depth))
              ;; Skip line comments (avoid counting parens in comments)
              (= c \;)
              (let [nl (.indexOf ^String content "\n" (int i))]
                (recur (if (neg? nl) (count content) (inc nl)) depth))
              :else (recur (inc i) depth))))))))

(defn read-ns-form
  "Read the (ns ...) form from a Clojure file. Returns nil if not found.
   Extracts only the ns form text before read-string, so files with
   auto-resolved keywords in function bodies are handled correctly."
  [file]
  (try
    (let [content (slurp file)
          ns-text (extract-ns-form-text content)]
      (when ns-text
        (read-string ns-text)))
    (catch Exception _
      nil)))
