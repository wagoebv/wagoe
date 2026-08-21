(ns wagoe.ui-style.shell.module-wiring
  "Integrant wiring for the ui-style module.

   `:wagoe/ui-style` is a settings block rather than a lifecycle component: the
   key exists so other components can take a ref to it. The init-key lived in
   every application's own namespace until BOU-326 — which meant an app that
   enabled this module and did not write the defmethod failed the boot with
   \"No such namespace: wagoe\"."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :wagoe/ui-style [_ config] config)

(defmethod ig/halt-key! :wagoe/ui-style [_ _config] nil)
