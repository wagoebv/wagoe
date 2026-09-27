(ns wagoe.tools.config-edn-test
  "Editing config.edn as text.

   It cannot be read as EDN: Aero tags (`#env`, `#profile`, `#or`) are reader
   macros no plain reader knows, so a read-modify-write round trip fails on the
   first `#env`. Brace-balanced text insertion is what quickstart already did;
   this is that code, in one place, so `integrate` does not grow a second copy
   that drifts (BOU-310)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.config-edn :as sut]))

(def ^:private sample
  ";; A config\n{:profile #profile {:default :dev}\n\n :active\n {:wagoe/settings\n  {:name \"app\"}\n\n  :wagoe/h2\n  {:db \"mem:app\"}}\n\n :inactive\n {:wagoe/cache\n  {:provider :redis}}}\n")

(deftest ^:unit a-brace-in-a-string-or-comment-does-not-move-the-insertion
  ;; A brace counter without lexer state is not a brace counter, and the damage
  ;; is silent: the result still balances and still parses, it is just in the
  ;; wrong section. The shipped dev config already contains `#{}` and a regex
  ;; `{L}`, and these files are hand-edited.
  (letfn [(lands-in-active? [text]
            (let [out (sut/insert-before-active-close text "\n  :wagoe/new {}\n")
                  cut (str/index-of out ":inactive")]
              (str/includes? (subs out 0 cut) ":wagoe/new")))]

    (testing "a brace inside a string"
      (is (lands-in-active?
           "{:active\n {:wagoe/h2 {:url \"jdbc:h2;INIT={\"}}\n\n :inactive\n {:x 1}}\n")))

    (testing "a brace inside a comment"
      (is (lands-in-active?
           "{:active\n {:a 1}  ;; example: {:foo 1\n\n :inactive\n {:x 1}}\n")))

    (testing "an escaped quote does not open a string"
      (is (lands-in-active?
           "{:active\n {:a \"say \\\" here\"}\n\n :inactive\n {:x 1}}\n")))))

(deftest ^:unit a-key-is-not-a-substring-of-another-key
  ;; Against the shipped dev config, `:wagoe/payment` matched
  ;; `:wagoe/payment-provider` — and route/router, setting/settings,
  ;; metric/metrics, log/logging. `bb scaffold integrate payment` reported
  ;; "already in" for both config files, wrote nothing, and the module never
  ;; booted.
  (let [text "{:active\n {:wagoe/payment-provider {:p :mock}\n  :wagoe/settings {:name \"x\"}}}\n"]
    (testing "a prefix of an existing key is absent"
      (is (= :absent (sut/key-status text ":wagoe/payment")))
      (is (= :absent (sut/key-status text ":wagoe/setting"))))

    (testing "the key itself is present"
      (is (= :already-present (sut/key-status text ":wagoe/payment-provider")))
      (is (= :already-present (sut/key-status text ":wagoe/settings"))))))

(deftest ^:unit only-the-active-section-counts-as-configured
  ;; `:wagoe/h2` lives under :inactive in the shipped config and `:wagoe/cache`
  ;; only in a comment. Reporting either as present writes nothing to :active —
  ;; configured and switched off, which is what this namespace exists to avoid.
  (let [text (str "{:active\n {:wagoe/settings {:name \"x\"}\n"
                  "  ;; :wagoe/cache to enable Redis\n }\n\n"
                  " :inactive\n {:wagoe/h2 {:db \"mem\"}}}\n")]
    (is (= :absent (sut/key-status text ":wagoe/h2")) "under :inactive")
    (is (= :absent (sut/key-status text ":wagoe/cache")) "in a comment")
    (is (= :already-present (sut/key-status text ":wagoe/settings")))))

(deftest ^:unit finds-the-closing-brace-of-active-not-inactive
  (testing "the index is the brace that closes :active"
    (let [idx (sut/active-closing-brace sample)]
      (is (some? idx))
      (is (= \} (nth sample idx)))
      ;; Everything after it must still contain :inactive — otherwise we found
      ;; the wrong brace and would inject into the wrong section.
      (is (str/includes? (subs sample idx) ":inactive"))))

  (testing ":active is found even when :inactive comes first"
    ;; Note `:inactive` does not contain `:active` — the colon is in the wrong
    ;; place — so this needs no special guard, and an earlier version of this
    ;; test asserted one that could never fire. What does need excluding is
    ;; `::active`.
    (let [inactive-first ";; x\n{:inactive\n {:a 1}\n\n :active\n {:b 2}}\n"
          idx (sut/active-closing-brace inactive-first)]
      (is (some? idx))
      (is (str/includes? (subs inactive-first 0 idx) ":b"))))

  (testing "a commented-out :active is not the section"
    (let [commented ";; :active is where modules go\n{:active\n {:a 1}}\n"]
      (is (some? (sut/active-closing-brace commented)))))

  (testing "no :active section at all is nil, not a guess"
    (is (nil? (sut/active-closing-brace "{:profile :dev}\n")))))

