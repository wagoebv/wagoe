(ns wagoe.user.core.view-as
  "An administrator viewing the app as another user, read-only (BOU-868): the
   session takes that user's rights for a while, and the administrator stays
   the actor of what is audited."
  (:require [wagoe.user.core.user :as user-core]))

(def duration-minutes
  "How long a view lasts before it ends by itself."
  30)

(def stop-path "/web/view-as/stop")

(def allowed-writes
  "What a view may still post: stopping it, and signing out, which ends it."
  #{stop-path "/web/logout"})

(defn active?
  "Whether `session` views the app as another user at `now`."
  [{:keys [view-as-user-id view-as-until]} now]
  (boolean (and view-as-user-id view-as-until (.isAfter ^java.time.Instant view-as-until now))))

(defn writes?
  "Whether a request of `method` would change something: refused while viewing."
  [method]
  (not (#{:get :head :options} method)))

(defn- refused [reason message]
  {:error {:type :forbidden :reason reason :message message}})

(defn refusal
  "Why `admin`, whose `session` it is, may not view the app as `target`, or
   nil. Only an administrator, not one already viewing, and never as an
   administrator, themselves, a deactivated user or one of another tenant
   than a tenant's administrator's."
  [admin target session now]
  (cond
    (active? session now)                  (refused :nested "Stop viewing as the other user first")
    (not (user-core/admin-role? admin))    (refused :not-admin "Only an administrator views as another user")
    (nil? target)                          {:error {:type :not-found :message "No such user"}}
    (= (str (:id admin)) (str (:id target))) (refused :self "That is you")
    (user-core/admin-role? target)         (refused :admin "An administrator is not viewed as")
    (not (:active target))                 (refused :inactive "A deactivated user is not viewed as")
    ;; Another tenant's user is not found, as on every user page (BOU-953)
    (and (:tenant-id admin) (not= (str (:tenant-id admin)) (str (:tenant-id target))))
    {:error {:type :not-found :message "No such user"}}))
