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
               "  :wagoe/h2 {:jdbc-url \"jdbc:h2:mem:app;DB_CLOSE_DELAY=-1\"}\n"
               "  :wagoe/http {:port 3000}\n"
               "  :wagoe/tasks {:enabled? true}}\n"
               "\n"
               " :inactive\n"
               " {:wagoe/payments {:provider :mock}}}\n"))
    dir))

(defn- rm-r [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-r c)))
  (.delete f))

(deftest ^:integration the-knowledge-base-is-read-from-the-classpath
  ;; The one resource that describes how to write Wagoe code was read from
  ;; resources/agents/knowledge.edn — a path in this repository and in no
  ;; project.
  ;;
  ;; This proves the *read* works, not the packaging: here wagoe-tools is a
  ;; :local/root, so the file is found through its source path either way. What
  ;; puts it in the jar is the "resources" entry in libs/tools/build.clj, and
  ;; the only honest check of that is listing the built artifact.
  (is (some? (io/resource "wagoe/agents/knowledge.edn"))
      "the agents knowledge base must be on the classpath, not at a repo path"))

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

          (testing "not the one mentioned in a comment"
            (is (not-any? #{":wagoe/postgresql"} (:config-keys graph))))

          (testing "not the ones under :inactive — those are switched off"
            (is (not-any? #{":wagoe/payments"} (:config-keys graph))))

          (testing "and a semicolon inside a string does not swallow the rest"
            ;; "jdbc:h2:mem:app;DB_CLOSE_DELAY=-1" — stripping comments by
            ;; regex lost every key after it on that line.
            (is (some #{":wagoe/http"} (:config-keys graph))))))
      (finally (rm-r dir)))))

(deftest ^:integration a-project-advertises-only-what-it-can-serve
  (let [dir (tmp-project!)]
    (try
      (let [uris (mapv :uri (resources/available-catalog (sut/build-snapshot dir)))]
        (is (= ["wagoe://conventions" "wagoe://module-graph"] uris)
            "a project with no .clj-kondo/config.edn and no running system serves these two"))
      (finally (rm-r dir)))))

(deftest ^:integration a-project-with-its-own-libs-dir-is-not-a-monorepo
  ;; The monorepo branch answered for any root with a libs/ directory, and
  ;; returned {:modules [] :edges []} when it found no library there — an
  ;; available resource that says nothing, which is the shape this ticket
  ;; removes.
  (let [dir (tmp-project!)]
    (try
      (.mkdirs (io/file dir "libs/my-thing"))
      (let [graph (resources/read-resource (sut/build-snapshot dir) "wagoe://module-graph")]
        (is (= :project (:source graph)))
        (is (seq (:libraries graph))))
      (finally (rm-r dir)))))

(deftest ^:integration a-tool-alias-that-replaces-deps-still-counts
  ;; :replace-deps is the documented way to isolate a tool alias, and this
  ;; repository's own :mcp alias uses it. Reading only :extra-deps made those
  ;; libraries invisible.
  (let [dir (tmp-project!)]
    (try
      (spit (io/file dir "deps.edn")
            (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0\"}}\n"
                 " :aliases {:mcp {:replace-deps {com.wagoe/wagoe-mcp {:mvn/version \"1.0.0\"}}}}}"))
      (let [graph (resources/read-resource (sut/build-snapshot dir) "wagoe://module-graph")]
        (is (= ["wagoe-core"] (:libraries graph)))
        (is (= ["wagoe-mcp"] (:dev-libraries graph))))
      (finally (rm-r dir)))))
