(ns wagoe.tools.project
  "What the tooling needs to know about the project it is running in.

   Just one thing so far: which namespace the application's own code lives
   under. `bb scaffold generate` used to write every module into `wagoe.*` —
   the framework's namespace root — so a project called shop had a
   `wagoe.product` module (BOU-360)."
  (:require [clojure.java.io :as io]))

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
