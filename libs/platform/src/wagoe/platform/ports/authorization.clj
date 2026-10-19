(ns wagoe.platform.ports.authorization
  "The permission registry: every permission, code role and scope key the
  enabled modules declare or derive. Built once at boot as
  `:wagoe/permission-registry`; nothing outside it can be granted.")

(defprotocol IPermissionRegistry
  (permissions [this]
    "{permission {:label :module :source}}. `:source` is `:derived`
    (`<module>/read|write`, `admin.<entity>/read|write`), `:declared`, or
    `:groups-schema` (the `:wagoe/user :groups :permissions` enum).")

  (roles [this]
    "Code roles, {role {:description :permissions #{..} :module}}.")

  (scopes [this]
    "Scope keys, {key {:label :entity :module}}.")

  (permission? [this permission]
    "Whether `permission` is in the registry."))
