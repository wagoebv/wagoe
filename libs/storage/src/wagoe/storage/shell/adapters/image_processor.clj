(ns wagoe.storage.shell.adapters.image-processor
  "Java AWT-based image processing implementation.

  Implements IImageProcessor using Java's built-in image processing capabilities.
  No external dependencies required."
  (:require [wagoe.storage.ports :as ports]
            [wagoe.observability.logging.ports :as logging])
(:import [java.awt RenderingHints]
           [java.awt.image BufferedImage]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [javax.imageio ImageIO]))

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

(defn- bytes->buffered-image
  "Convert byte array to BufferedImage."
  [bytes]
  (let [input-stream (ByteArrayInputStream. bytes)]
    (ImageIO/read input-stream)))

(defn- buffered-image->bytes
  "Convert BufferedImage to byte array."
  [^BufferedImage image format]
  (let [output-stream (ByteArrayOutputStream.)]
    (ImageIO/write image format output-stream)
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
                        {:dimensions dimensions
                         :error (.getMessage e)}
                        e)))))

  (create-thumbnail [_ image-bytes size]
    (try
      (let [original (bytes->buffered-image image-bytes)
            orig-width (.getWidth original)
            orig-height (.getHeight original)

            ;; Calculate thumbnail dimensions (maintain aspect ratio)
            ratio (/ (double size) (max orig-width orig-height))
            thumb-width (int (* orig-width ratio))
            thumb-height (int (* orig-height ratio))

            ;; Create thumbnail
            thumbnail (resize-buffered-image original thumb-width thumb-height)

            ;; Detect format
            format (detect-image-format image-bytes nil)

            ;; Convert to bytes
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
                        {:size size
                         :error (.getMessage e)}
                        e)))))

  (get-image-info [_ image-bytes]
    (try
      (let [original (bytes->buffered-image image-bytes)
            width (.getWidth original)
            height (.getHeight original)
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
                        {:error (.getMessage e)}
                        e)))))

  (is-image? [_ bytes _content-type]
    (try
      (let [image (bytes->buffered-image bytes)]
        (boolean image))
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
