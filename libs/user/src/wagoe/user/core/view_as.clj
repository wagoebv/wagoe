(ns wagoe.user.core.view-as
  "An administrator viewing the app as another user, read-only (BOU-868): the
   session takes that user's rights for a while, and the administrator stays
   the actor of what is audited.")

(def duration-minutes
  "How long a view lasts before it ends by itself."
  30)

(def stop-path "/web/view-as/stop")

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
   administrator, themselves or a deactivated user."
  [admin target session now]
  (cond
    (active? session now)                  (refused :nested "Stop viewing as the other user first")
    (not= :admin (some-> (:role admin) keyword)) (refused :not-admin "Only an administrator views as another user")
    (nil? target)                          {:error {:type :not-found :message "No such user"}}
    (= (str (:id admin)) (str (:id target))) (refused :self "That is you")
    (= :admin (some-> (:role target) keyword)) (refused :admin "An administrator is not viewed as")
    (not (:active target))                 (refused :inactive "A deactivated user is not viewed as")))

(defn start-entry
  "The audit entry for `admin` starting to view as `target` until `until`."
  [admin target until ctx]
  {:action            :view-as-start
   :actor-id          (:id admin)
   :actor-email       (:email admin)
   :target-user-id    (:id target)
   :target-user-email (:email target)
   :metadata          {:until (str until)}
   :ip-address        (:ip-address ctx)
   :user-agent        (:user-agent ctx)
   :result            :success})

(defn stop-entry
  "The audit entry for `admin` no longer viewing as `target`."
  [admin target ctx]
  (assoc (start-entry admin target nil ctx) :action :view-as-stop :metadata nil))
