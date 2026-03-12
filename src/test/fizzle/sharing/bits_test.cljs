(ns fizzle.sharing.bits-test
  "Tests for BitWriter/BitReader primitives and base64url codec.

   MSB-first bit order. Auto-growing buffer. base64url with no padding.
   Tests cover bit boundaries, byte boundaries, empty input, and roundtrips."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.string :as str]
    [fizzle.sharing.bits :as bits]))


;; === A. BitWriter construction ===

(deftest bitwriter-starts-empty-test
  (testing "fresh BitWriter has zero bits written"
    (let [w (bits/writer)]
      (is (= 0 (bits/bits-written w))
          "new writer has 0 bits"))))


;; === B. write-bits and bit-count ===

(deftest write-bits-accumulates-count-test
  (testing "bit-count grows by n after each write-bits call"
    (let [w  (bits/writer)
          w1 (bits/write-bits w 0 1)
          w2 (bits/write-bits w1 0 4)
          w3 (bits/write-bits w2 0 7)]
      (is (= 1  (bits/bits-written w1)))
      (is (= 5  (bits/bits-written w2)))
      (is (= 12 (bits/bits-written w3))))))


(deftest write-bits-single-bit-test
  (testing "writing a single 1-bit then reading it back yields 1"
    (let [w (-> (bits/writer) (bits/write-bits 1 1))
          r (bits/reader (bits/finish w))]
      (is (= 1 (bits/read-bits r 1))))))


(deftest write-bits-single-zero-bit-test
  (testing "writing a single 0-bit then reading it back yields 0"
    (let [w (-> (bits/writer) (bits/write-bits 0 1))
          r (bits/reader (bits/finish w))]
      (is (= 0 (bits/read-bits r 1))))))


;; === C. Bit boundaries ===

(deftest write-bits-6bit-max-test
  (testing "6-bit max value (63) roundtrips correctly"
    (let [w (-> (bits/writer) (bits/write-bits 63 6))
          r (bits/reader (bits/finish w))]
      (is (= 63 (bits/read-bits r 6))))))


(deftest write-bits-6bit-zero-test
  (testing "6-bit zero roundtrips correctly"
    (let [w (-> (bits/writer) (bits/write-bits 0 6))
          r (bits/reader (bits/finish w))]
      (is (= 0 (bits/read-bits r 6))))))


(deftest write-bits-8bit-max-test
  (testing "8-bit max value (255) roundtrips correctly"
    (let [w (-> (bits/writer) (bits/write-bits 255 8))
          r (bits/reader (bits/finish w))]
      (is (= 255 (bits/read-bits r 8))))))


(deftest write-bits-8bit-zero-test
  (testing "8-bit zero roundtrips correctly"
    (let [w (-> (bits/writer) (bits/write-bits 0 8))
          r (bits/reader (bits/finish w))]
      (is (= 0 (bits/read-bits r 8))))))


(deftest write-bits-crosses-byte-boundary-test
  (testing "values written across byte boundaries roundtrip correctly"
    ;; Write 5 bits then 6 bits — crosses the 8-bit boundary
    (let [w (-> (bits/writer)
                (bits/write-bits 31 5)   ; 11111
                (bits/write-bits 42 6))  ; 101010
          r (bits/reader (bits/finish w))]
      (is (= 31 (bits/read-bits r 5)))
      (is (= 42 (bits/read-bits r 6))))))


;; === D. Multiple values roundtrip ===

(deftest write-read-multiple-values-test
  (testing "multiple values of mixed widths roundtrip in order"
    (let [values [[7 3] [0 1] [255 8] [63 6] [1 1] [15 4]]
          w      (reduce (fn [w [v n]] (bits/write-bits w v n))
                         (bits/writer)
                         values)
          r      (bits/reader (bits/finish w))]
      (doseq [[expected n] values]
        (is (= expected (bits/read-bits r n))
            (str "expected " expected " reading " n " bits"))))))


