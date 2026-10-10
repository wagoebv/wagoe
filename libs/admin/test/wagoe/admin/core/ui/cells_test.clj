(ns wagoe.admin.core.ui.cells-test
  "List cells by display role (ADR-040)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [wagoe.admin.core.ui.cells :as cells])
  (:import (java.time Instant ZoneId)
           (java.util Locale)))

(def ^:private now (Instant/parse "2026-10-10T10:00:00Z"))

(def ^:private display
  {:zone-id (ZoneId/of "Europe/Amsterdam") :server-zone-id (ZoneId/of "UTC") :locale Locale/ENGLISH})

(def ^:private entity-config
  {:primary-key   :id
   :fields        {:client-id {:type :uuid} :status {:type :enum}}
   :relationships {:belongs-to [{:field :client-id :entity :clients}]}
   :has-many      [{:entity :invoices :foreign-key :client_id :label "Invoices"}]})

(defn- render
  "The cell as an HTML string, `field-config` and the overview as given."
  [role field value & {:keys [field-config overview display href record]
                       :or   {display display}}]
  (str (h/html (cells/cell field (merge {:id "r1" field value} record)
                           {:role role :field-config field-config :entity-config entity-config
                            :display display :overview (merge {:now now} overview) :href href}))))

(defn- t-keys
  "The translation keys a cell's hiccup asks for."
  [role field value & {:keys [field-config]}]
  (->> (cells/cell field {:id "r1" field value}
                   {:role role :field-config field-config :entity-config entity-config
                    :display display :overview {:now now}})
       (tree-seq sequential? seq)
       (keep #(when (and (vector? %) (= :t (first %))) (second %)))
       set))

(deftest ^:unit nil-renders-nothing
  (doseq [role [:title :relation :enum :money :number :percent :date :datetime
                :boolean :identifier :email :url :text]]
    (is (= "" (render role :x nil)) (name role))))

(deftest ^:unit each-role-renders-its-value
  (testing ":title links to the record when it may be opened"
    (is (= "<a href=\"/web/admin/orders/r1\">Acme</a>" (render :title :name "Acme" :href "/web/admin/orders/r1")))
    (is (= "Acme" (render :title :name "Acme"))))

  (testing ":relation shows the target's title, linked, and falls back to the id"
    (is (str/includes? (render :relation :client-id "c1" :overview {:titles {:client-id {"c1" "Veldhuis"}}})
                       "<a href=\"/web/admin/clients/c1\" title=\"c1\">Veldhuis</a>"))
    (is (str/includes? (render :relation :client-id "c1") "c-mono")))

  (testing ":count links to the filtered child list, and 0 is plain"
    (is (= "<a href=\"/web/admin/invoices?client-id=r1\">7</a>"
           (render :count :invoices nil :overview {:counts {:invoices {"r1" 7}}})))
    (is (= "<span class=\"c-zero\">0</span>" (render :count :invoices nil))))

  (testing ":enum shows the option's label in the value's tone"
    (let [fc   {:options [[:paid "Paid"] [:overdue "Overdue"]] :tones {:overdue :danger}}
          html (render :enum :status "overdue" :field-config fc)]
      (is (str/includes? html "Overdue"))
      (is (str/includes? html "tone-danger"))
      (is (str/includes? (render :enum :status "paid" :field-config fc) "tone-neutral")))
    (is (str/includes? (render :enum :status "on_hold") "On hold") "no option: humanized"))

  (testing ":money in the field's currency and the reader's locale"
    (is (= "€1,234.50" (render :money :amount 1234.5M :field-config {:currency "EUR"})))
    (is (str/includes? (render :money :amount "1234.5" :field-config {:currency "EUR"}
                               :display (assoc display :locale (Locale/forLanguageTag "nl-NL")))
                       "1.234,50"))
    (is (= "not-a-number" (render :money :amount "not-a-number"))))

  (testing ":number and :percent"
    (is (= "1,234.5" (render :number :n 1234.5M)))
    (is (= "21 %" (render :percent :vat-pct 21))))

  (testing ":boolean is a check mark, with text for screen readers"
    (is (str/includes? (render :boolean :paid true) "c-check"))
    (is (not (str/includes? (render :boolean :paid false) "c-check"))))

  (testing ":email and :url"
    (is (= "<a href=\"mailto:a@b.nl\">a@b.nl</a>" (render :email :email "a@b.nl")))
    (is (str/includes? (render :url :website "https://wagoe.org/docs") ">wagoe.org</a>")))

  (testing ":text keeps the full value in title"
    (is (= "<span title=\"A long note\">A long note</span>" (render :text :notes "A long note")))))

(deftest ^:unit a-url-that-is-not-http-is-never-a-link
  (let [html (render :url :website "javascript:alert(1)")]
    (is (not (str/includes? html "href")))
    (is (str/includes? html "javascript:alert(1)"))))

(deftest ^:unit dates-are-relative-with-the-absolute-value-in-title
  (testing ":date counts calendar days in the display zone"
    (is (contains? (t-keys :date :due "2026-10-10") :admin/relative-today))
    (is (contains? (t-keys :date :due "2026-10-15") :admin/relative-in-days))
    (is (str/includes? (render :date :due "2026-10-07") "title=\"2026-10-07\""))
    (is (str/includes? (render :date :due "2026-01-01") ">2026-01-01<") "beyond the horizon: the date"))

  (testing ":datetime"
    (is (contains? (t-keys :datetime :at "2026-10-10T09:59:30Z") :admin/relative-just-now))
    (is (contains? (t-keys :datetime :at "2026-10-10T09:15:00Z") :admin/relative-minutes-ago))
    (is (contains? (t-keys :datetime :at "2026-10-10T13:00:00Z") :admin/relative-in-hours))
    (is (contains? (t-keys :datetime :at "2026-10-08T09:00:00Z") :admin/relative-days-ago)))

  (testing "urgency tones a due date"
    (let [fc {:urgency {:warn-days 7}}]
      (is (str/includes? (render :date :due "2026-10-01" :field-config fc) "tone-danger"))
      (is (str/includes? (render :date :due "2026-10-14" :field-config fc) "tone-warning"))
      (is (not (str/includes? (render :date :due "2026-11-30" :field-config fc) "tone-")))
      (is (not (str/includes? (render :date :due "2026-10-01") "tone-")) "only with :urgency")
      (is (str/includes? (render :date :due "2026-10-01" :field-config {:urgency true}) "tone-danger")
          "true means a week's warning")))

  (testing "urgency ends when the record holds an :until value"
    (let [fc {:urgency {:until {:status [:paid]}}}]
      (is (not (str/includes? (render :date :due "2026-10-01" :field-config fc :record {:status "paid"}) "tone-")))
      (is (str/includes? (render :date :due "2026-10-01" :field-config fc :record {:status "sent"}) "tone-danger")))))

(deftest ^:unit page-totals-sum-what-is-a-number
  (is (= 30.50M (cells/page-total [{:a 10} {:a "20.50"} {:a nil} {:a "x"}] :a)))
  (is (nil? (cells/page-total [{:a nil}] :a))))
