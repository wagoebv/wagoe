(ns wagoe.platform.ports.authorization
  "The permission registry: every permission, code role and scope key the
  enabled modules declare or derive. Built once at boot as
  `:wagoe/permission-registry`; nothing outside it can be granted.")

(defprotocol IPermissionRegistry
  (permissions [this]
    "{permission {:label :module :source}}. `:source` is `:derived`
    (`<module>/read|write`, `admin.<entity>/read|write`), `:declared`, or one
    a module names, such as the user module's `:groups-schema`.")

  (roles [this]
    "Code roles, {role {:description :permissions #{..} :module}}.")

  (scopes [this]
    "Scope keys, {key {:label :entity :schema :sources :expand :source :module}}.")

  (scope-sources [this]
    "Where a scope value may come from the user, {:user/<name> {:label :resolve :module}};
    `:resolve` is a fn from the user to the values.")

  (permission? [this permission]
    "Whether `permission` is in the registry."))
