(ns wagoe.error-shape-test
  "Every JSON error body the framework sends is
   `{\"error\": {\"type\": …, \"message\": …}}` (BOU-586).

   Swept over the real route table with every module on, as an anonymous
   caller, a user and an admin, with an empty and a malformed body, plus a
   method no route takes and a path nobody serves. A client needs one parser;
   a producer that answers in a shape of its own fails here, named."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.platform.core.http.errors :as errors]
            [wagoe.user.shell.auth :as auth]
            [support.route-table :as rt]))

(use-fixtures :once (rt/system-fixture "error-shape"))

(defn- bearer [role]
  {"authorization"
   (str "Bearer " (auth/create-jwt-token
                   {:id    (random-uuid)
                    :email (str (name role) "@error-shape.test")
                    :role  role}
                   1))})

(def ^:private callers
  {:anonymous {}
   :user      (bearer :user)
   :admin     (bearer :admin)})

(def ^:private skipped
  "Routes not swept, and why."
  {[:post "/test/reset"] "truncates the database the other calls read"})

(defn- error-status? [{:keys [status]}]
  (and (int? status) (>= status 400)))

(defn- one-shape?
  "The body is the one error shape. A body that is not JSON is judged by the
   path: a page may answer HTML, an API may not."
  [uri response]
  (let [body (rt/decoded response)]
    (cond
      (map? body)          (errors/error-body? body)
      (str/starts-with? uri "/web") true
      :else                false)))

(defn- offenders
  "`[who METHOD uri status]` for every error response in `calls` that is not
   in the one shape. `calls` are `[who method uri body headers]`."
  [calls]
  (->> (for [[who m uri body headers] calls
             :let  [response (rt/call m uri body headers)]
             :when (error-status? response)
             :when (not (one-shape? uri response))]
         [(name who) (str/upper-case (name m)) uri (:status response)])
       distinct
       sort))

(defn- report [found]
  (str (count found) " error response(s) not in the one shape:\n"
       (str/join "\n" (map #(str "  " (str/join " " %)) found))))

(deftest ^:integration every-route-answers-errors-in-one-shape
  (let [table (remove (fn [[p m]] (contains? skipped [m p])) (rt/route-table))
        found (offenders
               (for [[path m]    table
                     [who hdrs]  callers
                     [_ body]    rt/bodies]
                 [who m (rt/concrete-uri path) body hdrs]))]
    (is (empty? found) (report found))))

(deftest ^:integration a-method-no-route-takes-answers-in-one-shape
  (let [by-path (group-by first (rt/route-table))
        found   (offenders
                 (for [[path rows] by-path
                       :let  [taken  (set (map second rows))
                              absent (first (remove taken [:delete :patch :put :post :get]))]
                       :when absent
                       [who hdrs] callers]
                   [who absent (rt/concrete-uri path) (:empty rt/bodies) hdrs]))]
    (testing "405s were produced at all"
      (is (some #(= 405 (:status (rt/call :delete (rt/concrete-uri %) "{}" (:admin callers))))
                (keys by-path))))
    (is (empty? found) (report found))))

(deftest ^:integration a-path-nobody-serves-answers-in-one-shape
  (let [found (offenders
               (for [uri        ["/api/v1/no-such-thing" "/no-such-thing"]
                     [who hdrs] callers]
                 [who :get uri (:empty rt/bodies) hdrs]))]
    (is (= 404 (:status (rt/call :get "/api/v1/no-such-thing"))))
    (is (empty? found) (report found))))
