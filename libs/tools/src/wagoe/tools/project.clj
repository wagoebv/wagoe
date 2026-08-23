(ns wagoe.tools.project
  "What the tooling needs to know about the project it is running in.

   Just one thing so far: which namespace the application's own code lives
   under. `bb scaffold generate` used to write every module into `wagoe.*` —
   the framework's namespace root — so a project called shop had a
   `wagoe.product` module (BOU-360)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn base-ns
  "The namespace this project's own code lives under.

   Read from the layout rather than from a config file, because there is no
   config file to forget to update: `wagoe new` writes `src/<ns>/main.clj`, and
   has done since long before this mattered. The framework's own repository
   answers `wagoe` by the same rule — its application really is `wagoe.main` —
   so the monorepo keeps behaving exactly as it did.

   Falls back to `wagoe` when there is no such directory: a project laid out
   some other way keeps the old behaviour rather than getting a guess.

   Returns the *directory* name, which is also the namespace segment —
   `wagoe new my-app` writes `src/my_app/`, and its namespaces are `my_app.*`."
  ([] (base-ns (System/getProperty "user.dir")))
  ([root]
   (or (some->> (.listFiles (io/file root "src"))
                seq
                (filter #(.isDirectory %))
                (filter #(.exists (io/file % "main.clj")))
                (map #(.getName %))
                sort
                first)
       "wagoe")))

(defn module-base-ns
  "The namespace `module` actually lives under in this project.

   Not the same question as `base-ns`. A command that *creates* a module puts
   it under the project's namespace; a command that *edits* one has to find it
   where it already is, and in a project generated before BOU-360 that is
   `wagoe.<module>`.

   Getting this wrong is quiet rather than loud: `bb scaffold field` writes the
   migration, cannot find the schema file, skips it — and still reports
   success. So the answer is the directory that exists, and only then the
   project's own namespace for a module about to be created."
  ([module] (module-base-ns module (System/getProperty "user.dir")))
  ([module root]
   (let [project (base-ns root)]
     (or (first (filter #(.isDirectory (io/file root "src" (str/replace % "." "/") module))
                        (distinct [project "wagoe"])))
         project))))
