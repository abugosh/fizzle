(ns fizzle.cards.lands.cycling-lands-test
  "Tests for Onslaught cycling land cycle.

   Barren Moor, Lonely Sandbar, Secluded Steppe,
   Forgotten Cave, Tranquil Thicket.

   Each is a Land that enters tapped, taps for one color of mana,
   and has cycling for one colored mana.

   Oracle (all five):
   This land enters tapped.
   {T}: Add {COLOR}.
   Cycling {COLOR} ({COLOR}, Discard this card: Draw a card.)

   Test categories:
   A. Card definition — all fields with exact values
   B. Enters tapped — land enters battlefield tapped
   C. Mana ability activation — tap for colored mana
   D. Cycling — pay cost, discard self, draw 1
   E. Cannot-activate guards — wrong zone, already tapped
   F. Cannot-cycle guards — wrong zone, insufficient mana
   G. Edge cases — cycle completeness, cycling from hand with no library"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.lands.cycling-lands :as cycling-lands]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.events.cycling :as cycling]
    [fizzle.events.lands :as lands]
    [fizzle.test-helpers :as th]))


;; === Cycle specs for parameterized tests ===

(def ^:private cycling-land-specs
  [{:card-id :barren-moor :color :black :cycling-cost {:black 1}
    :name "Barren Moor" :card-def cycling-lands/barren-moor}
   {:card-id :lonely-sandbar :color :blue :cycling-cost {:blue 1}
    :name "Lonely Sandbar" :card-def cycling-lands/lonely-sandbar}
   {:card-id :secluded-steppe :color :white :cycling-cost {:white 1}
    :name "Secluded Steppe" :card-def cycling-lands/secluded-steppe}
   {:card-id :forgotten-cave :color :red :cycling-cost {:red 1}
    :name "Forgotten Cave" :card-def cycling-lands/forgotten-cave}
   {:card-id :tranquil-thicket :color :green :cycling-cost {:green 1}
    :name "Tranquil Thicket" :card-def cycling-lands/tranquil-thicket}])


;; =====================================================
;; A. Card Definition
;; =====================================================

