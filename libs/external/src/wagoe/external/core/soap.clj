(ns wagoe.external.core.soap
  "Pure functions for the SOAP 1.1 client: operation settings, the request
   envelope, reading the response or fault, which failures to retry and error
   values. No I/O.

   The shell parses XML into elements of this shape, and serialises nothing:
   the envelope is built here as a string.

     {:name \"GetListsResult\" :ns \"http://tempuri.org/\"
      :nil? false :text \"\" :children [element ...]}"
  (:require [clojure.string :as str]
            [wagoe.core.utils.case-conversion :as cc]))

(def envelope-ns "http://schemas.xmlsoap.org/soap/envelope/")
(def xsi-ns "http://www.w3.org/2001/XMLSchema-instance")
(def arrays-ns "http://schemas.microsoft.com/2003/10/Serialization/Arrays")

;; =============================================================================
;; Operations
;; =============================================================================

(defn operation-spec
  "Settings for one call: the client's defaults, then (:operations config) for
   op, then the per-call opts. The SOAPAction is WCF's default,
   namespace + contract + \"/\" + operation, unless :action is given."
  [config op opts]
  (let [op-name (name op)
        spec    (merge {:namespace   "http://tempuri.org/"
                        :timeout-ms  30000
                        :idempotent? true}
                       (select-keys config [:namespace :data-namespace :contract :timeout-ms :retry-faults])
                       (get (:operations config) (keyword op-name))
                       opts)
        ns-uri  (:namespace spec)]
    (merge {:operation op-name
            :element   op-name
            :action    (str ns-uri
                            (when (and (:contract spec) (not (str/ends-with? ns-uri "/"))) "/")
                            (some-> (:contract spec) (str "/"))
                            op-name)
            :data-namespace ns-uri}
           spec
           {:operation op-name})))

(defn with-credentials
  "Request with the credentials prepended as the parameters named by
   :credential-elements, e.g. [\"username\" \"password\"]."
  [request {:keys [credential-elements username password]}]
  (if-let [[user-el pass-el] credential-elements]
    (into [[user-el username] [pass-el password]]
          (if (map? request) (sort-by (comp name key) request) request))
    request))

;; =============================================================================
;; Envelope
;; =============================================================================

(defn- escape [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"}))

(defn pairs?
  "Whether v is an ordered request: a sequence of [name value] pairs."
  [v]
  (and (sequential? v)
       (seq v)
       (every? #(and (vector? %) (= 2 (count %)) (or (keyword? (first %)) (string? (first %)))) v)))

(defn valid-request?
  "Whether request can be sent: nil, a map, or [name value] pairs."
  [request]
  (or (nil? request) (map? request) (pairs? request)))

(defn- members
  "A map's entries in DataContract's default order (ordinal by name), or the
   pairs as given."
  [v]
  (if (map? v) (sort-by (comp name key) v) v))

(defn- scalar [v]
  (cond
    (keyword? v)                  (name v)
    (instance? java.util.Date v)  (str (.toInstant ^java.util.Date v))
    :else                         (str v)))

(defn- array-item-name [v]
  (cond
    (string? v)  "string"
    (int? v)     "int"
    (boolean? v) "boolean"
    :else        "string"))

(declare element-xml)

(defn- content-xml [v]
  (cond
    (or (map? v) (pairs? v)) (apply str (map (fn [[k x]] (element-xml "d" k x)) (members v)))
    (sequential? v)          (apply str (map #(element-xml "arr" (array-item-name %) %) v))
    :else                    (escape (scalar v))))

(defn- element-xml [prefix k v]
  (let [tag (str prefix ":" (name k))]
    (if (nil? v)
      (str "<" tag " i:nil=\"true\"/>")
      (str "<" tag ">" (content-xml v) "</" tag ">"))))

(defn envelope
  "The SOAP 1.1 envelope for a call. Parameters are in the operation's
   namespace; members of nested values in :data-namespace (WCF's
   http://schemas.datacontract.org/2004/07/<CLR namespace>); a vector of
   scalars is a WCF array (string, int, boolean items).

   Element names are written as given: the contract is case-sensitive and its
   parameters are often camelCase (filterRequest) where members are PascalCase.
   A map is written in DataContract's default member order, ordinal by name;
   pass [name value] pairs where the contract declares another order."
  [{:keys [element namespace data-namespace]} request]
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<s:Envelope xmlns:s=\"" envelope-ns "\" xmlns:i=\"" xsi-ns "\""
       " xmlns:t=\"" (escape namespace) "\" xmlns:d=\"" (escape data-namespace) "\""
       " xmlns:arr=\"" arrays-ns "\">"
       "<s:Body><t:" element ">"
       (apply str (map (fn [[k v]] (element-xml "t" k v)) (members request)))
       "</t:" element "></s:Body></s:Envelope>"))

;; =============================================================================
;; Response
;; =============================================================================

(defn element-key
  "The kebab-case keyword for an element name: ListEntries -> :list-entries."
  [el-name]
  (keyword (cc/camel-case->kebab-case-string el-name)))

(defn- child [el child-name]
  (first (filter #(= child-name (:name %)) (:children el))))

(defn- key-value? [el]
  (and (= arrays-ns (:ns el)) (str/starts-with? (:name el) "KeyValueOf")))

(defn- array? [el arrays]
  (let [kids (:children el)]
    (or (contains? arrays (element-key (:name el)))
        (str/starts-with? (:name el) "ArrayOf")
        (some #(= arrays-ns (:ns %)) kids)
        (and (next kids) (apply = (map :name kids))))))

(defn element-value
  "An element as data. xsi:nil is nil; KeyValueOf… items are a map with the
   keys as strings; a WCF array is a vector; any other element with children a
   map with kebab-case keys; a leaf its text, a string.

   An array is recognised by an ArrayOf… name, items in the Serialization/Arrays
   namespace, or two or more children of one name. Name one that may hold a
   single item, or none, in arrays (#{:list-entries}), or it reads as a map."
  [el arrays]
  (let [kids (:children el)]
    (cond
      (:nil? el)
      nil

      (and (seq kids) (every? key-value? kids))
      (into {} (map (fn [kv] [(:text (child kv "Key")) (some-> (child kv "Value") (element-value arrays))]))
            kids)

      (array? el arrays)
      (mapv #(element-value % arrays) kids)

      (seq kids)
      (reduce (fn [m kid]
                (let [k (element-key (:name kid))
                      v (element-value kid arrays)]
                  ;; A repeated name among other members keeps every value.
                  (cond
                    (not (contains? m k)) (assoc m k v)
                    (vector? (get m k))   (update m k conj v)
                    :else                 (update m k vector v))))
              {} kids)

      :else
      (:text el ""))))

(defn- fault [fault-el arrays]
  (let [text #(some-> (child fault-el %) :text str/trim)]
    (cond-> {:code    (text "faultcode")
             :message (or (text "faultstring") "SOAP fault")}
      (child fault-el "faultactor") (assoc :actor (text "faultactor"))
      (child fault-el "detail")     (assoc :detail (element-value (child fault-el "detail") arrays)))))

(defn read-envelope
  "Read a parsed response envelope: {:body .. :result ..}, {:fault ..}, or
   {:malformed cause}. :body is the response element as data, :result its
   <Operation>Result."
  [root {:keys [operation arrays]}]
  (let [body     (when (and (= "Envelope" (:name root)) (= envelope-ns (:ns root)))
                   (first (filter #(and (= "Body" (:name %)) (= envelope-ns (:ns %))) (:children root))))
        response (first (:children body))
        arrays   (set arrays)]
    (cond
      (nil? body)
      {:malformed "not a SOAP 1.1 envelope"}

      (and (= "Fault" (:name response)) (= envelope-ns (:ns response)))
      {:fault (fault response arrays)}

      (nil? response)
      {:malformed "empty SOAP body"}

      :else
      (let [data (element-value response arrays)]
        {:body   data
         :result (when (map? data) (get data (element-key (str operation "Result"))))}))))

;; =============================================================================
;; Retry policy
;; =============================================================================

(defn retry-fault?
  "Whether a fault's message matches one of retry-faults: a string it
   contains, or a regex. iBABS answers a throttled call with the bare fault
   \"Unknown fault occured\"."
  [retry-faults message]
  (boolean (some #(if (string? %)
                    (str/includes? (str message) %)
                    (re-find % (str message)))
                 retry-faults)))

(defn unavailable?
  "Whether a failure says the service could not answer: no connection, a
   timeout, an I/O error, a 429 or 5xx without a fault, or a fault named in
   :retry-faults. This is what counts against the circuit."
  [{:keys [kind status fault]} {:keys [retry-faults]}]
  (case kind
    (:unconnected :timeout :io) true
    :status                     (or (= 429 status) (>= status 500))
    :fault                      (retry-fault? retry-faults (:message fault))
    false))

(defn retryable?
  "Whether a failed attempt is worth repeating. A call that never connected, a
   429 and a fault named in :retry-faults were not carried out. A timeout, an
   I/O error or a 5xx may have been, so they are retried only for an
   :idempotent? operation, the default."
  [{:keys [kind status] :as failure} {:keys [idempotent?] :as spec}]
  (cond
    (#{:unconnected :fault} kind) (unavailable? failure spec)
    (= :status kind)              (or (= 429 status) (and idempotent? (>= status 500)))
    :else                         (and idempotent? (unavailable? failure spec))))

;; =============================================================================
;; Error values
;; =============================================================================

(defn soap-error
  "The :error map of a failed call. A fault is :fault with the server's
   :code and :message; anything else :external-error naming the endpoint."
  [{:keys [kind fault status cause]} {:keys [operation]} endpoint attempts]
  (if (= :fault kind)
    (assoc fault :type :fault :operation operation :attempts attempts)
    (cond-> {:type      :external-error
             :message   (format "SOAP %s on %s failed after %d attempt%s: %s"
                                operation endpoint attempts (if (= 1 attempts) "" "s") cause)
             :endpoint  endpoint
             :operation operation
             :attempts  attempts
             :cause     cause}
      status (assoc :status status))))
