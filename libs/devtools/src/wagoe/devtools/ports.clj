(ns wagoe.devtools.ports
  "Protocol definitions for the devtools library.")

(defprotocol IGuidance
  "Protocol for the guidance engine that provides contextual help."
  (guidance-level [this]
    "Returns the current guidance level (:full, :minimal, :off).")
  (set-guidance-level! [this level]
    "Sets the guidance level at runtime."))