(deftest ^:unit injecting-a-key-puts-it-inside-active
  (let [out (sut/insert-before-active-close sample "\n  :wagoe/tasks\n  {:enabled? true}\n")]
    (testing "the key lands inside the :active map"
      (let [active-part (subs out 0 (str/index-of out ":inactive"))]
        (is (str/includes? active-part ":wagoe/tasks"))))

    (testing "and the braces still balance"
      (is (= (count (filter #{\{} out)) (count (filter #{\}} out)))))

    (testing "the rest of the file is untouched"
      (is (str/includes? out ":wagoe/h2"))
      (is (str/includes? out ":inactive")))))

(deftest ^:unit a-key-that-is-already-there-is-not-added-twice
  ;; Running integrate twice must be a no-op, not a duplicate key — EDN keeps
  ;; the last one, so a second run would silently discard hand edits to the
  ;; first.
  (let [once  (sut/insert-before-active-close sample "\n  :wagoe/tasks\n  {:enabled? true}\n")]
    (is (= :already-present (sut/key-status once ":wagoe/tasks")))
    (is (= :absent (sut/key-status sample ":wagoe/tasks")))))

(deftest ^:unit injection-changes-no-byte-outside-the-insertion
  ;; The old post-insert "safety net" shelled out to clj-paren-repair, which
  ;; re-indents the whole file — so integrate's bytes depended on which repair
  ;; tool was on the PATH, and every run reshuffled a reviewed diff (BOU-359).
  ;; The contract now: every pre-existing line survives byte-identically.
  (let [text    (str "{;; hand-written comment, odd   indentation\n"
                     " :active\n"
                     " {  :wagoe/settings\n"
                     "    {:name \"x\"}\n"
                     "}\n"
                     " :inactive\n"
                     " {}}\n")
        out     (sut/insert-before-active-close text "\n  :wagoe/tasks {}\n")
        ;; The snippet's own trailing newline leaves one blank line at the
        ;; insertion point; the fixture has no blank lines, so dropping blanks
        ;; strips only what the insertion added.
        removed (remove #(or (str/blank? %) (str/includes? % "wagoe/tasks"))
                        (str/split-lines out))]
    (is (= (str/split-lines text) removed)
        "only the inserted lines may differ — nothing is reformatted")))

(deftest ^:unit injection-refuses-to-write-an-unbalanced-result
  ;; Unreachable for a balanced snippet; this is what stands where the
  ;; reformatter used to. Exercised through the private check via inject-key!.
  (let [dir  (.toFile (java.nio.file.Files/createTempDirectory
                       "wagoe-config-edn" (make-array java.nio.file.attribute.FileAttribute 0)))
        path (str dir "/config.edn")]
    (try
      (spit path "{:active\n {}\n :inactive {}}\n")
      (is (= :insert-would-unbalance
             (sut/inject-key! path ":wagoe/broken" "\n  :wagoe/broken {\n" {})))
      (is (str/includes? (slurp path) ":active")
          "the file is untouched when the insertion is refused")
      (is (not (str/includes? (slurp path) ":wagoe/broken")))
      (finally (doseq [f (reverse (file-seq dir))] (.delete f))))))

(deftest ^:unit entries-read-each-value-to-its-end
  (let [text (str "{:active\n {:a/str \"x } y\"\n  ;; about b\n  :b/tag #or [#env B \"{\"]\n"
                  "  :c/set #{:x} :d/char \\} :e/num 3\n  :f/map {:re #\"{L}\"}}}")
        es   (sut/entries text ":active")]
    (is (= [":a/str" ":b/tag" ":c/set" ":d/char" ":e/num" ":f/map"] (map :key es)))
    (is (= [":b/tag #or [#env B \"{\"]" ":e/num 3"]
           (for [e es :when (#{":b/tag" ":e/num"} (:key e))]
             (subs text (:start e) (:end e)))))
    (is (str/starts-with? (subs text (:from (second es))) "  ;; about b")
        "the comment above a key belongs to it")
    (is (nil? (sut/entries "{:active {:a/key}}" ":active")) "a key without a value")))

(deftest ^:unit entries-read-what-edn-allows-in-a-value
  (testing "each reads as one value, so the next key is not swallowed"
    (doseq [[label v] [["#_ discard"      "#_ :gone {:x 1}"]
                       ["##Inf"           "##Inf"]
                       ["##NaN in a vec"  "[##NaN 1]"]
                       ["^meta"           "^{:tag x} {:y 2}"]
                       ["^:kw meta"       "^:private sym"]
                       ["\\newline"       "\\newline"]
                       ["\\space"         "\\space"]]]
      (let [text (str "{:active {:a/k " v " :b/k 1}}")]
        (is (= [":a/k" ":b/k"] (map :key (sut/entries text ":active"))) label))))
  (testing "a discarded key/value pair is not an entry"
    (is (= [":b/k"] (map :key (sut/entries "{:active {#_ :a/k #_ 1 :b/k 1}}" ":active"))))))

(deftest ^:unit section-is-a-root-key-matched-exactly
  (let [nested "{:wagoe/flags {:active true :opts {:x 1}}\n :active {:a/k 1}}"]
    (is (= [":a/k"] (map :key (sut/entries nested ":active")))
        "a nested :active is not the section"))
  (is (= [":a/k"] (map :key (sut/entries "{:active-profiles {:z 1} :active {:a/k 1}}" ":active"))))
  (let [inner "{:active {:my/feature {:inactive {:x 1}}} :inactive {:b/k 2}}"]
    (is (= [":b/k"] (map :key (sut/entries inner ":inactive")))
        ":inactive inside :active is not the section"))
  (is (nil? (sut/section "{:active {:a 1}} ;; end }" ":inactive")))
  (is (= [0 15] (sut/root-map "{:active {:a 1}} ;; end }"))
      "a brace in a trailing comment is not the root's"))

(deftest ^:unit an-active-that-is-not-a-literal-map-has-no-section
  (doseq [v ["#include \"active.edn\"" "#profile {:dev {:a 1}}" "#merge [{:a 1} {:b 2}]"]]
    (is (nil? (sut/entries (str "{:active " v "}") ":active")) v)))
