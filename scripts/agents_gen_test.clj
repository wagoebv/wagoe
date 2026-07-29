(ns agents-gen-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [agents-gen :as gen]))

(def sample-knowledge
  {:fc-is {:layers [{:from :shell :to :core :allowed true}
                    {:from :core :to :shell :allowed false :reason "violates FC/IS"}]
           :rules ["core/ must not import shell/IO/logging/DB"]
           :ports-required true
           :example "(ns {{ns}}.core.product)"}})

(deftest render-fc-is-emits-rows-and-substitutes-ns
  (let [out (gen/render-fc-is (:fc-is sample-knowledge) "myapp")]
    (is (str/includes? out "Shell → Core"))
    (is (str/includes? out "❌"))
    (is (str/includes? out "violates FC/IS"))
    (is (str/includes? out "myapp.core.product"))
    (is (not (str/includes? out "{{ns}}")))))

(deftest render-fc-is-template-keeps-project-ns-token
  (let [out (gen/render-fc-is (:fc-is sample-knowledge) "{{project-ns}}")]
    (is (str/includes? out "{{project-ns}}.core.product"))))

(deftest render-fc-is-uppercases-io-acronym
  (let [out (gen/render-fc-is {:layers [{:from :core :to :io :allowed false :reason "even logging"}]
                               :rules [] :ports-required false :example "(ns x)"} "myapp")]
    (is (str/includes? out "Core → IO"))
    (is (not (str/includes? out "Core → Io")))))

(deftest splice-region-replaces-between-markers
  (let [doc "a\n<!-- gen:x -->\nOLD\n<!-- /gen:x -->\nb\n"]
    (is (= "a\n<!-- gen:x -->\nNEW\n<!-- /gen:x -->\nb\n"
           (gen/splice-region doc "x" "NEW")))))

(deftest splice-region-is-idempotent
  (let [doc "a\n<!-- gen:x -->\nOLD\n<!-- /gen:x -->\nb\n"
        once (gen/splice-region doc "x" "NEW")]
    (is (= once (gen/splice-region once "x" "NEW")))))

(deftest splice-region-throws-on-missing-marker
  (is (thrown? clojure.lang.ExceptionInfo
               (gen/splice-region "no markers here" "x" "NEW"))))

(deftest render-naming-emits-table
  (let [out (gen/render-naming [{:context :clojure :case :kebab :example ":password-hash"}
                                {:context :db :case :snake :example "password_hash"}])]
    (is (str/includes? out "| Location | Convention | Example |"))
    (is (str/includes? out "kebab"))
    (is (str/includes? out ":password-hash"))))

(def sample-pitfalls
  [{:id "P01" :title "kebab mixing" :surfaces #{:framework :downstream}
    :symptom "nil values" :cause "snake key" :fix "use kebab; convert at boundary {{ns}}"}
   {:id "P11" :title "swagger params" :surfaces #{:framework}
    :symptom "invisible params" :cause "no declaration" :fix "declare explicitly"
    :example "(GET \"/x\" {{ns}}.handler)"}])

(deftest render-pitfalls-framework-includes-all
  (let [out (gen/render-pitfalls sample-pitfalls :framework "myapp")]
    (is (str/includes? out "kebab mixing"))
    (is (str/includes? out "swagger params"))
    (is (str/includes? out "myapp"))
    (is (not (str/includes? out "{{ns}}")))))

(deftest render-pitfalls-downstream-filters-to-tagged
  (let [out (gen/render-pitfalls sample-pitfalls :downstream "{{project-ns}}")]
    (is (str/includes? out "kebab mixing"))
    (is (not (str/includes? out "swagger params")))))

(deftest render-pitfalls-renders-optional-example-as-code-block
  (let [with-ex (gen/render-pitfalls sample-pitfalls :framework "myapp")
        without-ex (gen/render-pitfalls [(first sample-pitfalls)] :framework "myapp")]
    (is (str/includes? with-ex "```clojure"))
    (is (not (str/includes? without-ex "```clojure")))))

(def sample-modules
  [{:name "core" :description "Validation, case conversion" :category :core
    :docs-url "https://github.com/wagoebv/wagoe/blob/main/libs/core/AGENTS.md"}
   {:name "payments" :description "PSP abstraction" :category :optional
    :docs-url "https://github.com/wagoebv/wagoe/blob/main/libs/payments/AGENTS.md"}])

