(ns wagoe.core.utils.redirect-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.core.utils.redirect :as redirect]))

(deftest ^:unit ^:security local-path?-refuses-anything-a-browser-could-leave-the-site-by
  (doseq [[s expected]
          [["/\\evil.com"        false]
           ["/%5Cevil.com"      false]
           ["/%5cevil.com"      false]
           ["//evil.com"        false]
           ["/%2F%2Fevil.com"   false]
           ["/%2f/evil.com"     false]
           ["https://evil.com"  false]
           ["javascript:alert(1)" false]
           ["/\t/evil.com"      false]
           ["/web/x\ny"         false]
           ["/web/x%0Ay"        false]
           ["/web/x y"          false]
           ["/web/%zz"          false]
           [""                  false]
           [nil                 false]
           ["web/x"             false]
           ["/"                 true]
           ["/web/x?y=1"        true]
           ["/web/x#frag"       true]
           ["/web/x?q=a+b%20c"  true]
           ["/web/admin/users/%C3%A9" true]]]
    (is (= expected (redirect/local-path? s)) (pr-str s))))
