(ns wagoe.shared.ui.core.htmx
  "htmx helpers for Hiccup.")

(defn oob
  "Mark a Hiccup element for an out-of-band swap: htmx swaps it into the
   element with the same id, wherever that is on the page. An explicit
   `:hx-swap-oob` (e.g. \"beforeend:#toasts\") is kept. A seq (from `for`)
   marks each element; strings and nil pass through."
  [element]
  (cond
    (vector? element)
    (let [[tag & more] element
          [attrs children] (if (map? (first more)) [(first more) (rest more)] [{} more])]
      (into [tag (merge {:hx-swap-oob "true"} attrs)] children))

    (seq? element) (map oob element)
    :else          element))
