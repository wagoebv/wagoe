(ns wagoe.tools.text
  "Locale-independent case conversion for the Babashka tooling, which cannot
   load libs/core. Same contract as wagoe.core.utils.text."
  (:import (java.util Locale)))

(defn lower-case ^String [^CharSequence s] (.toLowerCase (.toString s) Locale/ROOT))

(defn upper-case ^String [^CharSequence s] (.toUpperCase (.toString s) Locale/ROOT))

(defn capitalize
  ^String [^CharSequence s]
  (let [s (.toString s)]
    (if (< (count s) 2)
      (upper-case s)
      (str (upper-case (subs s 0 1)) (lower-case (subs s 1))))))