(deftest render-modules-emits-aligned-table-with-links
  (let [out (gen/render-modules sample-modules)]
    (is (str/includes? out "| Module"))
    (is (str/includes? out "[core]"))
    (is (str/includes? out "libs/core/AGENTS.md"))
    (is (= out (gen/render-modules sample-modules)))))  ; deterministic

(deftest render-modules-sorts-by-name
  (let [reversed [{:name "payments" :description "PSP" :category :optional
                   :docs-url "https://x/libs/payments/AGENTS.md"}
                  {:name "core" :description "Validation" :category :core
                   :docs-url "https://x/libs/core/AGENTS.md"}]
        out (gen/render-modules reversed)]
    ;; core must render before payments despite reversed input
    (is (< (clojure.string/index-of out "[core]")
           (clojure.string/index-of out "[payments]")))))

(deftest render-modules-escapes-pipe-in-description
  (let [out (gen/render-modules [{:name "x" :description "a | b"
                                  :docs-url "https://h/libs/x/AGENTS.md"}])]
    (is (str/includes? out "a \\| b"))
    (is (not (str/includes? out "| a | b |")))))

(def sample-knowledge-full
  {:fc-is (:fc-is sample-knowledge)
   :naming [{:context :clojure :case :kebab :example ":password-hash"}]
   :pitfalls sample-pitfalls})

(deftest render-target-substitutes-and-splices-known-sections
  (let [doc (str "<!-- gen:naming -->\nx\n<!-- /gen:naming -->\n"
                 "<!-- gen:fc-is -->\ny\n<!-- /gen:fc-is -->\n")
        out (gen/render-target doc sample-knowledge-full sample-modules
                               {:sections [:naming :fc-is] :ns-token "myapp"
                                :pitfall-surface :framework})]
    (is (str/includes? out "| Location | Convention"))
    (is (str/includes? out "Shell → Core"))
    (is (not (str/includes? out "boundary:")))))

(deftest drifted-files-detects-mismatch
  (is (empty? (gen/drifted-files [{:file "A" :current "x" :rendered "x"}])))
  (is (= ["A"] (gen/drifted-files [{:file "A" :current "x" :rendered "y"}]))))

(deftest validate-modules-flags-missing-and-dead-links
  (with-redefs [gen/libs-with-agents (constantly #{"core" "user" "newlib" "tools"})]
    (let [modules [{:name "core" :docs-url "x/libs/core/AGENTS.md"}
                   {:name "user" :docs-url "x/libs/user/AGENTS.md"}
                   {:name "ghost" :docs-url "x/libs/ghost/AGENTS.md"}]
          knowledge {:dev-modules [{:name "tools"}]}   ; allowlist derived from :name
          problems (gen/validate-modules modules knowledge)]
      ;; newlib: has AGENTS.md, not allowlisted, not in catalogue -> flagged
      (is (some #(clojure.string/includes? % "newlib") problems))
      ;; tools: allowlisted via :dev-modules -> NOT flagged
      (is (not-any? #(clojure.string/includes? % "tools") problems))
      ;; ghost: in catalogue but no libs/ghost dir on disk -> dead docs-url flagged
      (is (some #(clojure.string/includes? % "ghost") problems)))))

(deftest validate-modules-allowlist-is-load-bearing
  (with-redefs [gen/libs-with-agents (constantly #{"tools"})]
    (let [modules []]  ; tools not in catalogue
      ;; without tools in :dev-modules -> flagged as missing
      (is (some #(clojure.string/includes? % "tools")
                (gen/validate-modules modules {:dev-modules []})))
      ;; with tools in :dev-modules -> not flagged
      (is (not-any? #(clojure.string/includes? % "tools")
                    (gen/validate-modules modules {:dev-modules [{:name "tools"}]}))))))

(deftest validate-modules-flags-unparseable-docs-url
  (with-redefs [gen/libs-with-agents (constantly #{})]
    (is (some #(clojure.string/includes? % "unparseable")
              (gen/validate-modules [{:name "x" :docs-url "https://example.com/not-a-lib"}]
                                    {:dev-modules []})))))
