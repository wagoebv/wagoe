(ns wagoe.error-shape-test
  "Every JSON error body the framework sends is
   `{\"error\": {\"type\": …, \"message\": …}}` (BOU-586).

   Swept over the real route table with every module on, as an anonymous
   caller, a user and an admin, with an empty and a malformed body, plus a
   method no route takes and a path nobody serves. A client needs one parser;
   a producer that answers in a shape of its own fails here, named. So does a
   5xx or an escaped exception: every call is a client's mistake."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.platform.core.http.errors :as errors]
            [wagoe.user.shell.auth :as auth]
            [support.route-table :as rt]))

(defn- bearer [{:keys [id email role]}]
  {"authorization"
   (str "Bearer " (auth/create-jwt-token {:id id :email email :role role} 1))})

(def ^:dynamic ^:private callers
  "Bound by `with-seeded-callers`: anonymous, the seeded user and admin — real
   rows, so a handler that reads its caller finds one — and a valid token for
   a user with no row, as one deleted since sign-in holds."
  nil)

(defn- with-seeded-callers [f]
  (let [seeded (get-in (rt/decoded (rt/call :post "/test/reset" "{}")) [:seeded])]
    (assert (get-in seeded [:admin :id]) (str "/test/reset seeded no admin: " (pr-str seeded)))
    (binding [callers {:anonymous {}
                       :user      (bearer (update (:user seeded) :role keyword))
                       :admin     (bearer (update (:admin seeded) :role keyword))
                       :deleted   (bearer {:id (random-uuid) :email "gone@error-shape.test" :role :user})}]
      (f))))

(use-fixtures :once (rt/system-fixture "error-shape") with-seeded-callers)

(def ^:private skipped
  "Routes not swept, and why."
  {[:post "/test/reset"] "truncates the database the other calls read"})

(defn- one-shape?
  "The body is the one error shape. A body that is not JSON is judged by the
   path: a page may answer HTML, an API may not."
  [uri response]
  (let [body (rt/decoded response)]
    (cond
      (map? body)          (errors/error-body? body)
      (str/starts-with? uri "/web") true
      :else                false)))

(defn- offence
  "Why `response` fails the sweep, or nil. An exception that escaped the
   stack (`rt/call` reports it as a string status) and a 5xx are offences
   whatever their body: every call here is a client's mistake — an empty or
   malformed body, an id that names nothing — and a client's mistake is a
   4xx (BOU-586)."
  [uri {:keys [status] :as response}]
  (cond
    (string? status)               "escaped the stack"
    (< status 400)                 nil
    (>= status 500)                "a 5xx for the client's mistake"
    (not (one-shape? uri response)) "not the one shape"))

(defn- offenders
  "`[who METHOD uri status why]` for every response in `calls` that is an
   offence. `calls` are `[who method uri body headers]`."
  [calls]
  (->> (for [[who m uri body headers] calls
             :let  [response (rt/call m uri body headers)
                    why      (offence uri response)]
             :when why]
         [(name who) (str/upper-case (name m)) uri (:status response) (str "— " why)])
       distinct
       sort))

(defn- report [found]
  (str (count found) " response(s) the sweep refuses:\n"
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

(deftest ^:integration no-route-answers-the-missing-type-diagnostic
  ;; The dev diagnostic is for an app's ex-info without :type. A framework
  ;; route that answers it has thrown something the framework never typed —
  ;; push's devices, where the interceptor runner wrapped a JDBC error (BOU-586).
  (let [table (remove (fn [[p m]] (contains? skipped [m p])) (rt/route-table))
        found (->> (for [[path m]   table
                         [who hdrs] callers
                         :let  [uri (rt/concrete-uri path)
                                r   (rt/call m uri (:empty rt/bodies) hdrs)]
                         :when (= "missing-error-type" (get-in (rt/decoded r) [:error :type]))]
                     [(name who) (str/upper-case (name m)) uri])
                    distinct sort)]
    (is (empty? found) (str/join "\n" (map #(str/join " " %) found)))))

(deftest ^:integration an-unknown-admin-entity-is-a-404
  (doseq [uri ["/web/admin/no-such-entity" "/web/admin/no-such-entity/new"
               "/web/admin/no-such-entity/00000000-0000-0000-0000-000000000001"]]
    (let [r (rt/call :get uri (:empty rt/bodies) (:admin callers))]
      (is (= 404 (:status r)) uri)
      (is (= "not-found" (get-in (rt/decoded r) [:error :type])) uri))))
