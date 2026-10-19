(ns wagoe.external.core.soap-test
  "Envelope building, response and fault reading, and the retry rule, on
   iBABS-shaped XML (List.svc, API v2)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.external.core.soap :as soap]
            [wagoe.external.shell.adapters.soap :as adapter]))

(def ^:private ibabs-ns "http://schemas.datacontract.org/2004/07/iBabsWebService.Objects")

(def ^:private config
  {:contract       "IList"
   :data-namespace ibabs-ns
   :retry-faults   ["Unknown fault occured"]
   :operations     {:GetListEntries {:timeout-ms 20000 :arrays #{:list-entries}}}})

(defn- parse [^String xml]
  (#'adapter/parse-xml (.getBytes xml "UTF-8")))

(defn- read-xml [op xml]
  (soap/read-envelope (parse xml) (soap/operation-spec config op {})))

(def get-lists-response
  "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">
     <s:Body>
       <GetListsResponse xmlns=\"http://tempuri.org/\">
         <GetListsResult xmlns:a=\"http://schemas.datacontract.org/2004/07/iBabsWebService.Objects\"
                         xmlns:i=\"http://www.w3.org/2001/XMLSchema-instance\">
           <a:Message i:nil=\"true\"/>
           <a:Status>0</a:Status>
           <a:Lists>
             <a:iBabsList><a:ListId>11111111-aaaa-bbbb-cccc-000000000001</a:ListId><a:Name>Vergaderingen</a:Name></a:iBabsList>
             <a:iBabsList><a:ListId>11111111-aaaa-bbbb-cccc-000000000002</a:ListId><a:Name>Activiteiten</a:Name></a:iBabsList>
           </a:Lists>
         </GetListsResult>
       </GetListsResponse>
     </s:Body>
   </s:Envelope>")

(def get-list-entries-response
  "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">
     <s:Body>
       <GetListEntriesResponse xmlns=\"http://tempuri.org/\">
         <GetListEntriesResult xmlns:a=\"http://schemas.datacontract.org/2004/07/iBabsWebService.Objects\"
                               xmlns:i=\"http://www.w3.org/2001/XMLSchema-instance\">
           <a:ListEntries>
             <a:iBabsListEntryBasic>
               <a:EntryId>42</a:EntryId>
               <a:MasterId>m-42</a:MasterId>
               <a:Values xmlns:b=\"http://schemas.microsoft.com/2003/10/Serialization/Arrays\">
                 <b:KeyValueOfstringstring><b:Key>Titel</b:Key><b:Value>Raadsvergadering</b:Value></b:KeyValueOfstringstring>
                 <b:KeyValueOfstringstring><b:Key>Locatie</b:Key><b:Value i:nil=\"true\"/></b:KeyValueOfstringstring>
               </a:Values>
               <a:Tags xmlns:b=\"http://schemas.microsoft.com/2003/10/Serialization/Arrays\">
                 <b:string>raad</b:string>
               </a:Tags>
             </a:iBabsListEntryBasic>
           </a:ListEntries>
           <a:Message i:nil=\"true\"/>
           <a:Status>0</a:Status>
         </GetListEntriesResult>
       </GetListEntriesResponse>
     </s:Body>
   </s:Envelope>")

(def get-list-entry-by-master-id-response
  "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">
     <s:Body>
       <GetListEntryByMasterIdResponse xmlns=\"http://tempuri.org/\">
         <GetListEntryByMasterIdResult xmlns:a=\"http://schemas.datacontract.org/2004/07/iBabsWebService.Objects\"
                                       xmlns:i=\"http://www.w3.org/2001/XMLSchema-instance\">
           <a:ListEntry>
             <a:EntryId>42</a:EntryId>
             <a:Values xmlns:b=\"http://schemas.microsoft.com/2003/10/Serialization/Arrays\">
               <b:KeyValueOfstringstring><b:Key>Titel</b:Key><b:Value>Raad &amp; commissie</b:Value></b:KeyValueOfstringstring>
             </a:Values>
           </a:ListEntry>
           <a:Status>0</a:Status>
         </GetListEntryByMasterIdResult>
       </GetListEntryByMasterIdResponse>
     </s:Body>
   </s:Envelope>")

(def throttle-fault
  "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">
     <s:Body>
       <s:Fault>
         <faultcode xmlns:a=\"http://schemas.microsoft.com/net/2005/12/windowscommunicationfoundation/dispatcher\">a:InternalServiceFault</faultcode>
         <faultstring xml:lang=\"en-US\">Unknown fault occured</faultstring>
       </s:Fault>
     </s:Body>
   </s:Envelope>")

(deftest ^:unit operation-spec-test
  (testing "WCF's default SOAPAction and element"
    (let [spec (soap/operation-spec config :GetLists {})]
      (is (= "http://tempuri.org/IList/GetLists" (:action spec)))
      (is (= "GetLists" (:element spec)))
      (is (= 30000 (:timeout-ms spec)))
      (is (:idempotent? spec))))

  (testing "per-operation and per-call settings win"
    (is (= 20000 (:timeout-ms (soap/operation-spec config :GetListEntries {}))))
    (is (= 5000 (:timeout-ms (soap/operation-spec config "GetListEntries" {:timeout-ms 5000}))))
    (is (= "urn:x" (:action (soap/operation-spec (assoc-in config [:operations :GetLists :action] "urn:x")
                                                 :GetLists {}))))))

(deftest ^:unit envelope-test
  (let [spec (soap/operation-spec config :GetListEntries {})]
    (testing "parameters in the operation namespace, members in the data contract's, in order"
      (let [xml (soap/envelope spec [[:Sitename "town"]
                                     [:filterRequest {:SinceDate "2026-10-01T00:00:00"
                                                      :ListId    "11111111-aaaa-bbbb-cccc-000000000001"
                                                      :Sitename  "town"}]])]
        (is (str/includes? xml "xmlns:t=\"http://tempuri.org/\""))
        (is (str/includes? xml (str "xmlns:d=\"" ibabs-ns "\"")))
        (is (str/includes? xml (str "<s:Body><t:GetListEntries><t:Sitename>town</t:Sitename>"
                                    "<t:filterRequest><d:ListId>11111111-aaaa-bbbb-cccc-000000000001</d:ListId>"
                                    "<d:SinceDate>2026-10-01T00:00:00</d:SinceDate><d:Sitename>town</d:Sitename>"
                                    "</t:filterRequest></t:GetListEntries></s:Body>")))
        (testing "and the result is well-formed XML"
          (is (= "Envelope" (:name (parse xml)))))))

    (testing "a map is written ordinally by name, as DataContract does"
      (is (str/includes? (soap/envelope spec {:filterRequest {} :Sitename "t"})
                         "<t:Sitename>t</t:Sitename><t:filterRequest></t:filterRequest>")))

    (testing "nil, escaping, dates and arrays"
      (let [xml (soap/envelope spec {:A nil :B "a<b & \"c\"" :C #inst "2026-10-01T00:00:00Z" :D ["x" "y"] :E [1]})]
        (is (str/includes? xml "<t:A i:nil=\"true\"/>"))
        (is (str/includes? xml "<t:B>a&lt;b &amp; &quot;c&quot;</t:B>"))
        (is (str/includes? xml "<t:C>2026-10-01T00:00:00Z</t:C>"))
        (is (str/includes? xml "<t:D><arr:string>x</arr:string><arr:string>y</arr:string></t:D>"))
        (is (str/includes? xml "<t:E><arr:int>1</arr:int></t:E>"))))))

(deftest ^:unit with-credentials-test
  (is (= [["username" "u"] ["password" "p"] [:Sitename "t"]]
         (soap/with-credentials {:Sitename "t"} {:credential-elements ["username" "password"]
                                                 :username "u" :password "p"})))
  (is (= {:Sitename "t"} (soap/with-credentials {:Sitename "t"} {:username "u" :password "p"}))))

(deftest ^:unit read-get-lists-test
  (let [{:keys [result body]} (read-xml :GetLists get-lists-response)]
    (is (= {:message nil
            :status  "0"
            :lists   [{:list-id "11111111-aaaa-bbbb-cccc-000000000001" :name "Vergaderingen"}
                      {:list-id "11111111-aaaa-bbbb-cccc-000000000002" :name "Activiteiten"}]}
           result))
    (is (= result (:get-lists-result body)))))

(deftest ^:unit read-get-list-entries-test
  (testing "a one-item array named in :arrays, KeyValueOfstringstring as a map, a string array"
    (is (= {:list-entries [{:entry-id  "42"
                            :master-id "m-42"
                            :values    {"Titel" "Raadsvergadering" "Locatie" nil}
                            :tags      ["raad"]}]
            :message      nil
            :status       "0"}
           (:result (read-xml :GetListEntries get-list-entries-response)))))

  (testing "without the hint one item reads as a map"
    (is (map? (get-in (soap/read-envelope (parse get-list-entries-response)
                                          (soap/operation-spec {} :GetListEntries {}))
                      [:result :list-entries])))))

(deftest ^:unit read-get-list-entry-by-master-id-test
  (is (= {:list-entry {:entry-id "42" :values {"Titel" "Raad & commissie"}}
          :status     "0"}
         (:result (read-xml :GetListEntryByMasterId get-list-entry-by-master-id-response)))))

(deftest ^:unit read-fault-test
  (is (= {:fault {:code "a:InternalServiceFault" :message "Unknown fault occured"}}
         (read-xml :GetLists throttle-fault)))
  (testing "detail is read as data"
    (is (= {:code "s:Client" :message "Bad" :detail {:reason "no list"}}
           (:fault (read-xml :GetLists (str "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                                            "<s:Fault><faultcode>s:Client</faultcode><faultstring>Bad</faultstring>"
                                            "<detail><Reason>no list</Reason></detail></s:Fault></s:Body></s:Envelope>")))))))

(deftest ^:unit read-malformed-test
  (is (:malformed (read-xml :GetLists "<html><body>Service Unavailable</body></html>")))
  (is (:malformed (read-xml :GetLists "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>"))))

(deftest ^:unit outcome-test
  (testing "a fault wins over the status, then the status, then an unreadable body"
    (is (= :fault (get-in (soap/outcome 500 {:fault {:message "x"}}) [:failure :kind])))
    (is (= 503 (get-in (soap/outcome 503 {:malformed "html"}) [:failure :status])))
    (is (= :malformed (get-in (soap/outcome 200 {:malformed "html"}) [:failure :kind])))
    (is (= {:value {:body {} :result nil}} (soap/outcome 200 {:body {} :result nil})))))

(deftest ^:unit retry-rule-test
  (let [spec      (soap/operation-spec config :GetLists {})
        writes    (assoc spec :idempotent? false)
        throttled {:kind :fault :fault {:message "Unknown fault occured"}}
        other     {:kind :fault :fault {:message "List not found"}}]
    (testing "faults: only the configured ones"
      (is (soap/retryable? throttled spec))
      (is (soap/retryable? throttled writes))
      (is (not (soap/retryable? other spec)))
      (is (soap/retryable? other (assoc spec :retry-faults [#"not found$"]))))

    (testing "transport: a call that never connected always, one that may have run only when idempotent"
      (is (soap/retryable? {:kind :unconnected} writes))
      (is (soap/retryable? {:kind :timeout} spec))
      (is (not (soap/retryable? {:kind :timeout} writes)))
      (is (soap/retryable? {:kind :status :status 503} spec))
      (is (not (soap/retryable? {:kind :status :status 503} writes)))
      (is (soap/retryable? {:kind :status :status 429} writes))
      (is (not (soap/retryable? {:kind :status :status 404} spec)))
      (is (not (soap/retryable? {:kind :malformed} spec))))

    (testing "what counts against the circuit"
      (is (soap/unavailable? throttled spec))
      (is (not (soap/unavailable? other spec)))
      (is (soap/unavailable? {:kind :timeout} writes)))))

(deftest ^:unit soap-error-test
  (let [spec (soap/operation-spec config :GetLists {})]
    (is (= {:type :fault :code "a:X" :message "Unknown fault occured" :operation "GetLists" :attempts 3}
           (soap/soap-error {:kind :fault :fault {:code "a:X" :message "Unknown fault occured"}} spec "https://h/List.svc" 3)))
    (let [error (soap/soap-error {:kind :status :status 503 :cause "HTTP 503"} spec "https://h/List.svc" 1)]
      (is (= :external-error (:type error)))
      (is (= 503 (:status error)))
      (is (= "SOAP GetLists on https://h/List.svc failed after 1 attempt: HTTP 503" (:message error))))))
