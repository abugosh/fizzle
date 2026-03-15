(ns fizzle.sharing.bits
  "Bit-level encoding primitives for compact URL snapshot encoding.

   BitWriter: accumulates bits MSB-first into an auto-growing byte buffer.
   BitReader: reads bits MSB-first from a Uint8Array.
   base64url: encode/decode Uint8Array ↔ base64url string with no padding.

   Bit order: MSB-first within each byte. Partial trailing bytes are
   zero-padded on the right (low-order bits) when finished.")


;; ---------------------------------------------------------------------------
;; Internal state
;; BitWriter is a plain map for immutability:
;;   {:bits   vector of 0/1 integers (logical bit stream)
;;    :count  total bits written}
;; finish converts the bit stream into a Uint8Array.

(defn writer
  "Return a new, empty BitWriter."
  []
  {:bits [] :count 0})


(defn bits-written
  "Return the number of bits written to the BitWriter so far."
  [w]
  (:count w))


(defn write-bits
  "Append n bits of value v (MSB-first) to BitWriter w.
   v must fit in n bits (0 ≤ v < 2^n). Returns updated writer."
  [w v n]
  (let [new-bits (loop [i (dec n) acc (:bits w)]
                   (if (< i 0)
                     acc
                     (recur (dec i)
                            (conj acc (bit-and 1 (bit-shift-right v i))))))]
    {:bits  new-bits
     :count (+ (:count w) n)}))


(defn finish
  "Flush the BitWriter to a js/Uint8Array.
   The bit stream is zero-padded on the right to a full byte boundary."
  [w]
  (let [bits      (:bits w)
        n-bits    (count bits)
        n-bytes   (js/Math.ceil (/ n-bits 8))
        buf       (js/Uint8Array. n-bytes)]
    (dotimes [i n-bytes]
      (let [byte-val (loop [b 0 j 0]
                       (if (= j 8)
                         b
                         (let [bit-idx (+ (* i 8) j)
                               bit     (if (< bit-idx n-bits)
                                         (nth bits bit-idx)
                                         0)]
                           (recur (bit-or (bit-shift-left b 1) bit)
                                  (inc j)))))]
        (aset buf i byte-val)))
    buf))


;; ---------------------------------------------------------------------------
;; BitReader

(deftype BitReader
  [^:mutable buf ^:mutable pos]

  Object

  (readBits
    [_ n]
    (loop [remaining n result 0]
      (if (= remaining 0)
        result
        (let [byte-idx  (js/Math.floor (/ pos 8))
              bit-idx   (- 7 (mod pos 8))
              byte-val  (aget buf byte-idx)
              bit       (bit-and 1 (bit-shift-right byte-val bit-idx))]
          (set! pos (inc pos))
          (recur (dec remaining)
                 (bit-or (bit-shift-left result 1) bit)))))))


(defn reader
  "Return a new BitReader over the given js/Uint8Array."
  [buf]
  (BitReader. buf 0))


(defn read-bits
  "Read n bits MSB-first from BitReader r. Returns integer value."
  [^BitReader r n]
  (.readBits r n))


;; ---------------------------------------------------------------------------
;; base64url codec

(def ^:private b64-chars
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")


(defn base64url-encode
  "Encode a js/Uint8Array to a base64url string (no padding)."
  [buf]
  (let [n (.-length buf)]
    (if (= n 0)
      ""
      (let [sb (array)]
        (loop [i 0]
          (when (< i n)
            (let [b0 (aget buf i)
                  b1 (if (< (inc i) n) (aget buf (inc i)) 0)
                  b2 (if (< (+ i 2) n) (aget buf (+ i 2)) 0)
                  c0 (bit-shift-right b0 2)
                  c1 (bit-or (bit-shift-left (bit-and b0 0x3) 4)
                             (bit-shift-right b1 4))
                  c2 (bit-or (bit-shift-left (bit-and b1 0xF) 2)
                             (bit-shift-right b2 6))
                  c3 (bit-and b2 0x3F)
                  rem (- n i)]
              (.push sb (.charAt b64-chars c0))
              (.push sb (.charAt b64-chars c1))
              (when (>= rem 2) (.push sb (.charAt b64-chars c2)))
              (when (>= rem 3) (.push sb (.charAt b64-chars c3)))
              (recur (+ i 3)))))
        (.join sb "")))))


(def ^:private b64-decode-map
  (let [m (js/Object.)]
    (dotimes [i 64]
      (aset m (.charAt b64-chars i) i))
    m))


(defn base64url-decode
  "Decode a base64url string (no padding) to a js/Uint8Array."
  [s]
  (if (= (count s) 0)
    (js/Uint8Array. 0)
    (let [len    (count s)
          ;; number of output bytes: floor(len * 6 / 8)
          n-out  (js/Math.floor (/ (* len 6) 8))
          buf    (js/Uint8Array. n-out)]
      (loop [i 0 out-i 0]
        (when (and (< i len) (< out-i n-out))
          (let [c0 (aget b64-decode-map (.charAt s i))
                c1 (if (< (inc i) len) (aget b64-decode-map (.charAt s (inc i))) 0)
                c2 (if (< (+ i 2) len) (aget b64-decode-map (.charAt s (+ i 2))) 0)
                c3 (if (< (+ i 3) len) (aget b64-decode-map (.charAt s (+ i 3))) 0)
                b0 (bit-or (bit-shift-left c0 2) (bit-shift-right c1 4))
                b1 (bit-or (bit-shift-left (bit-and c1 0xF) 4) (bit-shift-right c2 2))
                b2 (bit-or (bit-shift-left (bit-and c2 0x3) 6) c3)]
            (aset buf out-i b0)
            (when (< (inc out-i) n-out) (aset buf (inc out-i) b1))
            (when (< (+ out-i 2) n-out) (aset buf (+ out-i 2) b2))
            (recur (+ i 4) (+ out-i 3)))))
      buf)))