;; Oracle: "This land enters tapped. {T}: Add {B}. Cycling {B}"
(deftest barren-moor-card-definition-test
  (testing "Barren Moor base card data is correct"
    (let [card cycling-lands/barren-moor]
      (is (= :barren-moor (:card/id card)))
      (is (= "Barren Moor" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (true? (:card/enters-tapped card)))
      (is (= {:black 1} (:card/cycling card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= 1 (count (:card/abilities card))))
      (let [ability (first (:card/abilities card))]
        (is (= :mana (:ability/type ability)))
        (is (= {:tap true} (:ability/cost ability)))
        (is (= [{:effect/type :add-mana :effect/mana {:black 1}}]
               (:ability/effects ability)))))))


;; Oracle: "This land enters tapped. {T}: Add {U}. Cycling {U}"
(deftest lonely-sandbar-card-definition-test
  (testing "Lonely Sandbar base card data is correct"
    (let [card cycling-lands/lonely-sandbar]
      (is (= :lonely-sandbar (:card/id card)))
      (is (= "Lonely Sandbar" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (true? (:card/enters-tapped card)))
      (is (= {:blue 1} (:card/cycling card)))
      (is (= 1 (count (:card/abilities card))))
      (let [ability (first (:card/abilities card))]
        (is (= :mana (:ability/type ability)))
        (is (= {:tap true} (:ability/cost ability)))
        (is (= [{:effect/type :add-mana :effect/mana {:blue 1}}]
               (:ability/effects ability)))))))


;; Oracle: "This land enters tapped. {T}: Add {W}. Cycling {W}"
(deftest secluded-steppe-card-definition-test
  (testing "Secluded Steppe base card data is correct"
    (let [card cycling-lands/secluded-steppe]
      (is (= :secluded-steppe (:card/id card)))
      (is (= "Secluded Steppe" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (true? (:card/enters-tapped card)))
      (is (= {:white 1} (:card/cycling card)))
      (is (= 1 (count (:card/abilities card))))
      (let [ability (first (:card/abilities card))]
        (is (= :mana (:ability/type ability)))
        (is (= {:tap true} (:ability/cost ability)))
        (is (= [{:effect/type :add-mana :effect/mana {:white 1}}]
               (:ability/effects ability)))))))


;; Oracle: "This land enters tapped. {T}: Add {R}. Cycling {R}"
(deftest forgotten-cave-card-definition-test
  (testing "Forgotten Cave base card data is correct"
    (let [card cycling-lands/forgotten-cave]
      (is (= :forgotten-cave (:card/id card)))
      (is (= "Forgotten Cave" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (true? (:card/enters-tapped card)))
      (is (= {:red 1} (:card/cycling card)))
      (is (= 1 (count (:card/abilities card))))
      (let [ability (first (:card/abilities card))]
        (is (= :mana (:ability/type ability)))
        (is (= {:tap true} (:ability/cost ability)))
        (is (= [{:effect/type :add-mana :effect/mana {:red 1}}]
               (:ability/effects ability)))))))


;; Oracle: "This land enters tapped. {T}: Add {G}. Cycling {G}"
(deftest tranquil-thicket-card-definition-test
  (testing "Tranquil Thicket base card data is correct"
    (let [card cycling-lands/tranquil-thicket]
      (is (= :tranquil-thicket (:card/id card)))
      (is (= "Tranquil Thicket" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (true? (:card/enters-tapped card)))
      (is (= {:green 1} (:card/cycling card)))
      (is (= 1 (count (:card/abilities card))))
      (let [ability (first (:card/abilities card))]
        (is (= :mana (:ability/type ability)))
        (is (= {:tap true} (:ability/cost ability)))
        (is (= [{:effect/type :add-mana :effect/mana {:green 1}}]
               (:ability/effects ability)))))))


(deftest cycling-lands-cycle-completeness-test
  (testing "cards vector contains all 5 cycling lands"
    (is (= 5 (count cycling-lands/cards)))
    (is (= #{:barren-moor :lonely-sandbar :secluded-steppe
             :forgotten-cave :tranquil-thicket}
           (set (map :card/id cycling-lands/cards))))))


;; =====================================================
;; B. Enters Tapped
;; =====================================================

;; Oracle: "This land enters tapped."
(deftest cycling-lands-enter-tapped-test
  (doseq [{:keys [card-id name]} cycling-land-specs]
    (testing (str name " enters the battlefield tapped")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            db-played (lands/play-land db' :player-1 obj-id)]
        (is (= :battlefield (th/get-object-zone db-played obj-id))
            (str name " should be on battlefield"))
        (is (true? (:object/tapped (q/get-object db-played obj-id)))
            (str name " should be tapped after entering"))))))


;; =====================================================
;; C. Mana Ability Activation
;; =====================================================

;; Oracle: "{T}: Add {COLOR}."
(deftest cycling-lands-tap-for-mana-test
  (doseq [{:keys [card-id color name]} cycling-land-specs]
    (testing (str name " taps for " (clojure.core/name color) " mana")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            initial-pool (q/get-mana-pool db' :player-1)
            _ (is (= 0 (get initial-pool color))
                  (str "Precondition: " (clojure.core/name color) " mana is 0"))
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color)]
        (is (= 1 (get (q/get-mana-pool db'' :player-1) color))
            (str (clojure.core/name color) " mana should be added to pool"))
        (is (true? (:object/tapped (q/get-object db'' obj-id)))
            (str name " should be tapped after activation"))))))


;; =====================================================
;; D. Cycling
;; =====================================================

;; Oracle: "Cycling {COLOR} ({COLOR}, Discard this card: Draw a card.)"
(deftest cycling-lands-cycle-from-hand-test
  (doseq [{:keys [card-id color cycling-cost name]} cycling-land-specs]
    (testing (str "Cycling " name " from hand: pay cost, discard, draw 1")
      (let [db (th/create-test-db {:mana cycling-cost})
            [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
            [db obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
            result (cycling/cycle-card db :player-1 obj-id)
            db (:db result)]
        (is (= :graveyard (:object/zone (q/get-object db obj-id)))
            "Cycled card should be in graveyard")
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card (net 0: discarded 1, drew 1)")
        (is (= 0 (get (q/get-mana-pool db :player-1) color))
            "Cycling cost should be paid")))))


;; =====================================================
;; E. Cannot-Activate Guards
;; =====================================================

;; Oracle: Mana ability requires tapping — must be on battlefield and untapped
(deftest cycling-lands-cannot-activate-from-hand-test
  (doseq [{:keys [card-id color name]} cycling-land-specs]
    (testing (str name " in hand cannot activate mana ability")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color)]
        (is (= 0 (get (q/get-mana-pool db'' :player-1) color))
            "Mana should NOT be added (card not on battlefield)")
        (is (= :hand (th/get-object-zone db'' obj-id))
            (str name " should remain in hand"))))))


(deftest cycling-lands-cannot-activate-from-graveyard-test
  (doseq [{:keys [card-id color name]} cycling-land-specs]
    (testing (str name " in graveyard cannot activate mana ability")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :graveyard :player-1)
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color)]
        (is (= 0 (get (q/get-mana-pool db'' :player-1) color))
            "Mana should NOT be added (card in graveyard)")))))


