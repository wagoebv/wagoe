(ns build-shared
  "Shared build helpers for Wagoe library build.clj files.

   Loaded via (load-file \"../build_shared.clj\") from each lib's build.clj,
   which runs with the lib directory as the working directory under
   `clojure -T:build`."
  (:require [clojure.edn :as edn]
            [clojure.tools.build.api :as b]))

(defn- rewrite-wagoe-deps
  "Rewrite boundary/<artifact> :local/root deps to their published
   org.wagoe/wagoe-<artifact> :mvn/version coordinates.

   tools.build's write-pom omits :local/root deps from the generated pom, so
   without this a published lib's pom lists none of its inter-Boundary deps and
   consumers must hand-enumerate the whole closure (and cljdoc analysis fails)."
  [deps version]
  (reduce-kv
   (fn [m dep coord]
     (if (and (map? coord)
              (contains? coord :local/root)
              (= "wagoe" (namespace dep)))
       (assoc m (symbol "org.wagoe" (str "wagoe-" (name dep)))
              {:mvn/version version})
       (assoc m dep coord)))
   {}
   deps))

(defn pom-basis
  "A basis for write-pom where boundary :local/root deps are rewritten to
   published :mvn/version coords at the given suite version."
  [version]
  (let [project   (edn/read-string (slurp "deps.edn"))
        rewritten (update project :deps rewrite-wagoe-deps version)]
    (b/create-basis {:project rewritten})))
