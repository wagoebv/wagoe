#!/usr/bin/env bb
;; scripts/deploy.clj
;;
;; Babashka entry point for publishing Wagoe libraries to Clojars.
;;
;; Usage — `bb deploy` is the shorter form of the same thing:
;;   bb deploy --all
;;   bb scripts/deploy.clj --missing
;;   bb scripts/deploy.clj core platform user
;;
;; The implementation lives in libs/tools/src/wagoe/tools/deploy.clj. This file
;; used to be a second copy of it, and the two had drifted in both directions:
;; the mirror alone requested cljdoc builds after publishing, while the
;; canonical copy alone carried --check-versions and --verify. Two tests
;; compared the two `all-libs` vectors, so membership stayed in step while the
;; behaviour around it diverged unnoticed (BOU-250, mechanism 3).
;;
;; One implementation, two entry points — so a fix here cannot miss the path CI
;; runs, and there is no second registry to keep in sync.
;;
;; Environment:
;;   CLOJARS_USERNAME  your Clojars username
;;   CLOJARS_PASSWORD  your Clojars deploy token

(require '[wagoe.tools.deploy :as deploy])

(apply deploy/-main *command-line-args*)
