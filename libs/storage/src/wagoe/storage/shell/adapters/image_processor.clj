(ns wagoe.storage.shell.adapters.image-processor
  "Java AWT-based image processing implementation.

  Implements IImageProcessor using Java's built-in image processing capabilities.
  No external dependencies required."
  (:require [wagoe.storage.ports :as ports]
            [wagoe.observability.logging.ports :as logging])
  (:import [java.awt RenderingHints]
           [java.awt.image BufferedImage]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [javax.imageio ImageIO ImageReader]))

;; ============================================================================
;; Image Format Detection
;; ============================================================================

(defn- detect-image-format
  "Detect image format from bytes or content-type."
  [bytes content-type]
  (cond
    ;; Try to detect from content-type
    (and content-type (re-find #"jpeg|jpg" content-type)) "jpg"
    (and content-type (re-find #"png" content-type)) "png"
    (and content-type (re-find #"gif" content-type)) "gif"
    (and content-type (re-find #"bmp" content-type)) "bmp"

    ;; Try to detect from magic bytes
    (and (>= (count bytes) 4)
         (= (nth bytes 0) 0xFF)
         (= (nth bytes 1) 0xD8)) "jpg"

    (and (>= (count bytes) 8)
         (= (nth bytes 0) 0x89)
         (= (nth bytes 1) 0x50)
         (= (nth bytes 2) 0x4E)
         (= (nth bytes 3) 0x47)) "png"

    (and (>= (count bytes) 6)
         (= (nth bytes 0) 0x47)
         (= (nth bytes 1) 0x49)
         (= (nth bytes 2) 0x46)) "gif"

    ;; Default to PNG for safety
    :else "png"))

;; ============================================================================
;; Image Manipulation
;; ============================================================================

(def ^:private max-pixels
  "The most pixels an image may have or be resized to: 50 megapixels, 200 MB as ARGB."
  ;; ponytail: one cap for every app; make it :wagoe/storage config if one needs more.
  50000000)

(def ^:private max-decoded-bytes
  "The most an image may take decoded: what `max-pixels` takes as ARGB. A small
   PNG can claim 30000x30000, and a 4 KB TIFF 64 float samples a pixel (BOU-1085)."
  (* 4 max-pixels))

(defn- check-pixels!
  [width height]
  (when (> (* (long width) (long height)) max-pixels)
    (throw (ex-info (str "Image of " width "x" height " exceeds " max-pixels " pixels")
                    {:type :validation-error}))))

(defn- check-decoded-size!
  "Refuses `reader`'s image 0 over `max-pixels`, or when decoding it would take
   over `max-decoded-bytes`: its size times its bits per pixel, from the header.
   Both: drawing a 1-bit image converts it to ARGB first."
  [^ImageReader reader]
  (let [w    (.getWidth reader 0)
        h    (.getHeight reader 0)
        spec ^javax.imageio.ImageTypeSpecifier (first (iterator-seq (.getImageTypes reader 0)))
        bits (if spec (reduce + (.getSampleSize (.getSampleModel spec 1 1))) 32)]
    (check-pixels! w h)
    (when (> (quot (* (long w) (long h) (long bits)) 8) max-decoded-bytes)
      (throw (ex-info (str "Image of " w "x" h " at " bits " bits a pixel exceeds " max-decoded-bytes " bytes")
                      {:type :validation-error})))))

(defn- with-reader
  "`(f reader)` on a reader of `bytes` whose header was read and whose image
   decodes within `max-decoded-bytes`, or nil when no reader knows them (BOU-1085)."
  [bytes f]
  (with-open [in (ImageIO/createImageInputStream (ByteArrayInputStream. bytes))]
    (when-let [^ImageReader reader (some-> (ImageIO/getImageReaders in) iterator-seq first)]
      (try
        (.setInput reader in true true)
        (check-decoded-size! reader)
        (f reader)
        (finally (.dispose reader))))))

(defn- bytes->buffered-image
  "`bytes` decoded, or nil when no reader knows them; never over `max-decoded-bytes`."
  [bytes]
  (with-reader bytes (fn [^ImageReader r] (.read r 0))))

(defn- image-size
  "`[width height]` from `bytes`' header, without decoding; nil when no
   reader knows them."
  [bytes]
  (with-reader bytes (fn [^ImageReader r] [(.getWidth r 0) (.getHeight r 0)])))

(defn- buffered-image->bytes
  "Convert BufferedImage to byte array."
  [^BufferedImage image format]
  (let [output-stream (ByteArrayOutputStream.)]
    ;; false, not an exception, when no writer takes the image (an ARGB JPEG),
    ;; which left an empty file (BOU-1084)
    (when-not (ImageIO/write image ^String format output-stream)
      (throw (ex-info (str "No " format " writer for this image") {:type :storage-error :format format})))
    (.toByteArray output-stream)))

(defn- calculate-dimensions
  "Calculate target dimensions maintaining aspect ratio.

  Returns map with :width and :height."
  [orig-width orig-height {:keys [width height]}]
  (cond
    ;; Both dimensions specified
    (and width height)
    {:width width :height height}

    ;; Only width specified
    width
    (let [ratio (/ (double width) orig-width)]
      {:width width
       :height (int (* orig-height ratio))})

    ;; Only height specified
    height
    (let [ratio (/ (double height) orig-height)]
      {:width (int (* orig-width ratio))
       :height height})

    ;; No dimensions specified
    :else
    {:width orig-width
     :height orig-height}))

(defn- resize-buffered-image
  "Resize a BufferedImage to target dimensions.

  Uses high-quality rendering hints for best results."
  [^BufferedImage original target-width target-height]
  (let [resized (BufferedImage. target-width target-height BufferedImage/TYPE_INT_ARGB)
        graphics (.createGraphics resized)]
    (try
      ;; Set high-quality rendering hints
      (doto graphics
        (.setRenderingHint RenderingHints/KEY_INTERPOLATION
                           RenderingHints/VALUE_INTERPOLATION_BICUBIC)
        (.setRenderingHint RenderingHints/KEY_RENDERING
                           RenderingHints/VALUE_RENDER_QUALITY)
        (.setRenderingHint RenderingHints/KEY_ANTIALIASING
                           RenderingHints/VALUE_ANTIALIAS_ON))

      ;; Draw scaled image
      (.drawImage graphics original 0 0 target-width target-height nil)

      resized

      (finally
        (.dispose graphics)))))

;; ============================================================================
;; Image Processor Implementation
;; ============================================================================

(defrecord JavaImageProcessor [logger]
  ports/IImageProcessor

  (resize-image [_ image-bytes dimensions]
    (try
      (let [original (bytes->buffered-image image-bytes)
            orig-width (.getWidth original)
            orig-height (.getHeight original)

            ;; Calculate target dimensions
            {:keys [width height]} (calculate-dimensions orig-width orig-height dimensions)
            _ (check-pixels! width height)

            ;; Resize image
            resized (resize-buffered-image original width height)

            ;; Detect format
            format (detect-image-format image-bytes nil)

            ;; Convert back to bytes
            result-bytes (buffered-image->bytes resized format)]

        (when logger
          (logging/debug logger "Image resized"
                         {:event ::image-resized
                          :original-dimensions [orig-width orig-height]
                          :target-dimensions [width height]
                          :format format}))

        result-bytes)

      (catch Exception e
        (when logger
          (logging/error logger "Failed to resize image"
                         {:event ::resize-image-failed
                          :error (.getMessage e)}))
        (throw (ex-info "Failed to resize image"
                        {:type :storage-error
                         :dimensions dimensions
                         :error (.getMessage e)}
                        e)))))

  (create-thumbnail [_ image-bytes size]
    (try
      (let [original (bytes->buffered-image image-bytes)
            orig-width (.getWidth original)
            orig-height (.getHeight original)

            ;; Aspect ratio kept, never scaled up: a 1x1 image with size 50000
            ;; allocated a 50000x50000 image (BOU-1084). At least one pixel.
            ratio (min 1.0 (/ (double size) (max orig-width orig-height)))
            thumb-width (max 1 (int (* orig-width ratio)))
            thumb-height (max 1 (int (* orig-height ratio)))

            ;; Create thumbnail
            thumbnail (resize-buffered-image original thumb-width thumb-height)

            ;; Always PNG: it keeps alpha, which the JPEG writer refuses (BOU-1084)
            format "png"
            result-bytes (buffered-image->bytes thumbnail format)]

        (when logger
          (logging/debug logger "Thumbnail created"
                         {:event ::thumbnail-created
                          :original-dimensions [orig-width orig-height]
                          :thumbnail-dimensions [thumb-width thumb-height]
                          :size size
                          :format format}))

        result-bytes)

      (catch Exception e
        (when logger
          (logging/error logger "Failed to create thumbnail"
                         {:event ::create-thumbnail-failed
                          :size size
                          :error (.getMessage e)}))
        (throw (ex-info "Failed to create thumbnail"
                        {:type :storage-error
                         :size size
                         :error (.getMessage e)}
                        e)))))

  (get-image-info [_ image-bytes]
    (try
      (let [[width height] (or (image-size image-bytes)
                               (throw (ex-info "Not an image" {:type :validation-error})))
            format (detect-image-format image-bytes nil)]

        {:width width
         :height height
         :format format
         :size (alength image-bytes)})

      (catch Exception e
        (when logger
          (logging/error logger "Failed to get image info"
                         {:event ::get-image-info-failed
                          :error (.getMessage e)}))
        (throw (ex-info "Failed to get image info"
                        {:type :storage-error
                         :error (.getMessage e)}
                        e)))))

  (is-image? [_ bytes _content-type]
    (try
      (boolean (image-size bytes))
      (catch Exception _
        false))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-image-processor
  "Create a Java AWT-based image processor.

  Options:
  - :logger - Logger instance (optional)"
  [{:keys [logger]}]
  (when logger
    (logging/info logger "Image processor initialized"
                  {:event ::image-processor-initialized}))

  (->JavaImageProcessor logger))
