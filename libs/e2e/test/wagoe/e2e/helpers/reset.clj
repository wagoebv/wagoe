(ns wagoe.e2e.helpers.reset
  "Client-side helper for POST /test/reset. Called from e2e fixtures
   before every test to force a clean DB + baseline seed."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defn default-base-url []
  "http://localhost:3100")

(defn parse-seed-response [body]
  (:seeded body))

(defn reset-db!
  "POSTs to /test/reset on the running e2e server and returns the parsed
   SeedResult (tenant/admin/user with IDs + plain-text passwords).

   Options:
     :base-url  (default http://localhost:3100)
     :seed      :baseline | :empty  (default :baseline)

   Throws ex-info on non-200 or on :ok false in the body."
  ([] (reset-db! {}))
  ([{:keys [base-url seed] :or {base-url (default-base-url) seed :baseline}}]
   (let [resp (http/post (str base-url "/test/reset")
                         {:content-type     :json
                          :accept           :json
                          :body             (json/generate-string {:seed (name seed)})
                          :throw-exceptions false
                          :as               :json})]
     (when-not (= 200 (:status resp))
       (throw (ex-info "POST /test/reset failed"
                       {:status (:status resp) :body (:body resp)})))
     (when-not (:ok (:body resp))
       (throw (ex-info "test/reset returned ok=false"
                       {:body (:body resp)})))
     (parse-seed-response (:body resp)))))
