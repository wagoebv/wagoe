(ns wagoe.storage.core.export-jobs
  "A data export produced by a background job, from request to download.")

(def download-window
  "How long a finished archive can be downloaded."
  (java.time.Duration/ofHours 24))

(def start-window
  "How long a queued job may wait for a worker. A queue that takes jobs at
   commit can lose one then (BOU-993); still pending after this, it never ran."
  (java.time.Duration/ofMinutes 15))

(def requests-per-hour
  "How many export jobs one requester may open an hour, by default. 0 is no
   limit."
  5)

(defn new-job
  [id subject requested-by now]
  {:id id :subject subject :requested-by (str requested-by) :status :pending :created-at now})

(defn finished
  "`job` done, its archive at `file-key`."
  [job file-key ^java.time.Instant now]
  (assoc job :status :done :file-key file-key :error nil
         :finished-at now :expires-at (.plus now ^java.time.Duration download-window)))

(defn failed
  [job message now]
  (assoc job :status :failed :error message :finished-at now))

(defn abandoned?
  "Whether open `job` will not finish: pending past `start-window`, or open
   past `download-window`, a worker that died mid-export."
  [job ^java.time.Instant now]
  (let [older? #(.isBefore ^java.time.Instant (:created-at job) (.minus now ^java.time.Duration %))]
    (or (older? download-window)
        (and (= :pending (:status job)) (older? start-window)))))

(defn requested-by?
  "Whether `user-id` asked for `job`. Only they may see it."
  [job user-id]
  (and (some? user-id) (= (:requested-by job) (str user-id))))

(defn own-data?
  "Whether `job` exports the data of who asked for it, rather than an
   admin's export of someone else."
  [{:keys [subject requested-by]}]
  (and (= :user (:type subject)) (= (str (:id subject)) requested-by)))

(defn downloadable?
  [{:keys [status file-key ^java.time.Instant expires-at]} now]
  (boolean (and (= :done status) file-key expires-at (.isBefore ^java.time.Instant now expires-at))))

(defn status-view
  "`job` as its status endpoint answers it; `download-path` only while the
   archive can be downloaded."
  [job download-path now]
  (cond-> (select-keys job [:id :status :created-at :finished-at :expires-at])
    (= :failed (:status job)) (assoc :error "The export failed. Request a new one.")
    (downloadable? job now)   (assoc :download-path download-path)))
