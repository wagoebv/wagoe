(ns todo.shell.persistence
  "Imperative shell: the H2-backed repository. This is the ONLY place snake_case
   (the database's convention) meets the kebab-case used everywhere else —
   conversion happens here via wagoe.core's case-conversion helpers, never
   deeper in the code."
  (:require [clojure.set :as set]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.core.utils.case-conversion :as cc]
            [todo.ports :as ports]))

(defn create-table!
  "Create the todos table if it does not exist."
  [ds]
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS todos (
                        id         UUID PRIMARY KEY,
                        title      VARCHAR(200) NOT NULL,
                        done       BOOLEAN NOT NULL DEFAULT FALSE,
                        created_at TIMESTAMP NOT NULL)"]))

(defn- row->todo
  "DB row (snake_case) -> todo entity (kebab-case). `created_at` -> `created-at`
   via the shared helper; `done` -> `done?` to match the entity shape."
  [row]
  (-> (cc/snake-case->kebab-case-map row)
      (set/rename-keys {:done :done?})))

(defrecord H2TodoRepository [ds]
  ports/ITodoRepository
  (save-todo! [_ todo]
    (jdbc/execute! ds ["INSERT INTO todos (id, title, done, created_at) VALUES (?,?,?,?)"
                       (:id todo) (:title todo) (:done? todo) (:created-at todo)]))
  (all-todos [_]
    (->> (jdbc/execute! ds ["SELECT id, title, done, created_at FROM todos ORDER BY created_at"]
                        {:builder-fn rs/as-unqualified-lower-maps})
         (map row->todo)))
  (set-done! [_ id done?]
    (jdbc/execute! ds ["UPDATE todos SET done = ? WHERE id = ?" done? id])))
