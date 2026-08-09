(ns wagoe.ai.core.parsing-test
  (:require [wagoe.ai.core.parsing :as parsing]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit parse-json-response-test
  (testing "parses plain JSON"
    (let [result (parsing/parse-json-response "{\"key\": \"value\"}")]
      (is (= "value" (:key result)))))

  (testing "parses JSON wrapped in code fences"
    (let [result (parsing/parse-json-response "```json\n{\"key\": \"value\"}\n```")]
      (is (= "value" (:key result)))))

  (testing "returns error map for invalid JSON"
    (let [result (parsing/parse-json-response "not json at all")]
      (is (contains? result :error))))

  (testing "returns nil for nil input"
    (is (nil? (parsing/parse-json-response nil)))))

(deftest ^:unit parse-module-spec-test
  (testing "parses valid module spec JSON"
    (let [json   "{\"module-name\": \"product\", \"entity\": \"Product\", \"fields\": [{\"name\": \"price\", \"type\": \"decimal\", \"required\": true, \"unique\": false}], \"http\": true, \"web\": true}"
          result (parsing/parse-module-spec json)]
      (is (= "product" (:module-name result)))
      (is (= "Product" (:entity result)))
      (is (= 1 (count (:fields result))))
      (is (= "price" (:name (first (:fields result)))))
      (is (= "decimal" (:type (first (:fields result)))))
      (is (true? (:http result)))
      (is (true? (:web result)))))

  (testing "returns error for missing module-name"
    (let [result (parsing/parse-module-spec "{\"entity\": \"Product\", \"fields\": []}")]
      (is (contains? result :error))))

  (testing "defaults invalid field type to string"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"x\", \"type\": \"invalid\"}]}"
          result (parsing/parse-module-spec json)]
      (is (= "string" (:type (first (:fields result)))))))

  (testing "defaults required to true"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"x\", \"type\": \"string\"}]}"
          result (parsing/parse-module-spec json)]
      (is (true? (:required (first (:fields result)))))))

  (testing "defaults unique to false"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"x\", \"type\": \"string\"}]}"
          result (parsing/parse-module-spec json)]
      (is (false? (:unique (first (:fields result)))))))

  (testing "preserves enum-values for enum fields"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"status\", \"type\": \"enum\", \"enum-values\": [\"active\", \"inactive\", \"archived\"]}]}"
          result (parsing/parse-module-spec json)
          field  (first (:fields result))]
      (is (= "enum" (:type field)))
      (is (= ["active" "inactive" "archived"] (:enum-values field)))))

  (testing "enum field without enum-values gets nil"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"status\", \"type\": \"enum\"}]}"
          result (parsing/parse-module-spec json)
          field  (first (:fields result))]
      (is (= "enum" (:type field)))
      (is (nil? (:enum-values field))))))

