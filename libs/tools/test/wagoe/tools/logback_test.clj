(ns wagoe.tools.logback-test
  "migratus logs its connection and whole config at INFO once per migration;
   the logback files a project and this repository run with keep that below
   the default level (BOU-588)."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

(defn- repo-root []
  (if (fs/exists? "libs") "." "../.."))

(defn- logger-level [xml logger]
  (second (re-find (re-pattern (str "<logger\\s+name=\"" (java.util.regex.Pattern/quote logger)
                                    "\"\\s+level=\"([A-Z]+)\""))
                   xml)))

(deftest ^:unit migratus-connection-lines-stay-below-info
  (doseq [f ["libs/wagoe-cli/resources/wagoe/cli/templates/logback.xml.tmpl"
             "resources/logback.xml"]]
    (is (#{"WARN" "ERROR"} (logger-level (slurp (str (fs/path (repo-root) f))) "migratus.database"))
        f)))