(deftest cycling-lands-cannot-activate-when-tapped-test
  (doseq [{:keys [card-id color name]} cycling-land-specs]
    (testing (str name " already tapped cannot activate again")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            db-tap1 (engine-mana/activate-mana-ability db' :player-1 obj-id color)
            _ (is (= 1 (get (q/get-mana-pool db-tap1 :player-1) color))
                  "Precondition: first tap adds mana")
            db-tap2 (engine-mana/activate-mana-ability db-tap1 :player-1 obj-id color)]
        (is (= 1 (get (q/get-mana-pool db-tap2 :player-1) color))
            "Second tap should NOT add more mana")))))


;; =====================================================
;; F. Cannot-Cycle Guards
;; =====================================================

;; Oracle: Cycling requires card to be in hand
(deftest cycling-lands-cannot-cycle-without-mana-test
  (doseq [{:keys [card-id name]} cycling-land-specs]
    (testing (str "Cannot cycle " name " without sufficient mana")
      (let [db (th/create-test-db)
            [db obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            result (cycling/cycle-card db :player-1 obj-id)
            result-db (:db result)]
        (is (= :hand (:object/zone (q/get-object result-db obj-id)))
            "Card should remain in hand when cycling fails")))))


(deftest cycling-lands-cannot-cycle-from-battlefield-test
  (doseq [{:keys [card-id cycling-cost name]} cycling-land-specs]
    (testing (str "Cannot cycle " name " from battlefield")
      (let [db (th/create-test-db {:mana cycling-cost})
            [db obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            result (cycling/cycle-card db :player-1 obj-id)
            result-db (:db result)]
        (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
            "Card should remain on battlefield when cycling fails")))))


(deftest cycling-lands-cannot-cycle-from-graveyard-test
  (doseq [{:keys [card-id cycling-cost name]} cycling-land-specs]
    (testing (str "Cannot cycle " name " from graveyard")
      (let [db (th/create-test-db {:mana cycling-cost})
            [db obj-id] (th/add-card-to-zone db card-id :graveyard :player-1)
            result (cycling/cycle-card db :player-1 obj-id)
            result-db (:db result)]
        (is (= :graveyard (:object/zone (q/get-object result-db obj-id)))
            "Card should remain in graveyard when cycling fails")))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

;; Oracle: "Cycling {COLOR} ({COLOR}, Discard this card: Draw a card.)"
;; Edge case: cycling with empty library still discards the card
(deftest cycling-lands-cycle-with-empty-library-test
  (testing "Cycling with empty library discards but draws nothing"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :barren-moor :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (cycling/cycle-card db :player-1 obj-id)
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Cycled card should be in graveyard")
      (is (= 0 (th/get-hand-count db :player-1))
          "Hand should be empty (discarded 1, drew 0 from empty library)"))))


;; Oracle: "This land enters tapped." — cannot tap for mana immediately after playing
(deftest cycling-lands-cannot-tap-immediately-after-playing-test
  (doseq [{:keys [card-id color name]} cycling-land-specs]
    (testing (str name " cannot tap for mana immediately after playing (enters tapped)")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            db-played (lands/play-land db' :player-1 obj-id)
            _ (is (true? (:object/tapped (q/get-object db-played obj-id)))
                  "Precondition: land is tapped after entering")
            db-tap (engine-mana/activate-mana-ability db-played :player-1 obj-id color)]
        (is (= 0 (get (q/get-mana-pool db-tap :player-1) color))
            (str name " should NOT produce mana when tapped"))))))


;; Oracle: Cycling does not count as casting a spell (no storm increment)
(deftest cycling-lands-cycling-does-not-increment-storm-test
  (testing "Cycling does not increment storm count"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :barren-moor :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)) "Storm count should start at 0")
          result (cycling/cycle-card db :player-1 obj-id)
          db (:db result)]
      (is (= 0 (q/get-storm-count db :player-1))
          "Storm count should remain 0 after cycling"))))
