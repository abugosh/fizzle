(ns fizzle.engine.spec-common-test
  "Tests for engine/spec-common.cljs shared predicates.

   Tests cover all 7 shared specs:
   - :game/player-id   — keyword? only (no int)
   - :game/object-id   — uuid? only
   - :game/card-color  — 5 MTG colors (no colorless)
   - :game/mana-color  — 6 pool colors (includes colorless)
   - :game/mana-map    — partial map of mana-color->nat-int
   - :game/mana-pool   — full 6-key map (all colors required)
   - :game/collection-flexible — set or vector (not list, not map)"
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer [deftest is testing]]
    [fizzle.engine.spec-common]))


;; =====================================================
;; A. :game/player-id
;; =====================================================

(deftest test-player-id-valid
  (testing "keyword player-ids are valid"
    (is (s/valid? :game/player-id :player-1) "player-1 keyword valid")
    (is (s/valid? :game/player-id :player-2) "player-2 keyword valid")
    (is (s/valid? :game/player-id :any-keyword) "arbitrary keyword valid")))


(deftest test-player-id-rejects-int
  (testing "int player-ids are rejected — ints are Datascript EIDs, not player identity"
    (is (not (s/valid? :game/player-id 42)) "int 42 rejected")
    (is (not (s/valid? :game/player-id 1)) "int 1 rejected")
    (is (not (s/valid? :game/player-id 0)) "int 0 rejected")))


