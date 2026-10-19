(ns wagoe.platform.ports.privacy
  "Data export (GDPR Art. 20) and retention, contributed per module.

  A module implements `IDataExporter` for the data it owns and contributes it
  under `:data-export` in its `ig-config`; `IRetentionPolicy` likewise under
  `:retention`. Platform collects both, so no module reads another's tables.")

(defprotocol IDataExporter
  (export-data [this subject]
    "The data this module holds about `subject`, {:type :user|:tenant :id …},
    as JSON-encodable data. nil when it holds nothing for that type of subject."))

(defprotocol IFileExporter
  "Optional, beside `IDataExporter`, for an exporter whose data has files."
  (export-files [this subject section]
    "The files behind `section`, what `export-data` returned for `subject`,
    for an export served as a zip: [{:path \"a/b.pdf\" :content (fn [] bytes)}].
    Each sits under the exporter's id in the zip. `:content` is called while
    the zip is written and returns nil for a file that is gone."))

(defprotocol IRetentionPolicy
  (purge-before! [this cutoff]
    "Delete what this policy covers that is older than `cutoff`, an Instant.
    Returns the number of records deleted."))

(defprotocol IDataExport
  (export-subject [this subject]
    "Every contributed exporter's data about `subject`:
    {:format :subject :exported-at :sections {exporter-id data}}. Throws
    :validation-error for a malformed subject, and whatever an exporter throws:
    a partial export would read as a complete one.")

  (export-response [this doc request]
    "`doc`, from `export-subject`, as a Ring download: JSON, or a zip of the
    JSON and the exporters' files when `request` asks for one with
    `?format=zip` or `Accept: application/zip`.")

  (write-archive! [this doc out]
    "Write `doc` and the exporters' files to OutputStream `out` as the zip
    `export-response` serves. Throws when a file cannot be read, leaving the
    zip unfinished."))

(defprotocol IExportJobs
  "Data export as a background job. The storage module provides it as
  `:wagoe/export-jobs` when the jobs module runs; another module refers to it
  with `(ig/refset :wagoe/export-jobs)`, empty without them."
  (request-export-job! [this subject requested-by]
    "Queue an export of `subject` for the signed-in user `requested-by`, or
    return the one they have open for it. A 202 Ring response, `Location` the
    job's status endpoint, where they download the archive once it is done.
    Throws :rate-limit-exceeded over their hourly limit, and :conflict while
    someone else's export of `subject` is open."))
