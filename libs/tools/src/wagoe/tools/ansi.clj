#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/ansi.clj
;;
;; Shared ANSI terminal colour helpers for wagoe-tools CLI output.

(ns wagoe.tools.ansi)

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn cyan   [s] (str "\033[36m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))
