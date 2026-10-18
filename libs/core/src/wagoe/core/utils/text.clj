(ns wagoe.core.utils.text
  "Case conversion that gives the same answer on every JVM.

   clojure.string/lower-case and upper-case use the JVM's default locale, so on
   tr-TR `I` lowers to a dotless `ı` and `i` uppers to a dotted `İ`. Keys,
   headers, emails and SQL names must not change with the server's locale."
  (:import (java.util Locale)))

(defn lower-case
  "`s` lower-cased with the root locale."
  ^String [^CharSequence s]
  (.toLowerCase (.toString s) Locale/ROOT))

(defn upper-case
  "`s` upper-cased with the root locale."
  ^String [^CharSequence s]
  (.toUpperCase (.toString s) Locale/ROOT))

(defn capitalize
  "`s` with its first character upper-cased and the rest lower-cased, both
   with the root locale."
  ^String [^CharSequence s]
  (let [s (.toString s)]
    (if (< (count s) 2)
      (upper-case s)
      (str (upper-case (subs s 0 1)) (lower-case (subs s 1))))))
