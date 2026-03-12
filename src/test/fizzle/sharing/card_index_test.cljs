(ns fizzle.sharing.card-index-test
  "Tests for card index registry — stable integer ↔ card-id mapping for URL encoding.

   The card index assigns a stable, deterministic integer to every card in the
   registry. Index = position in the registry all-cards vector. New cards are
   appended to all-cards so existing indices never shift."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.engine.cards :as cards]
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
  (testing "Known cards always get the same index (registry position ordering)"
    ;; These exact values are fixed by the position in registry/all-cards.
    ;; If they change, URL-encoded snapshots become invalid.
    ;; dark-ritual is first in registry → index 0
    (is (= 0 (card-index/encode :dark-ritual))
        ":dark-ritual is first in registry, should be index 0")
    ;; brain-freeze is 13th entry (0-based index 12) in the individual cards list
    (is (= 12 (card-index/encode :brain-freeze))
        ":brain-freeze is at registry position 12")))


(deftest card-index-ordering-matches-registry-test
  (testing "card->int indices match position in all-cards vector"
    (let [registry-ids (mapv :card/id cards/all-cards)]
      (doseq [[i card-id] (map-indexed vector registry-ids)]
        (is (= i (card-index/encode card-id))
            (str card-id " should have index " i " (its registry position)"))))))


(deftest card-index-new-cards-appended-do-not-shift-existing-test
  (testing "First card in registry is always index 0 regardless of card pool size"
    (let [first-id (:card/id (first cards/all-cards))]
      (is (= 0 (card-index/encode first-id))
          "First registry card always gets index 0")))

  (testing "Last card in registry has index (card-count - 1)"
    (let [last-id (:card/id (last cards/all-cards))]
      (is (= (dec card-index/card-count) (card-index/encode last-id))
          "Last registry card gets index (card-count - 1)"))))


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
