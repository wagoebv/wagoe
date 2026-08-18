#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/config_edn.clj
;;
;; Editing resources/conf/<env>/config.edn as text.
;;
;; It cannot be read as EDN. Aero tags — #env, #profile, #or — are reader macros
;; no plain reader knows, so a read-modify-write round trip dies on the first
;; #env and would in any case discard every comment in the file, which is where
;; most of the explanation lives.
;;
;; So: brace-balanced insertion. `bb quickstart` already did this, hardcoded for
;; :wagoe/tasks; `bb scaffold integrate` needs the same thing for an arbitrary
;; module (BOU-310), and two copies of a text editor for the same file is how
;; they drift.

(ns wagoe.tools.config-edn
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- lex
  "`text` as [index char state] triples, state being :code, :string or :comment.

   A brace counter without lexer state is not a brace counter. All three of
   these put the insertion in the wrong section, and the result still balances
   and still parses, so nothing complains:

     {:url \"jdbc:h2;INIT=CREATE SCHEMA {\"}   ; brace in a string
     ;; example: {:foo 1                        ; brace in a comment
     \\{                                        ; char literal

   Config files are hand-edited, and `#{}` and `{L}` in a regex already appear
   in the shipped dev config."
  [text]
  (loop [i 0, state :code, out (transient [])]
    (if (>= i (count text))
      (persistent! out)
      (let [c (nth text i)]
        (case state
          :code    (cond
                     (= c \\) (recur (+ i 2) :code (-> out (conj! [i c :code])
                                                       (conj! [(inc i) \space :code])))
                     (= c \") (recur (inc i) :string (conj! out [i c :string]))
                     (= c \;) (recur (inc i) :comment (conj! out [i c :comment]))
                     :else   (recur (inc i) :code (conj! out [i c :code])))
          :string  (cond
                     (= c \\) (recur (+ i 2) :string (-> out (conj! [i c :string])
                                                         (conj! [(inc i) \space :string])))
                     (= c \") (recur (inc i) :code (conj! out [i c :string]))
                     :else   (recur (inc i) :string (conj! out [i c :string])))
          :comment (if (= c \newline)
                     (recur (inc i) :code (conj! out [i c :code]))
                     (recur (inc i) :comment (conj! out [i c :comment]))))))))

(defn- code-only
  "`text` with strings and comments blanked, line structure intact."
  [text]
  (apply str (map (fn [[_ c st]]
                    (cond (= st :code)  c
                          (= c \newline) c
                          :else         \space))
                  (lex text))))

(defn active-section
  "[start end] indices of the `:active` map's braces, or nil.

   `end` is the closing brace. Strings and comments are blanked first, so a
   brace inside either cannot shift the depth count, and a `:active` mentioned
   in prose is not the section."
  [text]
  (let [code (code-only text)
        idx  (loop [pos 0]
               (let [i (str/index-of code ":active" pos)]
                 (cond
                   (nil? i) nil
                   ;; `:inactive` does not contain `:active` — the colon is in
                   ;; the wrong place — so no guard is needed for it. An earlier
                   ;; version had one anyway, and a test that never exercised it.
                   ;; What does need excluding is `::active`.
                   (and (pos? i) (= \: (nth code (dec i)))) (recur (+ i 7))
                   :else i)))]
    (when idx
      (when-let [open (str/index-of code "{" (+ idx 7))]
        (loop [i (inc open), depth 1]
          (cond
            (>= i (count code)) nil
            (zero? depth)       [open (dec i)]
            :else (recur (inc i)
                         (case (nth code i) \{ (inc depth) \} (dec depth) depth))))))))

(defn active-closing-brace
  "Index of the brace closing the `:active` map, or nil."
  [text]
  (second (active-section text)))

(defn key-status
  "`:already-present` when `key-str` is a key in the `:active` map, else
   `:absent`.

   Two mistakes a substring search over the whole file makes, both of which
   report a module as configured while writing nothing:

   - `:wagoe/payment` is a substring of `:wagoe/payment-provider`, which the
     shipped dev config already contains. So are route/router,
     setting/settings, metric/metrics, log/logging — six plausible module names
     in one file.
   - `:wagoe/h2` appears only under `:inactive`, and `:wagoe/cache` only in a
     comment. Reporting those as present is the configured-and-switched-off
     outcome this namespace exists to avoid."
  [text key-str]
  (if-let [[open end] (active-section text)]
    (let [active (subs (code-only text) open end)]
      (if (re-find (re-pattern (str (java.util.regex.Pattern/quote key-str)
                                    "(?![A-Za-z0-9*+!?<>=_-])"))
                   active)
        :already-present
        :absent))
    :absent))

(defn insert-before-active-close
  "`text` with `snippet` inserted just inside the `:active` map's closing brace.

   Returns text unchanged when there is no `:active` section — the caller
   decides whether that is an error."
  [text snippet]
  (if-let [idx (active-closing-brace text)]
    (str (subs text 0 idx) snippet (subs text idx))
    text))

(defn- paren-repair!
  "Run clj-paren-repair over a file, if it is installed. A safety net, not the
   mechanism: the insertion above is already balanced."
  [path]
  (try
    (process/shell {:continue true :out :string :err :string} "clj-paren-repair" path)
    (catch Exception _ nil)))

(defn inject-key!
  "Add `snippet` to the `:active` map of the config at `path`.

   Returns `:written`, `:already-present`, `:no-active-section`, or `:no-file`.
   With `dry-run?`, returns what it would have done and writes nothing."
  [path key-str snippet {:keys [dry-run?]}]
  (let [f (io/file path)]
    (cond
      (not (.exists f)) :no-file

      :else
      (let [text (slurp f)]
        (cond
          (= :already-present (key-status text key-str)) :already-present
          (nil? (active-closing-brace text))            :no-active-section
          dry-run?                                      :written
          :else (let [out (insert-before-active-close text snippet)]
                  (spit path out)
                  (paren-repair! path)
                  :written))))))
