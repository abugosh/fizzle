(ns fizzle.engine.reveal-until-test
  "Tests for :reveal-until effect type and selection mechanism.
   Tests engine-level mechanic, not card definitions.

   Uses real registered cards:
     :dark-ritual   — instant (matches {:match/types #{:instant :sorcery}})
     :nimble-mongoose — creature (does not match)
     :island        — land (does not match)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as fx]
    [fizzle.events.selection.core :as selection-core]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Criteria
;; =====================================================

(def instant-sorcery-criteria {:match/types #{:instant :sorcery}})


;; =====================================================
;; A. Effect type returns :needs-selection
;; =====================================================

(deftest reveal-until-effect-returns-needs-selection
  (testing "execute-effect-checked returns :needs-selection for :reveal-until"
    (let [db (th/create-test-db)
          effect {:effect/type :reveal-until
                  :effect/criteria instant-sorcery-criteria}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :needs-selection))
      (is (= :reveal-until (:effect/type (:needs-selection result)))))))


;; =====================================================
;; B. Selection builder — match found on top
;; =====================================================

(deftest build-reveal-until-match-on-top
  (testing "match on top: instant at pos 0, creatures behind"
    (let [[db obj-ids] (th/add-cards-to-library
                         (th/create-test-db)
                         [:dark-ritual :nimble-mongoose :nimble-mongoose]
                         :player-1)
          spell-id (random-uuid)
          effect {:effect/type :reveal-until
                  :effect/criteria instant-sorcery-criteria}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (some? selection) "Selection should be created")
      (is (= :reveal-until (:selection/domain selection)) "Domain must be :reveal-until")
      (is (= :pick-from-zone (:selection/mechanism selection)) "ADR-030: mechanism must be :pick-from-zone")
      (is (= 1 (:selection/select-count selection)) "Match found: select-count=1")
      (is (= :exact (:selection/validation selection)) "Match found: validation=:exact")
      ;; Only the top card (match) is revealed
      (is (= 1 (count (:selection/candidates selection))) "Only match revealed")
      (is (= #{(first obj-ids)} (:selection/valid-targets selection))
          "Valid targets contains only the match"))))


;; =====================================================
;; C. Selection builder — match deep in library
;; =====================================================

(deftest build-reveal-until-match-deep
  (testing "match deep: 3 creatures, then instant, then land — reveals 4 cards"
    (let [[db obj-ids] (th/add-cards-to-library
                         (th/create-test-db)
                         [:nimble-mongoose :nimble-mongoose :nimble-mongoose
                          :dark-ritual :island]
                         :player-1)
          spell-id (random-uuid)
          effect {:effect/type :reveal-until
                  :effect/criteria instant-sorcery-criteria}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (some? selection))
      (is (= :reveal-until (:selection/domain selection)))
      (is (= 1 (:selection/select-count selection)) "Match found: select-count=1")
      ;; Candidates = 3 creatures + 1 instant (all revealed before and including match)
      (is (= 4 (count (:selection/candidates selection))) "4 cards revealed (3 non-match + match)")
      ;; Valid target = only the instant (4th card, index 3)
      (is (= #{(nth obj-ids 3)} (:selection/valid-targets selection)))
      ;; Remainder = the 3 non-match revealed creatures
      (is (= 3 (count (:selection/remainder selection))) "Remainder = 3 non-match revealed"))))


;; =====================================================
;; D. Selection builder — no match
;; =====================================================

(deftest build-reveal-until-no-match
  (testing "no match: all library cards revealed, empty valid-targets"
    (let [[db _obj-ids] (th/add-cards-to-library
                          (th/create-test-db)
                          [:nimble-mongoose :nimble-mongoose :island]
                          :player-1)
          spell-id (random-uuid)
          effect {:effect/type :reveal-until
                  :effect/criteria instant-sorcery-criteria}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (some? selection))
      (is (= :reveal-until (:selection/domain selection)))
      (is (= 0 (:selection/select-count selection)) "No match: select-count=0")
      (is (= :at-most (:selection/validation selection)) "No match: validation=:at-most")
      ;; All 3 library cards are candidates
      (is (= 3 (count (:selection/candidates selection))))
      ;; No valid targets
      (is (empty? (:selection/valid-targets selection)) "No valid targets when no match")
      ;; All cards in remainder
      (is (= 3 (count (:selection/remainder selection))) "All cards in remainder"))))


;; =====================================================
;; E. Selection builder — empty library
;; =====================================================

(deftest build-reveal-until-empty-library
  (testing "empty library: builder returns nil, no selection created"
    (let [db (th/create-test-db)
          spell-id (random-uuid)
          effect {:effect/type :reveal-until
                  :effect/criteria instant-sorcery-criteria}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (nil? selection) "Empty library produces no selection"))))


;; =====================================================
;; F. Selection builder — single card with match
;; =====================================================

(deftest build-reveal-until-single-card-match
  (testing "single-card library with match: card becomes valid target"
    (let [[db obj-ids] (th/add-cards-to-library
                         (th/create-test-db)
                         [:dark-ritual]
                         :player-1)
          spell-id (random-uuid)
          effect {:effect/type :reveal-until
                  :effect/criteria instant-sorcery-criteria}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (some? selection))
      (is (= 1 (:selection/select-count selection)))
      (is (= #{(first obj-ids)} (:selection/valid-targets selection)))
      (is (= 1 (count (:selection/candidates selection))))
      (is (empty? (:selection/remainder selection)) "No non-match revealed cards"))))


;; =====================================================
;; G. ADR-030 compliance — mechanism and domain
;; =====================================================

(deftest build-reveal-until-adr030-compliance
  (testing "selection has :selection/mechanism :pick-from-zone and :selection/domain :reveal-until"
    (let [[db _obj-ids] (th/add-cards-to-library
                          (th/create-test-db)
                          [:dark-ritual :nimble-mongoose]
                          :player-1)
          spell-id (random-uuid)
          effect {:effect/type :reveal-until
                  :effect/criteria instant-sorcery-criteria}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (= :pick-from-zone (:selection/mechanism selection)))
      (is (= :reveal-until (:selection/domain selection))))))


;; =====================================================
;; H. Execute — match found: card moves to hand, non-match to bottom
;; =====================================================

(deftest execute-reveal-until-match-moves-to-hand
  (testing "match found: match card moves to hand, non-match revealed go to bottom"
    (let [[db obj-ids] (th/add-cards-to-library
                         (th/create-test-db)
                         [:nimble-mongoose :nimble-mongoose :dark-ritual]
                         :player-1)
          [creature-1-id creature-2-id instant-id] obj-ids
          ;; Simulate confirmed selection with the instant selected
          selection {:selection/mechanism :pick-from-zone
                     :selection/domain    :reveal-until
                     :selection/lifecycle :standard
                     :selection/player-id :player-1
                     :selection/selected  #{instant-id}
                     :selection/candidates #{creature-1-id creature-2-id instant-id}
                     :selection/valid-targets #{instant-id}
                     :selection/select-count 1
                     :selection/validation :exact
                     :selection/auto-confirm? false
                     :selection/revealed-cards [creature-1-id creature-2-id instant-id]
                     :selection/remainder [creature-1-id creature-2-id]
                     :selection/remaining-effects []}
          result (selection-core/execute-confirmed-selection db selection)
          db-after (:db result)]
      (is (some? db-after) "Result has :db key")
      ;; instant moves to hand
      (let [hand (q/get-objects-in-zone db-after :player-1 :hand)]
        (is (= 1 (count hand)) "Exactly 1 card in hand")
        (is (= instant-id (:object/id (first hand))) "Instant is in hand"))
      ;; 2 creatures remain in library at bottom positions
      (let [library (sort-by :object/position
                             (q/get-objects-in-zone db-after :player-1 :library))]
        (is (= 2 (count library)) "2 creatures remain in library")
        ;; Creatures should be at bottom (positions >= any pre-existing card positions)
        (is (every? #(contains? #{creature-1-id creature-2-id} (:object/id %))
                    library)
            "Both creatures are in library")))))


;; =====================================================
;; I. Execute — no match: all revealed go to bottom
;; =====================================================

(deftest execute-reveal-until-no-match-all-to-bottom
  (testing "no match: all library cards reassigned to bottom positions"
    (let [[db obj-ids] (th/add-cards-to-library
                         (th/create-test-db)
                         [:nimble-mongoose :island]
                         :player-1)
          [creature-id land-id] obj-ids
          selection {:selection/mechanism :pick-from-zone
                     :selection/domain    :reveal-until
                     :selection/lifecycle :standard
                     :selection/player-id :player-1
                     :selection/selected  #{}
                     :selection/candidates #{creature-id land-id}
                     :selection/valid-targets #{}
                     :selection/select-count 0
                     :selection/validation :at-most
                     :selection/auto-confirm? false
                     :selection/revealed-cards [creature-id land-id]
                     :selection/remainder [creature-id land-id]
                     :selection/remaining-effects []}
          result (selection-core/execute-confirmed-selection db selection)
          db-after (:db result)]
      (is (some? db-after))
      ;; Nothing moves to hand
      (let [hand (q/get-objects-in-zone db-after :player-1 :hand)]
        (is (empty? hand) "No cards in hand when no match"))
      ;; Both cards still in library
      (let [library (q/get-objects-in-zone db-after :player-1 :library)]
        (is (= 2 (count library)) "Both cards remain in library")))))


;; =====================================================
;; J. Execute — non-revealed library cards retain their original positions
;; =====================================================

(deftest execute-reveal-until-non-revealed-positions-undisturbed
  (testing "non-revealed cards at positions 2,3,4 retain their exact original positions"
    (let [;; Library: [creature, instant, land, land, land]
          ;; Reveal stops at instant (pos 1) — 3 lands at positions 2,3,4 are never revealed
          [db obj-ids] (th/add-cards-to-library
                         (th/create-test-db)
                         [:nimble-mongoose :dark-ritual :island :island :island]
                         :player-1)
          [creature-id instant-id land-1-id land-2-id land-3-id] obj-ids
          ;; Record original positions of the 3 unrevealed lands
          land-positions-before (into {} (map (fn [obj]
                                                [(:object/id obj) (:object/position obj)])
                                              (filter #(contains? #{land-1-id land-2-id land-3-id}
                                                                  (:object/id %))
                                                      (q/get-objects-in-zone db :player-1 :library))))
          ;; Simulate confirmed selection: only creature and instant were revealed
          ;; instant (the match) is selected; creature goes to remainder (bottom)
          selection {:selection/mechanism :pick-from-zone
                     :selection/domain    :reveal-until
                     :selection/lifecycle :standard
                     :selection/player-id :player-1
                     :selection/selected  #{instant-id}
                     :selection/candidates #{creature-id instant-id}
                     :selection/valid-targets #{instant-id}
                     :selection/select-count 1
                     :selection/validation :exact
                     :selection/auto-confirm? false
                     :selection/revealed-cards [creature-id instant-id]
                     :selection/remainder [creature-id]
                     :selection/found-zone :hand
                     :selection/remaining-effects []}
          result (selection-core/execute-confirmed-selection db selection)
          db-after (:db result)
          library-after (q/get-objects-in-zone db-after :player-1 :library)
          pos-by-id (into {} (map (fn [obj] [(:object/id obj) (:object/position obj)])
                                  library-after))]
      ;; Instant moved to hand
      (let [hand (q/get-objects-in-zone db-after :player-1 :hand)]
        (is (= 1 (count hand)) "Exactly 1 card in hand (the instant)")
        (is (= instant-id (:object/id (first hand))) "Instant is in hand"))
      ;; 4 cards remain in library (creature at bottom + 3 lands at original positions)
      (is (= 4 (count library-after)) "4 cards remain in library")
      ;; Creature is at bottom (position > all original land positions)
      (let [creature-pos (get pos-by-id creature-id)
            max-land-pos (apply max (vals land-positions-before))]
        (is (some? creature-pos) "Creature is in library")
        (is (> creature-pos max-land-pos) "Creature is at bottom (after the 3 lands)"))
      ;; The 3 lands retain their EXACT original positions
      (doseq [land-id [land-1-id land-2-id land-3-id]]
        (is (= (get land-positions-before land-id)
               (get pos-by-id land-id))
            (str "Land " land-id " retains its original position"))))))


;; =====================================================
;; K. Execute — remaining-effects are preserved on selection
;; =====================================================

(deftest build-reveal-until-preserves-remaining-effects
  (testing "remaining-effects are preserved in selection"
    (let [[db _obj-ids] (th/add-cards-to-library
                          (th/create-test-db)
                          [:dark-ritual]
                          :player-1)
          spell-id (random-uuid)
          remaining [{:effect/type :draw :effect/count 1}]
          effect {:effect/type :reveal-until
                  :effect/criteria instant-sorcery-criteria}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect remaining)]
      (is (= remaining (:selection/remaining-effects selection))))))
