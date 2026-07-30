(ns todo.core.todo
  "Functional core: pure business rules only. No I/O, no clock, no database —
   `now` and `id` are passed in by the shell so these functions stay
   deterministic and trivially testable."
  (:require [wagoe.core.validation :as v]
            [todo.schema :as schema]))

(defn valid-input?
  "True when `input` is a well-formed todo input (a non-blank title)."
  [input]
  (v/valid? schema/TodoInput input))

(defn prepare-todo
  "Build a new todo entity from a title. Pure — the caller supplies `id` and
   `now`."
  [title id now]
  {:id id :title title :done? false :created-at now})

(defn remaining
  "The not-yet-done todos."
  [todos]
  (remove :done? todos))
