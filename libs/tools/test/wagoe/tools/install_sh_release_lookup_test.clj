(ns wagoe.tools.install-sh-release-lookup-test
  "The release-tag lookup from scripts/install.sh, run against a fake curl.

   The nightly matrix went red on 24 and 26 Sep because the lookup used the
   GitHub REST API, whose 60-per-hour unauthenticated budget a shared runner
   IP exhausts (BOU-559)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- lookup-section
  "The installer's lookup, from its section header to the line that uses the
   tag, cut out of scripts/install.sh so the test runs the real code."
  []
  (let [src (slurp "scripts/install.sh")]
    (or (re-find #"(?ms)^# ── wagoe CLI .*?(?=^info \"Installing wagoe CLI @)" src)
        (throw (ex-info "release lookup not found in scripts/install.sh" {})))))

(def ^:private fake-curl
  ;; FAKE_PAGE: redirect | down | weird | notag — the github.com/…/releases/latest
  ;; page; curl follows it and prints the URL it ended on.
  ;; FAKE_API: limited | limited-briefly | ok — the REST API. limited-briefly
  ;; resets in five seconds and answers 200 once asked again.
  "#!/usr/bin/env bash
echo \"$*\" >> \"$HOME/curl.log\"
url=\"${@: -1}\"
case \"$url\" in
  https://api.github.com/*)
    n=$(( $(cat \"$HOME/.api-calls\" 2>/dev/null || echo 0) + 1 )); echo $n > \"$HOME/.api-calls\"
    while [ $# -gt 0 ]; do
      case \"$1\" in -D) hdr=\"$2\"; shift 2 ;; -o) body=\"$2\"; shift 2 ;; *) shift ;; esac
    done
    if [ \"$FAKE_API\" = ok ] || { [ \"$FAKE_API\" = limited-briefly ] && [ $n -gt 1 ]; }; then
      printf 'HTTP/2 200\\r\\n\\r\\n' > \"$hdr\"
      printf '{\"tag_name\": \"1.0.0-from-api\"}' > \"$body\"
      printf 200
    else
      delay=1800; [ \"$FAKE_API\" = limited-briefly ] && delay=5
      reset=$(( $(date +%s) + delay ))
      printf 'HTTP/2 403\\r\\nx-ratelimit-remaining: 0\\r\\nx-ratelimit-reset: %s\\r\\n\\r\\n' $reset > \"$hdr\"
      printf '{\"message\":\"API rate limit exceeded\"}' > \"$body\"
      printf 403
    fi ;;
  https://github.com/wagoebv/wagoe/releases/latest)
    echo x >> \"$HOME/page-calls\"
    case \"$FAKE_PAGE\" in
      redirect) printf 'https://github.com/wagoebv/wagoe/releases/tag/1.0.0-rc-3' ;;
      weird)    printf 'https://github.com/wagoebv/wagoe/releases/tag/1.0;touch%%20x' ;;
      notag)    printf 'https://github.com/wagoebv/wagoe/releases' ;;
      *)        echo 'curl: (7) Failed to connect' >&2; exit 7 ;;
    esac ;;
  *) echo \"unexpected url: $url\" >&2; exit 99 ;;
esac
")

(defn- run-lookup [env]
  (let [home (fs/create-temp-dir)
        bin  (fs/create-dirs (fs/path home "fakebin"))]
    (spit (str (fs/path bin "curl")) fake-curl)
    (fs/set-posix-file-permissions (fs/path bin "curl") "rwxr-xr-x")
    (try
      (let [r (process/shell {:out :string :err :string :continue true
                              :extra-env (merge {"HOME" (str home)
                                                 "PATH" (str bin ":" (System/getenv "PATH"))
                                                 "GITHUB_TOKEN" ""
                                                 "GH_TOKEN" ""}
                                                env)}
                             "bash" "-c"
                             (str "set -euo pipefail\n"
                                  "ok() { echo \"$*\"; }\ninfo() { echo \"$*\"; }\n"
                                  "fail() { echo \"$*\"; exit 1; }\n"
                                  "sleep() { echo \"slept $1\" >> \"$HOME/sleeps\"; }\n"
                                  (lookup-section)
                                  "echo \"TAG=$WAGOE_TAG\"\n"))
            read (fn [f] (let [p (fs/path home f)] (if (fs/exists? p) (slurp (str p)) "")))]
        (assoc r
               :out (str (:out r) (:err r))
               :curl-log (read "curl.log")
               :sleeps (str/split-lines (read "sleeps"))
               :page-calls (count (str/split-lines (read "page-calls")))))
      (finally (fs/delete-tree home)))))

(deftest ^:unit the-tag-resolves-while-the-api-is-rate-limited
  (let [r (run-lookup {"FAKE_PAGE" "redirect" "FAKE_API" "limited"})]
    (is (zero? (:exit r)) (:out r))
    (is (str/includes? (:out r) "TAG=1.0.0-rc-3"))
    (is (not (str/includes? (:curl-log r) "api.github.com"))
        "the REST API is not asked when the releases page answers")))

(deftest ^:unit a-rate-limit-that-resets-shortly-is-waited-out
  (let [r (run-lookup {"FAKE_PAGE" "down" "FAKE_API" "limited-briefly"})]
    (is (zero? (:exit r)) (:out r))
    (is (str/includes? (:out r) "TAG=1.0.0-from-api"))
    (is (some #{"slept 4" "slept 5"} (:sleeps r))
        "waits until the reset, give or take the clock")))

(deftest ^:unit an-exhausted-rate-limit-says-what-to-do
  (let [r (run-lookup {"FAKE_PAGE" "down" "FAKE_API" "limited"})]
    (is (not (zero? (:exit r))))
    (is (str/includes? (:out r) "rate limit"))
    (is (str/includes? (:out r) "GITHUB_TOKEN"))
    (is (re-find #"Retry in \d+ min" (:out r)))
    (is (not (str/includes? (:out r) "internet connection"))
        "a throttled answer is not blamed on the connection")
    (is (every? #(<= (parse-long (re-find #"\d+" %)) 60) (remove str/blank? (:sleeps r)))
        "a limit that resets in half an hour is not waited out")))

(deftest ^:unit the-api-fallback-sends-github-token
  (let [r (run-lookup {"FAKE_PAGE" "down" "FAKE_API" "ok" "GITHUB_TOKEN" "t0ken"})]
    (is (zero? (:exit r)) (:out r))
    (is (str/includes? (:out r) "TAG=1.0.0-from-api"))
    (is (str/includes? (:curl-log r) "Authorization: Bearer t0ken"))))

(deftest ^:unit an-answer-without-a-tag-is-not-retried
  (let [r (run-lookup {"FAKE_PAGE" "notag" "FAKE_API" "ok"})]
    (is (zero? (:exit r)) (:out r))
    (is (= 1 (:page-calls r)) "the same answer would come back")
    (is (str/includes? (:out r) "TAG=1.0.0-from-api"))))

(deftest ^:unit an-unreachable-page-is-retried
  (let [r (run-lookup {"FAKE_PAGE" "down" "FAKE_API" "ok"})]
    (is (= 3 (:page-calls r)))
    (is (str/includes? (:out r) "TAG=1.0.0-from-api"))))

(deftest ^:unit a-tag-with-unexpected-characters-falls-back-to-the-api
  (let [r (run-lookup {"FAKE_PAGE" "weird" "FAKE_API" "ok"})]
    (is (zero? (:exit r)) (:out r))
    (is (str/includes? (:out r) "TAG=1.0.0-from-api"))))
