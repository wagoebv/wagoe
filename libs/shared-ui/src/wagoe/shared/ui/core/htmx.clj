(ns wagoe.shared.ui.core.htmx
  "htmx helpers for Hiccup.")

(defn merge-attrs
  "`element` with `attrs` merged over its attribute map (one is added when it
   has none)."
  [[tag & more] attrs]
  (let [[own children] (if (map? (first more)) [(first more) (rest more)] [{} more])]
    (into [tag (merge own attrs)] children)))

(defn fragment-request?
  "Whether a request is an htmx control asking for one of `target-ids`, so a
   handler serving the page and its fragment from one URL answers with the
   fragment. A history restore asks for the page. Such a handler sends
   `Vary: HX-Request, HX-Target`, so a cache does not answer one with the other."
  [headers target-ids]
  (boolean (and (get headers "hx-request")
                (not (get headers "hx-history-restore-request"))
                (contains? (set target-ids) (get headers "hx-target")))))

(defn oob
  "Mark a Hiccup element for an out-of-band swap: htmx swaps it into the
   element with the same id, wherever that is on the page. An explicit
   `:hx-swap-oob` (e.g. \"beforeend:#toasts\") is kept. A seq (from `for`)
   marks each element; strings and nil pass through."
  [element]
  (cond
    (vector? element)
    (let [[_ attrs] element]
      (merge-attrs element (merge {:hx-swap-oob "true"} (when (map? attrs) attrs))))

    (seq? element) (map oob element)
    :else          element))