(deftest ^:unit module-spec->cli-args-test
  (testing "generates correct CLI args for generate command"
    (let [spec {:module-name "product"
                :entity      "Product"
                :fields      [{:name "price" :type "decimal" :required true :unique false}]
                :http        true
                :web         true}
          args (parsing/module-spec->cli-args spec)]
      (is (= "generate" (first args)))
      (is (some #{"product"} args))
      (is (some #{"Product"} args))
      (is (some #{"--field"} args))
      (is (some #(str/includes? % "price") args))))

  (testing "adds --no-http when http is false"
    (let [spec {:module-name "p" :entity "P" :fields [] :http false :web true}
          args (parsing/module-spec->cli-args spec)]
      (is (some #{"--no-http"} args))))

  (testing "adds --no-web when web is false"
    (let [spec {:module-name "p" :entity "P" :fields [] :http true :web false}
          args (parsing/module-spec->cli-args spec)]
      (is (some #{"--no-web"} args)))))

(deftest ^:unit parse-sql-response-test
  (testing "parses SQL response JSON"
    (let [json   "{\"honeysql\": \"{:select [:*]}\", \"explanation\": \"selects all\", \"raw-sql\": \"SELECT *\"}"
          result (parsing/parse-sql-response json)]
      (is (= "{:select [:*]}" (:honeysql result)))
      (is (= "selects all" (:explanation result)))
      (is (= "SELECT *" (:raw-sql result))))))

(deftest ^:unit parse-generated-tests-test
  (testing "strips markdown code fences"
    (let [result (parsing/parse-generated-tests "```clojure\n(ns foo-test)\n```")]
      (is (= "(ns foo-test)" result))))

  (testing "returns trimmed plain Clojure"
    (let [result (parsing/parse-generated-tests "  (ns foo-test)  ")]
      (is (= "(ns foo-test)" result))))

  (testing "returns nil for nil input"
    (is (nil? (parsing/parse-generated-tests nil)))))

(deftest ^:unit ensure-test-metadata-test
  (testing "an untagged deftest gets the test type"
    (is (= "(deftest ^:unit foo-test\n  (is (= 1 1)))"
           (parsing/ensure-test-metadata "(deftest foo-test\n  (is (= 1 1)))" :unit))))

  (testing "every deftest in the namespace is tagged, not only the first"
    ;; The measured failure was a whole namespace with no metadata, so tagging
    ;; one form would leave the rest invisible to Kaocha just the same.
    (let [src    "(deftest a-test\n  (is true))\n\n(deftest b-test\n  (is true))\n"
          result (parsing/ensure-test-metadata src :integration)]
      (is (= 2 (count (re-seq #"\^:integration" result))))))

  (testing "a deftest the model already tagged is left alone"
    (let [src "(deftest ^:contract already-test\n  (is true))"]
      (is (= src (parsing/ensure-test-metadata src :unit)))))

  (testing "a mixed namespace gains tags only where they are missing"
    (let [src    "(deftest ^:contract tagged-test\n  (is true))\n(deftest untagged-test\n  (is true))"
          result (parsing/ensure-test-metadata src :unit)]
      (is (str/includes? result "(deftest ^:contract tagged-test"))
      (is (str/includes? result "(deftest ^:unit untagged-test"))
      (is (= 1 (count (re-seq #"\^:unit" result))))))

  (testing "an indented deftest keeps its indentation"
    (is (= "  (deftest ^:unit foo-test)"
           (parsing/ensure-test-metadata "  (deftest foo-test)" :unit))))

  (testing "the word deftest inside a string or comment is not rewritten"
    ;; Anchoring to a line start is what protects these; a bare \\bdeftest\\b
    ;; would corrupt both.
    (let [src "(def doc \"call deftest here\")\n;; deftest goes at the top\n"]
      (is (= src (parsing/ensure-test-metadata src :unit)))))

  (testing "source with no deftest at all is unchanged"
    (is (= "(ns foo-test)" (parsing/ensure-test-metadata "(ns foo-test)" :unit))))

  (testing "returns nil for nil input"
    (is (nil? (parsing/ensure-test-metadata nil :unit)))))

(deftest ^:unit strip-noncode-test
  (testing "code is returned unchanged"
    (is (= "(deftest a-test)" (parsing/strip-noncode "(deftest a-test)"))))

  (testing "string contents are blanked, the quotes kept"
    (is (= "(is \"   \")" (parsing/strip-noncode "(is \"abc\")"))))

  (testing "a comment is blanked to the end of its line"
    (is (= "(a)          \n(b)" (parsing/strip-noncode "(a) ;; hidden\n(b)"))))

  (testing "line structure survives, so positions still line up"
    (let [src "(a \"one\ntwo\")\n;; c\n(b)"]
      (is (= (count src) (count (parsing/strip-noncode src))))
      (is (= (count (re-seq #"\n" src))
             (count (re-seq #"\n" (parsing/strip-noncode src)))))))

  (testing "an escaped quote does not end the string"
    ;; a \" b \" c — seven characters between the quotes, seven spaces out.
    (is (= "(is \"       \")" (parsing/strip-noncode "(is \"a\\\"b\\\"c\")"))))

  (testing "a character literal is blanked, so its delimiter is not code"
    (is (= "(is (=    c))" (parsing/strip-noncode "(is (= \\( c))"))))

  (testing "returns nil for nil input"
    (is (nil? (parsing/strip-noncode nil)))))

(deftest ^:unit delimiter-balance-test
  (testing "a complete form balances at zero"
    (is (= 0 (parsing/delimiter-balance "(deftest a-test (is (= 1 1)))"))))

  (testing "mixed delimiter kinds all count"
    (is (= 0 (parsing/delimiter-balance "(let [{:keys [a]} m] [a {:b 1}])"))))

  (testing "an unclosed form reports the open depth"
    (is (= 2 (parsing/delimiter-balance "(deftest a-test (is"))))

  (testing "a closing delimiter with nothing open is damage, not truncation"
    (is (nil? (parsing/delimiter-balance "(a)) "))))

  (testing "delimiters inside a string are text"
    (is (= 0 (parsing/delimiter-balance "(is (= \"(((\" x))"))))

  (testing "an escaped quote does not end the string"
    (is (= 0 (parsing/delimiter-balance "(is (= \"say \\\"(\\\" now\" x))"))))

  (testing "delimiters inside a line comment are text"
    (is (= 0 (parsing/delimiter-balance "(def x 1) ;; ((( unbalanced\n(def y 2)"))))

  (testing "a character literal delimiter is not structure"
    ;; `\\(` is one token; reading its paren as an opener would report every
    ;; namespace that mentions one as truncated.
    (is (= 0 (parsing/delimiter-balance "(is (= \\( c))")))
    (is (= 0 (parsing/delimiter-balance "(is (= \\; c))")))
    (is (= 0 (parsing/delimiter-balance "(is (= \\\" c))"))))

  (testing "a regex literal is skipped like a string"
    (is (= 0 (parsing/delimiter-balance "(re-find #\"\\(deftest\" s)")))))

(deftest ^:unit truncated?-test
  (testing "a complete namespace is not truncated"
    (is (false? (parsing/truncated? "(ns a-test)\n(deftest b-test (is true))"))))

  (testing "output cut off mid-form is truncated"
    ;; The measured shape: the model stopped inside a let binding.
    (is (true? (parsing/truncated?
                "(deftest a-test\n  (testing \"x\"\n    (let [source \"y\"\n          result (s"))))

  (testing "a stray closing delimiter counts as truncated"
    (is (true? (parsing/truncated? "(a))")))))

(deftest ^:unit require-clause-test
  (testing "the clause is returned whole, nested brackets included"
    (is (= "(:require [clojure.test :refer [deftest is]] [a.b :as b])"
           (parsing/require-clause
            "(ns foo-test\n  (:require [clojure.test :refer [deftest is]] [a.b :as b]))\n(deftest x)"))))

  (testing "the body after the ns form is excluded"
    ;; This is the whole point: a namespace named in the body is not required.
    (is (not (str/includes?
              (parsing/require-clause
               "(ns foo-test (:require [clojure.test]))\n(clojure.set/union a b)")
              "clojure.set"))))

  (testing "a namespace form with no :require clause has none"
    (is (nil? (parsing/require-clause "(ns foo-test)\n(deftest x)"))))

  (testing "an unterminated clause reports none rather than a partial one"
    (is (nil? (parsing/require-clause "(ns foo-test\n  (:require [clojure.test"))))

  (testing "returns nil for nil input"
    (is (nil? (parsing/require-clause nil)))))

(deftest ^:unit missing-standard-requires-test
  (testing "an alias used in the body but absent from the ns form is reported"
    (is (= [{:alias "str" :namespace "clojure.string" :aliased? true}]
           (parsing/missing-standard-requires
            "(ns foo-test (:require [clojure.test :refer [deftest]]))\n(str/join \",\" x)"))))

  (testing "an alias that is already required is not reported"
    (is (empty? (parsing/missing-standard-requires
                 "(ns foo-test (:require [clojure.string :as str]))\n(str/join \",\" x)"))))

  (testing "a fully qualified call still needs the namespace required"
    ;; Measured: `clojure.set/subset?` with no require loads as
    ;; `ClassNotFoundException: clojure.set`. Reported without :as, because
    ;; no alias is used.
    (is (= [{:alias "set" :namespace "clojure.set" :aliased? false}]
           (parsing/missing-standard-requires
            "(ns foo-test (:require [clojure.test :refer [deftest]]))\n(clojure.set/subset? a b)"))))

  (testing "a fully qualified call that is already required is not reported"
    (is (empty? (parsing/missing-standard-requires
                 "(ns foo-test (:require [clojure.set]))\n(clojure.set/subset? a b)"))))

  (testing "the namespace name appearing in the body is not a require"
    ;; The whole-file search this replaced answered yes here, and the file
    ;; then failed to load.
    (is (seq (parsing/missing-standard-requires
              "(ns foo-test (:require [clojure.test :refer [deftest]]))\n(clojure.set/union a b)"))))

  (testing "a qualified keyword is not an alias use"
    (is (empty? (parsing/missing-standard-requires
                 "(ns foo-test (:require [clojure.test]))\n{:str/kind :a ::set/other 1}"))))

  (testing "several missing namespaces come back sorted by alias"
    (is (= [{:alias "io"  :namespace "clojure.java.io" :aliased? true}
            {:alias "set" :namespace "clojure.set"     :aliased? true}
            {:alias "str" :namespace "clojure.string"  :aliased? true}]
           (parsing/missing-standard-requires
            "(ns foo-test (:require [clojure.test :refer [deftest]]))
             (str/join (set/union a b) (io/file \"x\"))"))))

  (testing "an alias quoted inside a string literal is not a use"
    ;; Measured: a generated namespace whose test data contained the text
    ;; "(edn/read d)" had [clojure.edn :as edn] added and never called it,
    ;; which clj-kondo then reports as an unused namespace.
    (is (empty? (parsing/missing-standard-requires
                 "(ns foo-test (:require [clojure.test :refer [deftest]]))
                  (is (= \"(edn/read d) (io/file e)\" sample))"))))

  (testing "an alias mentioned only in a comment is not a use"
    (is (empty? (parsing/missing-standard-requires
                 "(ns foo-test (:require [clojure.test :refer [deftest]]))\n;; uses str/join\n"))))

  (testing "nil input reports nothing rather than throwing"
    (is (empty? (parsing/missing-standard-requires nil)))))

(deftest ^:unit ensure-standard-requires-test
  (testing "the missing require is added to the existing :require clause"
    (let [src    "(ns foo-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(str/join \",\" x)"
          result (parsing/ensure-standard-requires src)]
      (is (str/includes? result "[clojure.string :as str]"))
      (is (str/includes? result "[clojure.test :refer [deftest is]]"))))

  (testing "the repaired namespace form is readable Clojure"
    ;; The point of the repair is that the file loads; a malformed ns form
    ;; would trade one compile error for another.
    (let [src      "(ns foo-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(str/join \",\" x)"
          ns-form  (read-string (parsing/ensure-standard-requires src))
          requires (->> ns-form (filter seq?) (filter #(= :require (first %))) first rest)]
      (is (= 'ns (first ns-form)))
      (is (= '([clojure.string :as str] [clojure.test :refer [deftest is]])
             requires))))

  (testing "a fully qualified use is required without an alias"
    (let [src    "(ns foo-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(clojure.set/subset? a b)"
          result (parsing/ensure-standard-requires src)]
      (is (str/includes? result "[clojure.set]"))
      (is (not (str/includes? result ":as set")))))

  (testing "source that needs nothing is returned unchanged"
    (let [src "(ns foo-test\n  (:require [clojure.string :as str]))\n(str/join \",\" x)"]
      (is (= src (parsing/ensure-standard-requires src)))))

  (testing "a namespace with no :require clause is left alone"
    ;; Nothing to append to, and inventing the clause means guessing.
    (let [src "(ns foo-test)\n(str/join \",\" x)"]
      (is (= src (parsing/ensure-standard-requires src)))))

  (testing "returns nil for nil input"
    (is (nil? (parsing/ensure-standard-requires nil)))))
