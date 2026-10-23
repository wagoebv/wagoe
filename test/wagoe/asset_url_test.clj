(ns wagoe.asset-url-test
  "ui-style writes the digest into the URL and platform compares it with its
   ETag; they are two copies of one hash, and disagreeing makes no asset
   immutable (BOU-391)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wagoe.platform.shell.http.reitit-router :as reitit]
            [wagoe.ui-style :as ui-style]))

(deftest ^:contract a-versioned-asset-url-is-served-immutable
  (let [[uri query] (str/split (ui-style/asset-url "/js/forms.js") #"\?")
        resp        ((reitit/compile-routes [] {})
                     {:request-method :get :uri uri :query-string query :headers {}})]
    (is (= 200 (:status resp)))
    (is (= "public, max-age=31536000, immutable" (get-in resp [:headers "Cache-Control"])))))
