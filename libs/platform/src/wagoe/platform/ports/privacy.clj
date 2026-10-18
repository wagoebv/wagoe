(ns wagoe.platform.ports.privacy
  "Data export (GDPR Art. 20) and retention, contributed per module.

  A module implements `IDataExporter` for the data it owns and contributes it
  under `:data-export` in its `ig-config`; `IRetentionPolicy` likewise under
  `:retention`. Platform collects both, so no module reads another's tables.")

(defprotocol IDataExporter
  (export-data [this subject]
    "The data this module holds about `subject`, {:type :user|:tenant :id …},
    as JSON-encodable data. nil when it holds nothing for that type of subject."))

(defprotocol IRetentionPolicy
  (purge-before! [this cutoff]
    "Delete what this policy covers that is older than `cutoff`, an Instant.
    Returns the number of records deleted."))

(defprotocol IDataExport
  (export-subject [this subject]
    "Every contributed exporter's data about `subject`:
    {:format :subject :exported-at :sections {exporter-id data}}. Throws
    :validation-error for a malformed subject, and whatever an exporter throws:
    a partial export would read as a complete one."))
