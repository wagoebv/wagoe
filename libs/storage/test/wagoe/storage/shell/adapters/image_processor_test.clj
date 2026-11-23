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

(deftest ^:integration ^:security a-thumbnail-is-never-larger-than-its-image
  ;; BOU-1084: size 50000 on a 1x1 image allocated a 50000x50000 one.
  (let [info #(ports/get-image-info (processor) (ports/create-thumbnail (processor) (make-png-bytes 2 1) %))]
    (is (= [2 1] ((juxt :width :height) (info 50000))))
    (is (= [1 1] ((juxt :width :height) (info 1))) "and at least one pixel"))
  (testing "a JPEG's thumbnail is a PNG, not an empty file"
    (let [jpg   (let [out (java.io.ByteArrayOutputStream.)]
                  (javax.imageio.ImageIO/write (java.awt.image.BufferedImage. 40 20 java.awt.image.BufferedImage/TYPE_INT_RGB)
                                               "jpg" out)
                  (.toByteArray out))
          thumb (ports/create-thumbnail (processor) jpg 10)]
      (is (pos? (alength ^bytes thumb)))
      (is (= "png" (:format (ports/get-image-info (processor) thumb)))))))

(defn- claiming
  "`png` with its header claiming `width` x `height`: IHDR's fields, bytes 16-23."
  [^bytes png width height]
  (let [b (aclone png)
        bb (java.nio.ByteBuffer/wrap b)]
    (.putInt bb 16 (int width))
    (.putInt bb 20 (int height))
    b))

(defn- float-tiff
  "A 4 KB little-endian TIFF claiming `w` x `h` with `spp` 32-bit float samples
   a pixel, and no pixel data."
  [w h spp]
  (let [b       (.order (java.nio.ByteBuffer/allocate 4096) java.nio.ByteOrder/LITTLE_ENDIAN)
        bps-at  2000
        entries [[256 4 1 w] [257 4 1 h] [258 3 spp bps-at] [259 3 1 1] [262 3 1 2]
                 [273 4 1 3000] [277 3 1 spp] [278 4 1 h] [279 4 1 16] [338 3 1 0] [339 3 1 3]]]
    (-> b (.put (byte 73)) (.put (byte 73)) (.putShort 42) (.putInt 8) (.putShort (count entries)))
    (doseq [[tag type n v] entries]
      (.putShort b (short tag)) (.putShort b (short type)) (.putInt b (int n))
      (if (and (= 3 type) (= 1 n)) (do (.putShort b (short v)) (.putShort b 0)) (.putInt b (int v))))
    (.putInt b 0)
    (.position b bps-at)
    (dotimes [_ spp] (.putShort b 32))
    (.array b)))

(deftest ^:integration ^:security an-image-claiming-too-many-pixels-is-not-decoded
  ;; BOU-1085: a small PNG claiming 30000x30000 allocated about 3.6 GB. Decoding
  ;; it here would fail with an OutOfMemoryError, not this refusal.
  (let [bomb (claiming (make-png-bytes 8 8) 30000 30000)]
    (doseq [[op f] {"create-thumbnail" #(ports/create-thumbnail (processor) bomb 80)
                    "resize-image"     #(ports/resize-image (processor) bomb {:width 10})
                    "get-image-info"   #(ports/get-image-info (processor) bomb)}]
      (is (re-find #"exceeds" (str (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))
          op))
    (is (false? (ports/is-image? (processor) bomb nil))))
  (testing "nor one whose samples make it large: 4 MP at 64 floats a pixel is 1 GB"
    (let [bomb (float-tiff 2000 2000 64)]
      (is (re-find #"exceeds" (str (try (ports/create-thumbnail (processor) bomb 80) nil
                                        (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))))
  (testing "nor a 1-bit one, which drawing turns into ARGB: a 35-byte GIF claiming 14000x14000"
    (let [le   (fn [n] [(bit-and n 0xff) (bit-shift-right n 8)])
          gif  (byte-array (map unchecked-byte
                                (concat (map int "GIF89a") (le 14000) (le 14000) [0x80 0 0] [0 0 0 255 255 255]
                                        [0x2c 0 0 0 0] (le 14000) (le 14000) [0 2 0] [0x3b])))]
      (is (re-find #"exceeds" (str (try (ports/create-thumbnail (processor) gif 80) nil
                                        (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))))
  (testing "nor is one produced: a resize to 30000x30000"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ports/resize-image (processor) (make-png-bytes 8 8) {:width 30000 :height 30000}))))
  (testing "a normal image still works"
    (is (= [8 8] ((juxt :width :height) (ports/get-image-info (processor) (make-png-bytes 8 8)))))))

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
