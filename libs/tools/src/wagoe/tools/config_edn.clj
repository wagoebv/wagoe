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

(defn active-closing-brace
  "Index of the brace closing the `:active` map, or nil.

   Two things it has to get right. `:inactive` contains `:active` as a
   substring, and a module injected there is configured and switched off — which
   reads as a scaffolder bug rather than a config one. And `:active` is
   mentioned in the file's own comments, which are not the section."
  [text]
  (let [active-idx
        (loop [pos 0]
          (let [idx (str/index-of text ":active" pos)]
            (cond
              (nil? idx) nil

              ;; A mention inside a comment line.
              (let [line-start (let [li (.lastIndexOf ^String text "\n" (int idx))]
                                 (if (neg? li) 0 li))
                    line-text  (str/trim (subs text line-start idx))]
                (str/starts-with? line-text ";"))
              (recur (+ idx 7))

              ;; ":inactive" — the match started one character early.
              (and (pos? idx) (= \: (nth text (dec idx))))
              (recur (+ idx 7))

              (str/starts-with? (subs text idx) ":inactive")
              (recur (+ idx 9))

              :else idx)))]
    (when active-idx
      (when-let [open-idx (str/index-of text "{" (+ active-idx 7))]
        (loop [i (inc open-idx), depth 1]
          (cond
            (>= i (count text)) nil
            (zero? depth)       (dec i)
            :else (recur (inc i)
                         (case (nth text i) \{ (inc depth) \} (dec depth) depth))))))))

(defn key-status
  "`:already-present` when `key-str` appears in `text`, else `:absent`."
  [text key-str]
  (if (str/includes? text key-str) :already-present :absent))

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
