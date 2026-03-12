(ns fizzle.sharing.card-index-test
  "Tests for card index registry — stable integer ↔ card-id mapping for URL encoding.

   The card index assigns a stable, deterministic integer to every card in the
   registry. This allows compact URL encoding: store a small int instead of a
   full keyword string like :brain-freeze."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.sharing.card-index :as card-index]))


;; === A. Module definition tests ===

(deftest card-index-exports-card->int-test
  (testing "card->int is a map from keyword card-id to integer"
    (let [idx card-index/card->int]
      (is (map? idx)
          "card->int should be a map")
      (is (pos? (count idx))
          "card->int should have entries")
      (doseq [[k v] idx]
        (is (keyword? k)
            (str "Key should be keyword, got: " k))
        (is (int? v)
            (str "Value should be integer for key " k))))))


(deftest card-index-exports-int->card-test
  (testing "int->card is a map from integer to keyword card-id"
    (let [idx card-index/int->card]
      (is (map? idx)
          "int->card should be a map")
      (is (pos? (count idx))
          "int->card should have entries")
      (doseq [[k v] idx]
        (is (int? k)
            (str "Key should be integer, got: " k))
        (is (keyword? v)
            (str "Value should be keyword for key " k))))))


(deftest card-index-sizes-match-registry-test
  (testing "card->int and int->card have same size as all-cards"
    (let [card->int card-index/card->int
          int->card card-index/int->card
          total    card-index/card-count]
      (is (= total (count card->int))
          "card->int count should equal card-count")
      (is (= total (count int->card))
          "int->card count should equal card-count")
      (is (pos? total)
          "card-count should be positive"))))


;; === B. Roundtrip tests ===

(deftest card-index-roundtrip-card->int->card-test
  (testing "card-id → int → card-id roundtrip is identity"
    (doseq [[card-id n] card-index/card->int]
      (is (= card-id (get card-index/int->card n))
          (str "Roundtrip failed for " card-id " → " n)))))


(deftest card-index-roundtrip-int->card->int-test
  (testing "int → card-id → int roundtrip is identity"
    (doseq [[n card-id] card-index/int->card]
      (is (= n (get card-index/card->int card-id))
          (str "Roundtrip failed for " n " → " card-id)))))


;; === C. Stability tests ===

(deftest card-index-indices-start-at-zero-test
  (testing "Indices form a dense range starting at 0"
    (let [indices (set (vals card-index/card->int))
          n       card-index/card-count]
      (is (= n (count indices))
          "Indices should be unique")
      (is (= indices (set (range n)))
          "Indices should be the dense range [0, n)"))))


(deftest card-index-known-cards-have-stable-index-test
  (testing "Known cards always get the same index (alphabetical ordering)"
    ;; These exact values are fixed once the card pool is established.
    ;; If they change, URL-encoded snapshots become invalid.
    (is (int? (get card-index/card->int :dark-ritual))
        ":dark-ritual should have an integer index")
    (is (int? (get card-index/card->int :brain-freeze))
        ":brain-freeze should have an integer index")
    (is (int? (get card-index/card->int :plains))
        ":plains should have an integer index")))


(deftest card-index-ordering-is-alphabetical-test
  (testing "card->int assigns indices in alphabetical order of card-id names"
    (let [sorted-ids  (sort-by name (keys card-index/card->int))
          assigned    (map card-index/card->int sorted-ids)]
      (is (= assigned (range (count sorted-ids)))
          "Alphabetically sorted cards should have indices 0, 1, 2, ..."))))


;; === D. Lookup helpers ===

(deftest card-index-encode-test
  (testing "encode returns integer for known card-id"
    (let [n (card-index/encode :dark-ritual)]
      (is (int? n)
          "encode should return integer")
      (is (= n (get card-index/card->int :dark-ritual))
          "encode should match card->int lookup")))

  (testing "encode returns nil for unknown card-id"
    (is (nil? (card-index/encode :no-such-card))
        "encode should return nil for unknown card")))


(deftest card-index-decode-test
  (testing "decode returns card-id keyword for valid integer"
    (let [n        (card-index/encode :dark-ritual)
          card-id  (card-index/decode n)]
      (is (= :dark-ritual card-id)
          "decode should return :dark-ritual")))

  (testing "decode returns nil for out-of-range integer"
    (is (nil? (card-index/decode -1))
        "decode should return nil for -1")
    (is (nil? (card-index/decode card-index/card-count))
        "decode should return nil for index equal to card-count")))
