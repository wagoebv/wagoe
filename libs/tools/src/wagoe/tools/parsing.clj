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
;; String literal extraction
;; ---------------------------------------------------------------------------

(defn string-literals
  "Every string literal in `content`, with the code that precedes it.

   Line-based regex cannot answer the questions a literal raises. Whether it is
   a docstring depends on the form it sits in, which may start on an earlier
   line; whether it is a regex depends on a `#` that a per-line match discards;
   and a multi-line string is several lines that are one token. The i18n scan
   asked all three per line and got them wrong — its docstring test was
   `(not (str/includes? line \"\\\"\"))`, which no line holding a string literal
   can satisfy, so the gate never reported anything.

   Args:
     content - Clojure source string

   Returns:
     Seq of {:text str :line int :regex? bool :preceding-code str}, in order.
     `:text` excludes the surrounding quotes. `:preceding-code` is the code
     before the literal with comments and other strings blanked, so a caller
     can match against the enclosing form without re-solving quoting."
  [content]
  (let [n (count content)]
    (loop [i        0
           line     1
           code     (StringBuilder.)   ; content so far, non-code blanked
           acc      (transient [])]
      (if (>= i n)
        (persistent! acc)
        (let [c (.charAt ^String content i)]
          (cond
            ;; Character literal: \" and \; are one token, not a quote or a
            ;; comment. Must be tested before both.
            (= c \\)
            (do (.append code "  ")
                (recur (+ i 2) (if (= \newline (when (< (inc i) n) (.charAt ^String content (inc i))))
                                 (inc line) line)
                       code acc))

            (= c \;)
            (let [nl (.indexOf ^String content "\n" (int i))
                  to (if (neg? nl) n nl)]
              (dotimes [_ (- to i)] (.append code " "))
              (recur to line code acc))

            (= c \")
            (let [start-line line
                  regex?     (and (pos? i) (= \# (.charAt ^String content (dec i))))
                  before     (str code)
                  end        (loop [j (inc i)]
                               (if (>= j n)
                                 j
                                 (let [ch (.charAt ^String content j)]
                                   (cond
                                     (= ch \\) (recur (+ j 2))
                                     (= ch \") (inc j)
                                     :else     (recur (inc j))))))
                  text       (subs content (inc i) (max (inc i) (dec (min end n))))
                  newlines   (count (re-seq #"\n" (subs content i (min end n))))]
              (dotimes [_ (- (min end n) i)] (.append code " "))
              (recur end (+ line newlines) code
                     (conj! acc {:text            text
                                 :line            start-line
                                 :regex?          regex?
                                 :preceding-code  before})))

            :else
            (do (.append code c)
                (recur (inc i) (if (= c \newline) (inc line) line) code acc))))))))

(defn unclosed-forms
  "The forms still open at the end of `code`, innermost last.

   `code` must already have strings and comments blanked — `string-literals`
   hands back exactly that as `:preceding-code`.

   Each entry is the text from the opening delimiter to the end, so a caller
   can ask what kind of form it is. Answering that from the line the literal
   sits on cannot work: two forms often share a line, and one form often spans
   several.

   Args:
     code - code-only Clojure source string

   Returns:
     Vector of substrings, outermost first."
  [code]
  (let [n (count (str code))]
    (loop [i 0, open []]
      (if (>= i n)
        (mapv #(subs code %) open)
        (let [c (.charAt ^String code i)]
          (cond
            (#{\( \[ \{} c) (recur (inc i) (conj open i))
            (#{\) \] \}} c) (recur (inc i) (cond-> open (seq open) pop))
            :else           (recur (inc i) open)))))))

(def ^:private docstring-position
  "Code that can only be followed by a docstring.

   `(defn name`, `(defn name [args]`, `(ns name` — anything else before a
   string means the string is a value.

   `def` is deliberately absent. `(def x \"doc\" v)` is legal and rare;
   `(def title \"Users\")` is common, and treating the second as a docstring
   would hide exactly the literal a scan is looking for."
  #"\((?:defn|defmacro|defprotocol|definterface|ns)-?\s+(?:\^\S+\s+)*[^\s()\[\]{}]+\s*(?:\[[^\]]*\]\s*)?$")

(defn docstring?
  "Whether a `string-literals` entry sits in a docstring position."
  [{:keys [preceding-code]}]
  (boolean (re-find docstring-position (str preceding-code))))

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
