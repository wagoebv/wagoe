#!/usr/bin/env bb
;; scripts/check_agents_links.clj
;; The pre-commit link check. It runs `bb check-links` (wagoe.tools.dev), so
;; there is one tested implementation of which files are checked.
(require '[wagoe.tools.dev :as dev])

(dev/check-links)
