#!/usr/bin/env bb
;; scripts/docs_lint.clj
;;
;; Babashka entry point for the documentation drift linter.
;;
;; Usage — `bb docs:lint` is the shorter form of the same thing:
;;   bb docs:lint
;;   bb scripts/docs_lint.clj --verbose
;;   bb scripts/docs_lint.clj --out-dir build/docs-lint
;;
;; It also runs as part of `bb check`.
;;
;; The implementation lives in dev/wagoe/tools/docs_lint.clj — the copy CI runs
;; via `clojure -M:docs-lint`. This file used to be a ~500-line duplicate of it,
;; and the two had silently diverged: the dev copy scanned an extra path and
;; exempted an extra alias. Editing the bb copy (the obvious one to reach for)
;; therefore changed nothing about what CI enforced. One implementation, two
;; entry points — so a fix here cannot miss the gate there.
;;
;; Output:
;;   build/docs-lint/report.edn   - structured report
;;   build/docs-lint/report.txt   - human-readable summary
;;
;; Exit code: 1 when a documented command names a deps.edn alias that does not
;; exist; 0 otherwise. Other findings are reported as warnings.

(require '[wagoe.tools.docs-lint :as docs-lint])

(apply docs-lint/-main *command-line-args*)
