(ns wagoe.user.core.password-policy
  "The one reading of a configured password policy.

   Two validators used to read the same policy under two spellings —
   `validate-password-constraint` wanted `:require-numbers?`, which is what
   `config.edn` writes, and `meets-password-policy?` wanted `:require-numbers`,
   which nothing writes. Configuration reached one of them and not the other,
   so registration rejected passwords on rules the config had switched off
   (BOU-388).

   `?`-suffixed is canonical. The unsuffixed spelling is still read, so a
   caller passing a hand-built policy keeps working.")

(def defaults
  "The policy applied to keys the configuration does not set."
  {:min-length             8
   :max-length             255
   :require-uppercase?     false
   :require-lowercase?     false
   :require-numbers?       true
   :require-special-chars? false
   :forbidden-patterns     #{}})

(def ^:private flags
  [:require-uppercase? :require-lowercase? :require-numbers? :require-special-chars?])

(defn- flag
  "The value of `k` in `policy`, under either spelling, else its default."
  [policy k]
  (let [unsuffixed (keyword (subs (name k) 0 (dec (count (name k)))))]
    (cond
      (contains? policy k)          (boolean (get policy k))
      (contains? policy unsuffixed) (boolean (get policy unsuffixed))
      :else                         (get defaults k))))

(defn normalize
  "A policy map with canonical keys and every default filled in.

   Pure."
  [policy]
  (let [policy (or policy {})]
    (reduce (fn [acc k] (assoc acc k (flag policy k)))
            (merge defaults
                   ;; nil is not an answer — an explicit :min-length nil must
                   ;; still get the default rather than break every comparison.
                   (into {} (filter (comp some? val))
                         (select-keys policy [:min-length :max-length :forbidden-patterns])))
            flags)))
