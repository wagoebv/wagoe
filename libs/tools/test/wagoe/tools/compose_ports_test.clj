(ns wagoe.tools.compose-ports-test
  "A dev or test container published on every interface is open to the network
   the laptop is on, with default credentials; Docker's rules bypass ufw too.
   So each one binds to loopback (BOU-1019)."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private root (first (filter #(fs/exists? (fs/path % "docker-compose.yml")) ["." "../.."])))

(defn- loopback? [host] (contains? #{"127.0.0.1" "::1" "[::1]"} host))

(defn- open-ports
  "Each port a compose file publishes beyond loopback: short or long syntax."
  [text]
  (for [[_ service] (:services (yaml/parse-string text))
        port        (:ports service)
        :when (if (map? port)
                (and (:published port) (not (loopback? (str (:host_ip port)))))
                (not (some #(str/starts-with? (str port) (str % ":")) ["127.0.0.1" "[::1]"])))]
    port))

(deftest ^:unit the-check-reads-a-bare-port-as-open
  (is (= ["5432:5432" 6379 "3306" "${P:-1}:1" {:published 1 :target 1}]
         (vec (open-ports (str "services:\n  a:\n    ports:\n"
                               "      - \"5432:5432\"\n      - 6379\n      - '3306'\n"
                               "      - \"${P:-1}:1\"\n      - \"127.0.0.1:2:2\"\n      - \"[::1]:3:3\"\n"
                               "      - published: 1\n        target: 1\n"
                               "      - published: 4\n        target: 4\n        host_ip: 127.0.0.1\n"))))))

(deftest ^:unit ^:security dev-and-test-ports-bind-to-localhost
  (is root)
  (doseq [f (fs/glob root "**docker-compose*.{yml,yaml,yml.example}")
          :let [rel (str (fs/relativize root f))]
          ;; deploy/ is a deployment: its load balancer and app are meant to be reached
          :when (not (or (str/starts-with? rel "deploy/") (str/includes? rel "node_modules")))]
    (is (empty? (open-ports (slurp (str f)))) rel)))