(deftest test-player-id-rejects-other-types
  (testing "non-keyword types are rejected"
    (is (not (s/valid? :game/player-id "player-1")) "string rejected")
    (is (not (s/valid? :game/player-id nil)) "nil rejected")
    (is (not (s/valid? :game/player-id #uuid "00000000-0000-0000-0000-000000000000")) "uuid rejected")))


;; =====================================================
;; B. :game/object-id
;; =====================================================

(deftest test-object-id-valid
  (testing "UUIDs are valid object-ids"
    (is (s/valid? :game/object-id (random-uuid)) "random-uuid valid")
    (is (s/valid? :game/object-id #uuid "00000000-0000-0000-0000-000000000000") "literal uuid valid")))


(deftest test-object-id-rejects-non-uuid
  (testing "non-UUID types are rejected"
    (is (not (s/valid? :game/object-id "not-a-uuid")) "string rejected")
    (is (not (s/valid? :game/object-id 42)) "int rejected")
    (is (not (s/valid? :game/object-id :some-keyword)) "keyword rejected")
    (is (not (s/valid? :game/object-id nil)) "nil rejected")))


;; =====================================================
;; C. :game/card-color — 5 MTG colors, NO colorless
;; =====================================================

(deftest test-card-color-all-five-valid
  (testing "all 5 MTG card colors are valid"
    (doseq [color [:white :blue :black :red :green]]
      (is (s/valid? :game/card-color color) (str color " is a valid card color")))))


(deftest test-card-color-rejects-colorless
  (testing "colorless is NOT a card color (it is a mana color)"
    (is (not (s/valid? :game/card-color :colorless)) "colorless rejected from card-color")))


(deftest test-card-color-rejects-mana-cost-keys
  (testing "mana-cost-only keys are not card colors"
    (is (not (s/valid? :game/card-color :any)) ":any rejected")
    (is (not (s/valid? :game/card-color :x)) ":x rejected")
    (is (not (s/valid? :game/card-color :generic)) ":generic rejected")))


(deftest test-card-color-rejects-other-types
  (testing "non-keyword types rejected"
    (is (not (s/valid? :game/card-color "white")) "string rejected")
    (is (not (s/valid? :game/card-color nil)) "nil rejected")
    (is (not (s/valid? :game/card-color 1)) "int rejected")))


;; =====================================================
;; D. :game/mana-color — 6 pool colors (includes colorless)
;; =====================================================

(deftest test-mana-color-all-six-valid
  (testing "all 6 mana pool colors are valid"
    (doseq [color [:white :blue :black :red :green :colorless]]
      (is (s/valid? :game/mana-color color) (str color " is a valid mana color")))))


(deftest test-mana-color-rejects-mana-cost-only-keys
  (testing "mana-cost-only keys are not valid mana pool colors"
    (is (not (s/valid? :game/mana-color :any)) ":any is mana-cost-only, not pool color")
    (is (not (s/valid? :game/mana-color :x)) ":x is mana-cost-only, not pool color")
    (is (not (s/valid? :game/mana-color :generic)) ":generic rejected")))


(deftest test-mana-color-rejects-other-types
  (testing "non-keyword types rejected"
    (is (not (s/valid? :game/mana-color "black")) "string rejected")
    (is (not (s/valid? :game/mana-color nil)) "nil rejected")))


;; =====================================================
;; E. :game/mana-map — partial map, valid color keys, nat-int values
;; =====================================================

(deftest test-mana-map-partial-maps-valid
  (testing "partial mana maps with valid color keys are valid"
    (is (s/valid? :game/mana-map {:black 3}) "single color map valid")
    (is (s/valid? :game/mana-map {:white 1 :blue 2}) "two colors valid")
    (is (s/valid? :game/mana-map {:colorless 5}) "colorless key valid")
    (is (s/valid? :game/mana-map {}) "empty map valid (no minimum)")))


(deftest test-mana-map-full-pool-valid
  (testing "full 6-color mana map is valid"
    (is (s/valid? :game/mana-map {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0})
        "zero-valued full pool valid")))


(deftest test-mana-map-rejects-negative-values
  (testing "negative mana counts are rejected"
    (is (not (s/valid? :game/mana-map {:black -1})) "negative value rejected")))


(deftest test-mana-map-rejects-bad-keys
  (testing "invalid mana keys are rejected"
    (is (not (s/valid? :game/mana-map {:generic 1})) ":generic is bad key — catches silent nil in merge-with +")
    (is (not (s/valid? :game/mana-map {:any 1})) ":any is mana-cost-only, not pool key")
    (is (not (s/valid? :game/mana-map {:x true})) ":x is mana-cost-only, not pool key")))


(deftest test-mana-map-rejects-float-values
  (testing "float mana counts are rejected"
    (is (not (s/valid? :game/mana-map {:black 1.5})) "float value rejected")))


(deftest test-mana-map-rejects-non-map
  (testing "non-map types rejected"
    (is (not (s/valid? :game/mana-map nil)) "nil rejected")
    (is (not (s/valid? :game/mana-map [:black 3])) "vector rejected")))


;; =====================================================
;; F. :game/mana-pool — full 6-key map (all colors required)
;; =====================================================

(deftest test-mana-pool-full-six-key-valid
  (testing "full 6-color pool is valid"
    (is (s/valid? :game/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0})
        "zero-valued full pool valid")
    (is (s/valid? :game/mana-pool {:white 1 :blue 2 :black 3 :red 0 :green 0 :colorless 5})
        "non-zero full pool valid")))


(deftest test-mana-pool-rejects-partial-maps
  (testing "partial maps (missing colors) are rejected"
    (is (not (s/valid? :game/mana-pool {:black 3})) "single-color map rejected — missing 5 colors")
    (is (not (s/valid? :game/mana-pool {})) "empty map rejected — missing all colors")
    (is (not (s/valid? :game/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0}))
        "5-color map rejected — missing colorless")))


(deftest test-mana-pool-rejects-extra-keys
  (testing "extra keys beyond the 6 valid colors are rejected"
    (is (not (s/valid? :game/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0 :generic 1}))
        "extra :generic key rejected")))


;; =====================================================
;; G. :game/collection-flexible — set or vector
;; =====================================================

(deftest test-collection-flexible-set-valid
  (testing "sets are valid collections"
    (is (s/valid? :game/collection-flexible #{1 2 3}) "set of ints valid")
    (is (s/valid? :game/collection-flexible #{:a :b}) "set of keywords valid")
    (is (s/valid? :game/collection-flexible #{}) "empty set valid")))


(deftest test-collection-flexible-vector-valid
  (testing "vectors are valid collections"
    (is (s/valid? :game/collection-flexible [1 2 3]) "vector of ints valid")
    (is (s/valid? :game/collection-flexible [:a :b]) "vector of keywords valid")
    (is (s/valid? :game/collection-flexible []) "empty vector valid")))


(deftest test-collection-flexible-rejects-list
  (testing "lists are not valid (only set or vector)"
    (is (not (s/valid? :game/collection-flexible '(1 2 3))) "list rejected")))


(deftest test-collection-flexible-rejects-map
  (testing "maps are not valid collections"
    (is (not (s/valid? :game/collection-flexible {:a 1})) "map rejected")))


(deftest test-collection-flexible-rejects-nil
  (testing "nil is not a valid collection"
    (is (not (s/valid? :game/collection-flexible nil)) "nil rejected")))
