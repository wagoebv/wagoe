(ns wagoe.mcp.shell.system-source-test
  "What the server can reflect from a project rather than from this repository.

   Every resource here answered `:unavailable` in a project that installed the
   server: the knowledge base was read from a path only this repository has, and
   the module graph walked a `libs/` directory no project has (BOU-320)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wagoe.mcp.core.resources :as resources]
            [wagoe.mcp.shell.system-source :as sut]))

(defn- tmp-project!
  "A directory shaped like `wagoe new` output: a deps.edn and a dev config."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "wagoe-mcp-project-" (System/currentTimeMillis)))]
    (.mkdirs (io/file dir "resources/conf/dev"))
    (spit (io/file dir "deps.edn")
          (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0\"}\n"
               "        com.wagoe/wagoe-platform {:mvn/version \"1.0.0\"}\n"
               "        org.xerial/sqlite-jdbc {:mvn/version \"3.0\"}}\n"
               " :aliases {:repl {:extra-deps {com.wagoe/wagoe-devtools {:mvn/version \"1.0.0\"}}}}}"))
    (spit (io/file dir "resources/conf/dev/config.edn")
          (str "{:active\n"
               " {:wagoe/settings {:name \"demo\"}\n"
               "  ;; :wagoe/postgresql — the alternative we did not pick\n"
               "  :wagoe/sqlite {:db \"demo.db\"}\n"
               "  :wagoe/tasks {:enabled? true}}}\n"))
    dir))

(defn- rm-r [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-r c)))
  (.delete f))

(deftest ^:integration the-knowledge-base-is-on-the-classpath
  ;; The one resource that describes how to write Wagoe code was read from
  ;; resources/agents/knowledge.edn — a path in this repository and in no
  ;; project. It ships in the wagoe-tools jar now, and this is the assertion
  ;; that keeps it there.
  (is (some? (io/resource "wagoe/agents/knowledge.edn"))
      "wagoe-tools must package the agents knowledge base"))

(deftest ^:integration conventions-resolve-without-the-framework-repo
  (let [dir (tmp-project!)]
    (try
      (let [conv (resources/read-resource (sut/build-snapshot dir) "wagoe://conventions")]
        (is (not= :unavailable (:status conv)))
        (is (seq (:fc-is conv)))
        (is (seq (:naming conv))))
      (finally (rm-r dir)))))

(deftest ^:integration the-module-graph-describes-the-project-it-is-in
  (let [dir (tmp-project!)]
    (try
      (let [graph (resources/read-resource (sut/build-snapshot dir) "wagoe://module-graph")]
        (is (= :project (:source graph)))

        (testing "the wagoe libraries the project depends on"
          (is (= ["wagoe-core" "wagoe-platform"] (:libraries graph)))
          (is (= ["wagoe-devtools"] (:dev-libraries graph)))
          (testing "and nothing that is not a wagoe library"
            (is (not-any? #(re-find #"sqlite" %) (:libraries graph)))))

        (testing "the modules its config switches on"
          (is (some #{":wagoe/tasks"} (:config-keys graph)))
          (testing "but not the one mentioned in a comment"
            (is (not-any? #{":wagoe/postgresql"} (:config-keys graph))))))
      (finally (rm-r dir)))))

(deftest ^:integration a-project-advertises-only-what-it-can-serve
  (let [dir (tmp-project!)]
    (try
      (let [uris (mapv :uri (resources/available-catalog (sut/build-snapshot dir)))]
        (is (= ["wagoe://conventions" "wagoe://module-graph"] uris)
            "a project with no .clj-kondo/config.edn and no running system serves these two"))
      (finally (rm-r dir)))))
