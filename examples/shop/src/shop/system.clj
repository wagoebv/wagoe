(ns shop.system
  "Integrant keys this application defines itself.

   Configuration is loaded from resources/conf/<env>/config.edn via Aero, and
   which components run is src/wagoe/system_config.clj. Framework modules
   register their own keys, so `wagoe add <module>` needs no line here.

   Empty on purpose. Add an init-key when you write a component of your own:

     (require '[integrant.core :as ig])

     (defmethod ig/init-key :acme/report-mailer
       [_ {:keys [email db-ctx]}]
       (->ReportMailer email db-ctx))")
