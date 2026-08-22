(ns wagoe.tools.quickstart-urls-test
  "The URLs the quickstart tells a reader to open.

   Its last step said `http://localhost:3000/admin/products`, which was wrong
   twice: the admin module is not in a generated project's `:active`, and admin
   mounts at `/web/admin`, not `/admin`. The final instruction of the framework's
   first page 404'd (BOU-328).

   Checking one against a running server needs a project, a scaffolded module
   and a boot — that is the first-run smoke's job, not a unit test's. What is
   cheap here is the mistake that was actually made: a path that no route in the
   framework serves, and a path that needs a module the page never tells you to
   add."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- mentions?
  "Whether `text` contains `needle`, as a small value.

   `(is (str/includes? page-text ...))` reports the entire page as `actual`,
   which buries the message under two hundred lines nobody reads."
  [text needle]
  (some? (re-find (re-pattern (java.util.regex.Pattern/quote needle)) text)))

(defn- page [name]
  (or (some #(when (fs/exists? %) (slurp %))
            [(str "docs/modules/getting-started/pages/" name)
             (str "../../docs/modules/getting-started/pages/" name)])
      (throw (ex-info (str name " not found — cannot check it") {}))))

(defn- localhost-paths
  "The paths of every http://localhost:3000/... the page shows."
  [text]
  (->> (re-seq #"http://localhost:3000(/[a-zA-Z0-9/._:-]*)" text)
       (map (comp #(str/replace % #"[.]$" "") second))
       (remove str/blank?)
       distinct))

(deftest ^:unit the-quickstart-only-sends-you-to-paths-that-exist
  (let [text  (page "quickstart.adoc")
        paths (localhost-paths text)]

    (testing "the page was read and does show URLs — otherwise this is vacuous"
      (is (mentions? text "localhost:3000"))
      (is (seq paths)))

    (testing "no admin path without the step that adds the admin module"
      ;; The failure was structural: the page ended on an admin URL and never
      ;; mentioned `wagoe add admin`, because admin is not in the generated
      ;; config. Either both or neither.
      (when (some #(str/includes? % "admin") paths)
        (is (mentions? text "wagoe add admin")
            "the page sends you to an admin URL without telling you to add the module")
        (is (mentions? text "bb create-admin")
            "admin needs a signed-in admin, and a new project has no users")))

    (testing "admin is under /web, which is where the module mounts"
      (doseq [p paths
              :when (str/includes? p "admin")]
        (is (str/starts-with? p "/web/admin")
            (str p " — the admin module's base-path is /web/admin"))))

    (testing "a scaffolded module's routes are shown under the prefix they mount at"
      ;; `bb scaffold integrate` prints "Routes are mounted under /api/v1".
      (doseq [p paths
              :when (str/includes? p "products")]
        (is (str/starts-with? p "/api/v1/")
            (str p " — a scaffolded module's API routes mount under /api/v1"))))))

(deftest ^:unit the-quickstart-and-the-cli-name-the-same-next-step
  ;; `wagoe new` finishes by printing `bb quickstart`. A page that describes the
  ;; same eight steps and never names that command leaves the reader guessing
  ;; whether they are two different things.
  (let [text (page "quickstart.adoc")]
    (is (mentions? text "bb quickstart")
        "the quickstart page must name the command `wagoe new` points at")))
