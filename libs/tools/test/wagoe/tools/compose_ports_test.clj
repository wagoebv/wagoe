(ns wagoe.tools.compose-ports-test
  "A dev or test container published on every interface is open to the network
   the laptop is on, with default credentials; Docker's rules bypass ufw too.
   So each one binds to 127.0.0.1 (BOU-1019)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private root (first (filter #(fs/exists? (fs/path % "docker-compose.yml")) ["." "../.."])))

(defn- open-ports
  "Each `- \"host:container\"` port mapping in `text` that names no host address."
  [text]
  (->> (re-seq #"(?m)^\s*#?\s*-\s*\"([^\"]+:[^\"]+)\"" text)
       (map second)
       (remove #(str/starts-with? % "127.0.0.1:"))))

(deftest ^:unit the-check-reads-a-bare-port-as-open
  (is (= ["5432:5432" "${P:-1}:1"]
         (open-ports "    ports:\n      - \"5432:5432\"\n      - \"127.0.0.1:6379:6379\"\n      - \"${P:-1}:1\""))))

(deftest ^:unit ^:security dev-and-test-ports-bind-to-localhost
  (is root)
  (doseq [f ["docker-compose.yml" "docker-compose.test.yml" "docker-compose.override.yml.example"
             "resources/conf/dev/docker-compose.yml"]]
    (is (empty? (open-ports (slurp (str (fs/path root f))))) f)))
