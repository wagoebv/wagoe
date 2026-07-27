(ns wagoe.storage.core.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.storage.core.validation :as sut]))

(deftest ^:unit get-file-extension-test
  (testing "extracts file extension correctly"
    (is (= "jpg" (sut/get-file-extension "photo.jpg")))
    (is (= "png" (sut/get-file-extension "image.PNG")))
    (is (= "pdf" (sut/get-file-extension "document.pdf"))))

  (testing "handles multiple dots in filename"
    (is (= "txt" (sut/get-file-extension "my.file.name.txt"))))

  (testing "returns nil for files without extension"
    (is (nil? (sut/get-file-extension "README")))
    (is (nil? (sut/get-file-extension nil)))))

(deftest ^:unit mime-type-from-extension-test
  (testing "returns correct MIME type for common extensions"
    (is (= "image/jpeg" (sut/mime-type-from-extension "photo.jpg")))
    (is (= "image/png" (sut/mime-type-from-extension "image.png")))
    (is (= "application/pdf" (sut/mime-type-from-extension "doc.pdf"))))

  (testing "returns nil for unknown extensions"
    (is (nil? (sut/mime-type-from-extension "file.xyz")))))

(deftest ^:unit validate-file-size-test
  (testing "accepts files within size limit"
    (let [result (sut/validate-file-size 1024 (* 10 1024 1024))]
      (is (:valid? result))))

  (testing "rejects files exceeding size limit"
    (let [result (sut/validate-file-size (* 20 1024 1024) (* 10 1024 1024))]
      (is (not (:valid? result)))
      (is (= :file-too-large (-> result :errors first :code)))))

  (testing "uses default max size when not specified"
    (let [result (sut/validate-file-size 1024 nil)]
      (is (:valid? result)))))

(deftest ^:unit validate-content-type-test
  (testing "accepts allowed content types"
    (let [result (sut/validate-content-type "image/jpeg" ["image/jpeg" "image/png"])]
      (is (:valid? result))))

  (testing "rejects disallowed content types"
    (let [result (sut/validate-content-type "application/pdf" ["image/jpeg" "image/png"])]
      (is (not (:valid? result)))
      (is (= :invalid-content-type (-> result :errors first :code)))))

  (testing "accepts any type when allowed-types is nil or empty"
    (is (:valid? (sut/validate-content-type "application/pdf" nil)))
    (is (:valid? (sut/validate-content-type "application/pdf" [])))))

(deftest ^:unit validate-extension-test
  (testing "accepts allowed extensions"
    (let [result (sut/validate-extension "photo.jpg" ["jpg" "png"])]
      (is (:valid? result))))

  (testing "rejects disallowed extensions"
    (let [result (sut/validate-extension "document.pdf" ["jpg" "png"])]
      (is (not (:valid? result)))
      (is (= :invalid-extension (-> result :errors first :code)))))

  (testing "accepts any extension when allowed-extensions is nil or empty"
    (is (:valid? (sut/validate-extension "file.xyz" nil)))
    (is (:valid? (sut/validate-extension "file.xyz" [])))))

(deftest ^:unit is-image-mime-type?-test
  (testing "recognizes image MIME types"
    (is (sut/is-image-mime-type? "image/jpeg"))
    (is (sut/is-image-mime-type? "image/png"))
    (is (sut/is-image-mime-type? "image/gif")))

  (testing "rejects non-image MIME types"
    (is (not (sut/is-image-mime-type? "application/pdf")))
    (is (not (sut/is-image-mime-type? "text/plain")))))

(deftest ^:unit validate-file-test
  (let [valid-file-data {:bytes (.getBytes "test content")
                         :content-type "image/jpeg"
                         :size 12}
        metadata {:filename "photo.jpg"}]

    (testing "validates correct file successfully"
      (let [result (sut/validate-file valid-file-data metadata {})]
        (is (:valid? result))
        (is (= "photo.jpg" (-> result :data :filename)))
        (is (= "image/jpeg" (-> result :data :content-type)))))

    (testing "fails when file is too large"
      (let [result (sut/validate-file valid-file-data metadata {:max-size 5})]
        (is (not (:valid? result)))
        (is (some #(= :file-too-large (:code %)) (:errors result)))))

    (testing "fails when content type not allowed"
      (let [result (sut/validate-file valid-file-data metadata {:allowed-types ["image/png"]})]
        (is (not (:valid? result)))
        (is (some #(= :invalid-content-type (:code %)) (:errors result)))))

    (testing "fails when extension not allowed"
      (let [result (sut/validate-file valid-file-data metadata {:allowed-extensions ["png"]})]
        (is (not (:valid? result)))
        (is (some #(= :invalid-extension (:code %)) (:errors result)))))

    (testing "accumulates multiple errors"
      (let [result (sut/validate-file valid-file-data metadata
                                      {:max-size 5
                                       :allowed-types ["image/png"]
                                       :allowed-extensions ["png"]})]
        (is (not (:valid? result)))
        (is (= 3 (count (:errors result))))))))

(deftest ^:unit sanitize-filename-test
  (testing "removes path separators"
    (is (= "file.txt" (sut/sanitize-filename "../file.txt")))
    (is (= "file.txt" (sut/sanitize-filename "..\\file.txt")))
    (is (= "pathtofile.txt" (sut/sanitize-filename "path/to/file.txt"))))

  (testing "removes dangerous characters"
    (is (= "filename.txt" (sut/sanitize-filename "file<name>.txt")))
    (is (= "filename.txt" (sut/sanitize-filename "file|name.txt"))))

  (testing "preserves extension"
    (is (= "myfile.jpg" (sut/sanitize-filename "my-file.jpg"))))

  (testing "truncates long filenames while preserving extension"
    (let [long-name (apply str (repeat 300 "a"))
          long-filename (str long-name ".txt")
          result (sut/sanitize-filename long-filename)]
      (is (< (count result) 256))
      (is (re-find #"\.txt$" result)))))

(deftest ^:unit generate-unique-filename-test
  (testing "generates unique filename with explicit suffix"
    (let [result (sut/generate-unique-filename* "photo.jpg" "1712745600000-abc12345")]
      (is (= "1712745600000-abc12345.jpg" result))))

  (testing "preserves extension"
    (let [result (sut/generate-unique-filename* "document.pdf" "1712745600000-abc12345")]
      (is (= "1712745600000-abc12345.pdf" result))))

  (testing "handles files without extension"
    (let [result (sut/generate-unique-filename* "README" "1712745600000-abc12345")]
      (is (= "1712745600000-abc12345" result))
      (is (not (re-find #"\." result))))))
