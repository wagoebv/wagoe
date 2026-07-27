(ns wagoe.storage.shell.adapters.image-processor-test
  "Unit tests for the Java AWT image processor adapter.

   Uses programmatically generated BufferedImage bytes so no external services
   or fixture files are required."
  {:kaocha.testable/meta {:integration true :storage true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.storage.shell.adapters.image-processor :as img-proc]
            [wagoe.storage.ports :as ports])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [javax.imageio ImageIO]))

;; Ensure AWT tests run in CI/headless environments.
(System/setProperty "java.awt.headless" "true")

;; =============================================================================
;; Helpers — create minimal PNG bytes in-process
;; =============================================================================

(defn- make-png-bytes
  "Create a simple PNG byte array of given dimensions using Java AWT."
  ([] (make-png-bytes 200 150))
  ([width height]
   (let [image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
         g (.createGraphics image)]
     (try
       (.setColor g java.awt.Color/BLUE)
       (.fillRect g 0 0 width height)
       (.setColor g java.awt.Color/RED)
       (.fillRect g 20 20 60 60)
       (finally
         (.dispose g)))
     (let [out (ByteArrayOutputStream.)]
       (ImageIO/write image "png" out)
       (.toByteArray out)))))

(defn- processor
  "Create a JavaImageProcessor instance with no logger."
  []
  (img-proc/create-image-processor {}))

;; =============================================================================
;; get-image-info
;; =============================================================================

(deftest ^:integration get-image-info-test
  (testing "returns correct width and height"
    (let [png-bytes (make-png-bytes 200 150)
          info (ports/get-image-info (processor) png-bytes)]
      (is (= 200 (:width info)))
      (is (= 150 (:height info)))))

  (testing "returns format"
    (let [png-bytes (make-png-bytes)
          info (ports/get-image-info (processor) png-bytes)]
      (is (string? (:format info)))))

  (testing "returns byte size"
    (let [png-bytes (make-png-bytes)
          info (ports/get-image-info (processor) png-bytes)]
      (is (= (count png-bytes) (:size info))))))

;; =============================================================================
;; is-image?
;; =============================================================================

(deftest ^:integration is-image?-test
  (testing "returns true for valid PNG bytes"
    (let [png-bytes (make-png-bytes)]
      (is (true? (ports/is-image? (processor) png-bytes "image/png")))))

  (testing "returns false for non-image bytes"
    (let [text-bytes (.getBytes "not an image")]
      (is (false? (ports/is-image? (processor) text-bytes "text/plain"))))))

;; =============================================================================
;; resize-image
;; =============================================================================

(deftest ^:integration resize-image-test
  (testing "resizes to explicit width and height"
    (let [png-bytes (make-png-bytes 200 150)
          resized (ports/resize-image (processor) png-bytes {:width 100 :height 80})]
      (is (bytes? resized))
      (let [info (ports/get-image-info (processor) resized)]
        (is (= 100 (:width info)))
        (is (= 80 (:height info))))))

  (testing "resizes maintaining aspect ratio from width only"
    (let [png-bytes (make-png-bytes 200 100)   ; 2:1 ratio
          resized (ports/resize-image (processor) png-bytes {:width 100})
          info (ports/get-image-info (processor) resized)]
      (is (= 100 (:width info)))
      (is (= 50 (:height info)))))

  (testing "resizes maintaining aspect ratio from height only"
    (let [png-bytes (make-png-bytes 200 100)   ; 2:1 ratio
          resized (ports/resize-image (processor) png-bytes {:height 50})
          info (ports/get-image-info (processor) resized)]
      (is (= 100 (:width info)))
      (is (= 50 (:height info)))))

  (testing "returns original dimensions when no target specified"
    (let [png-bytes (make-png-bytes 200 150)
          resized (ports/resize-image (processor) png-bytes {})
          info (ports/get-image-info (processor) resized)]
      (is (= 200 (:width info)))
      (is (= 150 (:height info))))))

;; =============================================================================
;; create-thumbnail
;; =============================================================================

(deftest ^:integration create-thumbnail-test
  (testing "creates thumbnail fitting within size box"
    (let [png-bytes (make-png-bytes 400 200)  ; wider than tall
          thumb (ports/create-thumbnail (processor) png-bytes 100)]
      (is (bytes? thumb))
      (let [info (ports/get-image-info (processor) thumb)]
        ;; Longest dimension should be <= 100
        (is (<= (max (:width info) (:height info)) 100)))))

  (testing "creates thumbnail for square image"
    (let [png-bytes (make-png-bytes 200 200)
          thumb (ports/create-thumbnail (processor) png-bytes 50)
          info (ports/get-image-info (processor) thumb)]
      (is (= 50 (:width info)))
      (is (= 50 (:height info)))))

  (testing "thumbnail is smaller than original"
    (let [original (make-png-bytes 300 200)
          thumb (ports/create-thumbnail (processor) original 80)]
      (is (< (count thumb) (count original)) "thumbnail should be smaller"))))

(deftest ^:integration invalid-image-bytes-test
  (testing "resize-image throws on invalid bytes"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ports/resize-image (processor) (.getBytes "not-image") {:width 100}))))

  (testing "create-thumbnail throws on invalid bytes"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ports/create-thumbnail (processor) (.getBytes "not-image") 80))))

  (testing "get-image-info throws on invalid bytes"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ports/get-image-info (processor) (.getBytes "not-image"))))))
