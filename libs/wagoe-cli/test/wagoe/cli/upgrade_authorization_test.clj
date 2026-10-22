(ns wagoe.cli.upgrade-authorization-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.cli.upgrade-authorization :as sut]))

(def ^:private old-config
  "What an app generated with 1.0.1 has: no :wagoe/authorization."
  "{:active
 {:wagoe/admin
  {:enabled?     true
   :require-role :admin
   :base-path    \"/web/admin\"}

  :wagoe/user
  {:oidc {:providers {:google {:issuer #env OIDC_ISSUER :default-role :viewer}
                      :azure  {:issuer \"https://login\"}}}}

  :wagoe/logging
  {:provider :slf4j :level :info}}

 :inactive
 {:wagoe/h2 {:url \"jdbc:h2:mem:x\"}}}
")

(deftest ^:unit config-facts-read-the-legacy-settings
  (is (= {:mode :off :require-role? true :default-roles [[:google :viewer]]}
         (update (sut/config-facts old-config) :default-roles vec)))
  (is (= {:mode :report :require-role? false :default-roles nil}
         (sut/config-facts "{:active {:wagoe/authorization {:mode :report}}}"))))

(deftest ^:unit prepare-rewrites-are-idempotent
  (let [once  (-> old-config sut/drop-require-role (sut/set-mode :report))
        twice (-> once sut/drop-require-role (sut/set-mode :report))]
    (is (= once twice))
    (is (not (str/includes? once ":require-role")))
    (is (str/includes? once "   :base-path    \"/web/admin\"}") "the rest of the map is kept as written")
    (is (str/includes? once "  :wagoe/authorization\n  {:mode :report}}"))
    (is (str/includes? once "#env OIDC_ISSUER") "Aero tags survive")
    (is (= {:mode :report :require-role? false} (dissoc (sut/config-facts once) :default-roles)))
    (testing "enforce changes the mode in place"
      (is (= (str/replace once "{:mode :report}" "{:mode :enforce}") (sut/set-mode once :enforce))))
    (testing "a map without :mode gets one"
      (is (= :enforce (:mode (sut/config-facts (sut/set-mode "{:active {:wagoe/authorization {}}}" :enforce))))))))

(deftest ^:unit admin-entities-missing-from-the-allowlist-are-found-and-added
  (let [dir  (io/file (System/getProperty "java.io.tmpdir") (str "wagoe-authz-allow-" (System/nanoTime)))
        text "{:active {:wagoe/admin {:enabled? true\n                          :entities #merge [#include \"admin/products.edn\"\n                                            {:orders {:label \"Orders\"}}]}}}"]
    (io/make-parents (io/file dir "admin" "products.edn"))
    (spit (io/file dir "admin" "products.edn") "{:products {:label \"Products\" :fields {:name {:label #env NAME}}}}")
    (try
      (is (= [:orders :products] (sut/unlisted-admin-entities text dir)))
      (let [added (sut/allow-entities text [:orders :products])]
        (is (= #{:orders :products} (get-in (read-string (str/replace added #"#merge|#include|#env" ""))
                                            [:active :wagoe/admin :entity-discovery :allowlist])))
        (is (str/includes? added "#include \"admin/products.edn\"") "the rest is kept as written")
        (is (empty? (sut/unlisted-admin-entities added dir)))
        (testing "an allowlist that exists is extended"
          (let [more (sut/allow-entities (str/replace added ":products" ":tenants") [:products])]
            (is (empty? (sut/unlisted-admin-entities more dir))))))
      (is (= text (sut/allow-entities text [])) "nothing to add, nothing changed")
      (finally (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))

(deftest ^:unit the-log-is-summarised-per-permission-and-route
  (let [log (str "12:00 WARN  w.p.s.authorization - Authorization would refuse this request under :enforce "
                 "{:mode :report, :user-id #uuid \"00000000-0000-0000-0000-00000000000a\", :route /api/v1/orders,"
                 " :method :post, :permission :orders/write, :reason :forbidden}\n"
                 "12:01 INFO  something else {:a 1}\n"
                 "12:02 WARN  w.p.s.authorization - Authorization would refuse this request under :enforce "
                 "{:mode :report, :user-id #uuid \"00000000-0000-0000-0000-00000000000b\", :route /api/v1/orders,"
                 " :method :post, :permission :orders/write, :reason :forbidden}\n"
                 "12:03 WARN  w.p.s.authorization - Authorization would refuse this request under :enforce "
                 "{:mode :report, :user-id #uuid \"00000000-0000-0000-0000-00000000000a\", :route /legacy/:id,"
                 " :method :get, :permission nil, :reason :forbidden}\n")
        entries (sut/log-refusals log)]
    (is (= 3 (count entries)))
    (is (= {:mode :report :user-id "00000000-0000-0000-0000-00000000000a" :route "/legacy/:id"
            :method :get :permission nil :reason :forbidden}
           (last entries))
        "the map is logged with print: a route has no quotes")
    (is (= ["  (no rule)  GET /legacy/:id  1 user(s): 00000000-0000-0000-0000-00000000000a"
            "  orders/write  POST /api/v1/orders  2 user(s): 00000000-0000-0000-0000-00000000000a, 00000000-0000-0000-0000-00000000000b"]
           (sut/format-log-refusals entries)))
    (is (= ["  None."] (sut/format-log-refusals [])))))

(defn- project! []
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "wagoe-authz-" (System/nanoTime)))]
    (io/make-parents (io/file dir "resources" "conf" "dev" "config.edn"))
    (spit (io/file dir "deps.edn") "{:deps {com.wagoe/wagoe-user {:local/root \"x\"}} :aliases {:run {:main-opts [\"-m\" \"acme.main\"]}}}")
    (spit (io/file dir "resources" "conf" "dev" "config.edn") old-config)
    dir))

(defn- config-of [dir] (slurp (io/file dir "resources" "conf" "dev" "config.edn")))

(defn- run [dir jvm-codes & args]
  (let [calls (atom [])]
    (binding [sut/*jvm* (fn [{:keys [env]} step] (swap! calls conj [env step]) (get jvm-codes step 0))]
      (let [out (with-out-str (reset! calls [(sut/run dir (concat args ["--env" "dev"])) @calls]))]
        (conj @calls out)))))

(deftest ^:unit the-steps-write-config-only-when-they-should
  (let [dir (project!)]
    (try
      (is (= "acme.system-config" (sut/config-ns dir {:aliases {:run {:main-opts ["-m" "acme.main"]}}})))
      (is (nil? (sut/config-ns dir {:aliases {:run {:main-opts ["-m" "acme.core"]}}}))
          "a main namespace not ending in .main names no system config")
      (testing "check and a dry-run prepare write nothing"
        (let [[code calls out] (run dir {} "check")]
          (is (= 0 code))
          (is (= [["dev" "check"]] calls))
          (is (str/includes? out "OIDC provider :google :default-role :viewer"))
          (is (str/includes? out "Verify:")))
        (let [[code calls out] (run dir {} "prepare" "--dry-run")]
          (is (= [0 [["dev" "check"]]] [code calls]) "a dry run reads the database state only")
          (is (str/includes? out "+  {:mode :report}}"))
          (is (= old-config (config-of dir)))))
      (testing "enforce refuses from :off"
        (is (= 1 (first (run dir {} "enforce"))))
        (is (= old-config (config-of dir))))
      (testing "prepare writes :report and runs in the app"
        (let [[code calls] (run dir {} "prepare")]
          (is (= [0 [["dev" "prepare"]]] [code calls]))
          (is (= :report (:mode (sut/config-facts (config-of dir)))))))
      (testing "a report step that crashed never switches, even forced"
        (is (= 137 (first (run dir {"report" 137} "enforce" "--force"))))
        (is (= :report (:mode (sut/config-facts (config-of dir))))))
      (testing "a flag without its value is refused"
        (let [[code _ out] (run dir {} "enforce" "--log")]
          (is (= 1 code))
          (is (str/includes? out "--log needs a value"))))
      (testing "enforce refuses while the app reports a refusal, unless forced"
        (let [[code _ out] (run dir {"report" 2} "enforce")]
          (is (= 2 code))
          (is (str/includes? out "Not switched"))
          (is (= :report (:mode (sut/config-facts (config-of dir))))))
        (let [[code _ out] (run dir {"report" 2} "enforce" "--force")]
          (is (= 0 code))
          (is (str/includes? out "To roll back, set :wagoe/authorization {:mode :report}"))
          (is (= :enforce (:mode (sut/config-facts (config-of dir)))))))
      (testing "enforce again is a no-op"
        (let [[code calls] (run dir {} "enforce")]
          (is (= [0 []] [code calls]))))
      (testing "an unknown step prints usage"
        (is (= 1 (first (run dir {} "nope")))))
      (finally (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))
