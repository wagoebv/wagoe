(ns wagoe.mcp.shell.migrator
  "Default migrator for the Tier 2 `run-migration` tool. Shells out to the
   project's own `:migrate` alias in the current working directory.

   Why shell out (like the test-runner): migrations need the *project's* full
   classpath and config (datasource, env), not the MCP server's. Injected as the
   `:migrator` dep, so it is swappable and stubbable in tests."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private command
  "Argv template; `%s` is replaced with the migration direction."
  ["clojure" "-M:migrate" "%s"])

(defn- tail [s n]
  (->> (str/split-lines (or s "")) (take-last n) (str/join "\n")))

(defn default-migrator
  "Run the project's migrations for `direction` (\"up\" | \"status\") and return
   {:status :ok | :error :exit n :raw-tail str}."
  [direction]
  (let [argv (mapv #(str/replace % "%s" (str direction)) command)
        {:keys [exit out err]} (apply sh/sh argv)
        combined (str out "\n" err)]
    {:status   (if (zero? exit) :ok :error)
     :exit     exit
     :raw-tail (tail combined 40)}))
