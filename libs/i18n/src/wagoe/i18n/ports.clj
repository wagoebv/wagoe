(ns wagoe.i18n.ports
  "Protocol definitions for the i18n module.

   FC/IS rule: protocols are interfaces — no implementation here.
   Adapters (shell layer) implement these protocols.")

;; =============================================================================
;; ICatalogue
;; =============================================================================

(defprotocol ICatalogue
  "Contract for translation catalogue adapters.

   The default implementation (MapCatalogue) wraps EDN maps loaded from
   classpath resources. Custom implementations can back the catalogue with
   a database, remote service, etc."

  (lookup [this locale key]
    "Look up a single key for a locale.

     Args:
       locale - keyword identifying the locale (e.g. :en, :nl)
       key    - keyword translation key (e.g. :user/sign-in)

     Returns:
       String translation, or nil if not found.")

  (available-locales [this]
    "Return set of locale keywords that have at least one entry.

     Returns:
       Set of keywords, e.g. #{:en :nl}"))
