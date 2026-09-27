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
  (:require [clojure.java.io :as io]
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

(defn- ch-at [lx i] (nth (nth lx i) 1))
(defn- st-at [lx i] (nth (nth lx i) 2))

(defn- ws-at? [lx i]
  (or (= :comment (st-at lx i))
      (and (= :code (st-at lx i))
           (let [c (ch-at lx i)] (or (Character/isWhitespace ^char c) (= \, c))))))

(declare form-end)

(defn- skip
  "Index past the whitespace, comments and `#_` discards from `k`, or nil when
   a discarded form cannot be read."
  [lx k]
  (let [n (count lx)]
    (loop [i k]
      (cond
        (>= i n)      i
        (ws-at? lx i) (recur (inc i))
        (and (= :code (st-at lx i)) (= \# (ch-at lx i))
             (< (inc i) n) (= \_ (ch-at lx (inc i))))
        (when-let [e (form-end lx (skip lx (+ i 2)))] (recur e))
        :else i))))

(defn- form-end
  "Index just past the form starting at `k` in lexed `lx`, or nil.

   Enough reader for a config value: collections, strings, tagged literals
   (`#env X`, `#or [..]`), sets, regexes, `##Inf`, `^meta`, character
   literals and bare tokens."
  [lx k]
  (let [n      (count lx)
        delim? #(or (ws-at? lx %) (not= :code (st-at lx %))
                    (#{\( \) \[ \] \{ \} \"} (ch-at lx %)))
        token  #(loop [i %] (if (and (< i n) (not (delim? i))) (recur (inc i)) i))]
    (when (and k (< k n))
      (let [c (ch-at lx k)]
        (cond
          (= :string (st-at lx k))
          (some #(when (and (= :string (st-at lx %)) (= \" (ch-at lx %))) (inc %)) (range (inc k) n))

          (#{\( \[ \{} c)
          (loop [i (inc k), depth 1]
            (cond (>= i n)                 nil
                  (not= :code (st-at lx i)) (recur (inc i) depth)
                  :else (let [d (case (ch-at lx i) (\( \[ \{) (inc depth) (\) \] \}) (dec depth) depth)]
                          (if (zero? d) (inc i) (recur (inc i) d)))))

          ;; The lexer blanks the escaped char, so `\newline` reads as `\`,
          ;; a blank, then `ewline`.
          (= \\ c) (min n (token (+ k 2)))

          (= \# c)
          (case (when (< (inc k) n) (ch-at lx (inc k)))
            (\{ \") (form-end lx (inc k))
            \#      (let [e (token k)] (when (> e k) e))
            \_      (form-end lx (skip lx k))
            (form-end lx (skip lx (token (inc k)))))

          (= \^ c) (form-end lx (skip lx (form-end lx (skip lx (inc k)))))

          (#{\' \@ \`} c) (form-end lx (inc k))

          (#{\) \] \}} c) nil

          :else (let [e (token k)] (when (> e k) e)))))))

(defn- line-start [text i]
  (inc (or (str/last-index-of text "\n" (dec i)) -1)))

(defn- lead-start
  "Where the entry whose key is at `k` begins, taking along the comment lines
   directly above it. Only when the key starts its own line."
  [text k]
  (let [ls (line-start text k)]
    (if-not (str/blank? (subs text ls k))
      k
      (loop [from ls]
        (if (zero? from)
          from
          (let [prev (line-start text (dec from))]
            (if (str/starts-with? (str/trim (subs text prev (dec from))) ";")
              (recur prev)
              from)))))))

(defn- map-entries [text lx open close]
  (loop [k (skip lx (inc open)), out []]
    (cond
      (nil? k)     nil
      (>= k close) out
      :else (let [kend (form-end lx k)
                  v    (some->> kend (skip lx))
                  vend (some->> v (form-end lx))]
              (when (and vend (<= vend close))
                (recur (skip lx vend)
                       (conj out {:key   (subs text k kend)
                                  :start k
                                  :value v
                                  :end   vend
                                  :from  (lead-start text k)})))))))

(defn root-map
  "[open close] indices of the braces of the file's top-level map, or nil."
  [text]
  (let [lx (lex text)
        k  (skip lx 0)]
    (when (and k (< k (count lx)) (= \{ (ch-at lx k)) (= :code (st-at lx k)))
      (when-let [e (form-end lx k)]
        [k (dec e)]))))

(defn section
  "[start end] indices of the braces of the map under the top-level key `kw`
   (\":active\", \":inactive\"), or nil.

   Only the root map's own keys count, matched exactly: a nested `:active`, or
   `:active-profiles`, is not the section. nil too when the value is not a
   literal map (`#include`, `#profile`), since there is nothing to edit."
  [text kw]
  (when-let [[open close] (root-map text)]
    (let [lx (lex text)]
      (some (fn [{:keys [key value end]}]
              (when (and (= key kw) (= \{ (ch-at lx value)) (= :code (st-at lx value)))
                [value (dec end)]))
            (map-entries text lx open close)))))

(defn active-section
  "[start end] indices of the `:active` map's braces, or nil."
  [text]
  (section text ":active"))

(defn entries
  "The key/value pairs of the map under `kw`, in order, as maps of `:key` (the
   key's text), `:start` (the key), `:end` (past the value) and `:from` (where
   the comments above it begin). nil when there is no such map or it cannot be
   read."
  [text kw]
  (when-let [[open close] (section text kw)]
    (map-entries text (lex text) open close)))

(defn insert-into
  "`text` with `snippet` inserted just inside the closing brace of the map
   under `kw`, or unchanged when there is none."
  [text kw snippet]
  (if-let [[_ close] (section text kw)]
    (str (subs text 0 close) snippet (subs text close))
    text))

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

(defn- balanced?
  "Whether braces balance in `text`, counted over code only.

   The invariant the insertion must preserve. The old safety net shelled out to
   clj-paren-repair, which re-indents the whole file — so the bytes depended on
   which repair tool was on the PATH, every integrate reshuffled a reviewed
   file's diff, and the regen check had to stop comparing indentation to cope
   (BOU-359). A check holds the invariant; a reformatter replaces it."
  [text]
  (zero? (reduce (fn [d c] (case c \{ (inc d) \} (dec d) d))
                 0 (code-only text))))

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
                  (if (and (balanced? out) (some? (active-closing-brace out)))
                    (do (spit path out) :written)
                    ;; Refuse rather than write a config the app cannot read.
                    ;; Unreachable for a balanced snippet into a balanced file;
                    ;; this is what stands where the reformatter used to.
                    :insert-would-unbalance)))))))