(deftest write-read-repeated-patterns-test
  (testing "alternating 1/0 bits roundtrip correctly"
    (let [bits-seq (take 16 (cycle [1 0]))
          w        (reduce (fn [w b] (bits/write-bits w b 1))
                           (bits/writer)
                           bits-seq)
          r        (bits/reader (bits/finish w))]
      (doseq [expected bits-seq]
        (is (= expected (bits/read-bits r 1)))))))


;; === E. finish produces byte array ===

(deftest finish-returns-js-uint8array-test
  (testing "finish returns a js/Uint8Array"
    (let [w (-> (bits/writer) (bits/write-bits 42 8))
          b (bits/finish w)]
      (is (instance? js/Uint8Array b)
          "finish should return Uint8Array"))))


(deftest finish-empty-writer-test
  (testing "finish on empty writer returns empty Uint8Array"
    (let [b (bits/finish (bits/writer))]
      (is (instance? js/Uint8Array b))
      (is (= 0 (.-length b))
          "empty writer produces empty byte array"))))


(deftest finish-pads-last-byte-with-zeros-test
  (testing "partial last byte is zero-padded on the right (MSB-first)"
    ;; Write 4 bits: 1111 → stored as 11110000 in one byte
    (let [b (bits/finish (-> (bits/writer) (bits/write-bits 15 4)))]
      (is (= 1 (.-length b))
          "4 bits fits in 1 byte")
      (is (= 0xF0 (aget b 0))
          "1111 left-aligned in byte = 0xF0"))))


;; === F. base64url encode/decode ===

(deftest base64url-encode-returns-string-test
  (testing "base64url-encode returns a string"
    (let [b (js/Uint8Array. #js [72 101 108 108 111])]
      (is (string? (bits/base64url-encode b))
          "encode should return string"))))


(deftest base64url-encode-no-padding-test
  (testing "base64url-encode output contains no = padding characters"
    (doseq [len [1 2 3 4 5 6 7]]
      (let [b   (js/Uint8Array. (clj->js (range len)))
            enc (bits/base64url-encode b)]
        (is (not (str/includes? enc "="))
            (str "no padding for " len "-byte input"))))))


(deftest base64url-encode-url-safe-chars-test
  (testing "base64url-encode output uses - and _ instead of + and /"
    (let [b   (js/Uint8Array. (clj->js (range 256)))
          enc (bits/base64url-encode b)]
      (is (not (str/includes? enc "+"))
          "should not contain +")
      (is (not (str/includes? enc "/"))
          "should not contain /")
      (is (not (str/includes? enc "="))
          "should not contain ="))))


(deftest base64url-roundtrip-test
  (testing "encode then decode is identity for arbitrary byte arrays"
    (doseq [data [[0] [255] [0 255] [1 2 3] [72 101 108 108 111]
                  (vec (range 16)) (vec (range 100))]]
      (let [b       (js/Uint8Array. (clj->js data))
            decoded (bits/base64url-decode (bits/base64url-encode b))
            result  (vec decoded)]
        (is (= data result)
            (str "roundtrip failed for " data))))))


(deftest base64url-empty-roundtrip-test
  (testing "encode/decode of empty byte array is identity"
    (let [b       (js/Uint8Array. #js [])
          enc     (bits/base64url-encode b)
          decoded (bits/base64url-decode enc)]
      (is (= "" enc)
          "empty input encodes to empty string")
      (is (= 0 (.-length decoded))
          "empty string decodes to empty byte array"))))


;; === G. Full pipeline: write → finish → encode → decode → read ===

(deftest full-pipeline-roundtrip-test
  (testing "BitWriter → finish → base64url-encode → decode → BitReader roundtrip"
    (let [values [[63 6] [0 6] [7 3] [255 8] [1 1]]
          w      (reduce (fn [w [v n]] (bits/write-bits w v n))
                         (bits/writer)
                         values)
          enc    (bits/base64url-encode (bits/finish w))
          b      (bits/base64url-decode enc)
          r      (bits/reader b)]
      (doseq [[expected n] values]
        (is (= expected (bits/read-bits r n))
            (str "pipeline roundtrip failed for value " expected " (" n " bits)"))))))
