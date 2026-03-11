(ns fizzle.cards.blue.turnabout-test
  "Tests for Turnabout.

   Turnabout: {2}{U}{U} - Instant
   Choose artifact, creature, or land. Tap all untapped permanents of the
   chosen type target player controls, or untap all tapped permanents of
   that type that player controls.

   Test categories:
   A. Card definition — all 6 modes with exact values
   B. Cast-resolve happy path — each of the 6 modes works
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   E. Player targeting — can target self or opponent
   F. Edge cases — no permanents of type, mixed tapped/untapped"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.turnabout :as turnabout]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest test-turnabout-card-definition
  (testing "Turnabout identity and core fields"
    (is (= :turnabout (:card/id turnabout/card))
        "Card id should be :turnabout")
    (is (= "Turnabout" (:card/name turnabout/card))
        "Card name should be 'Turnabout'")
    (is (= 4 (:card/cmc turnabout/card))
        "CMC should be 4")
    (is (= {:colorless 2 :blue 2} (:card/mana-cost turnabout/card))
        "Mana cost should be {2}{U}{U}")
    (is (= #{:blue} (:card/colors turnabout/card))
        "Colors should be #{:blue}")
    (is (= #{:instant} (:card/types turnabout/card))
        "Should be an instant"))

  (testing "Turnabout has exactly 6 modes"
    (let [modes (:card/modes turnabout/card)]
      (is (= 6 (count modes))
          "Should have exactly 6 modes (tap/untap × artifact/creature/land)")))

  (testing "Turnabout modes cover all 6 combinations"
    (let [modes (:card/modes turnabout/card)
          mode-ids (set (map :mode/id modes))]
      (is (contains? mode-ids :tap-artifacts) "Should have tap-artifacts mode")
      (is (contains? mode-ids :untap-artifacts) "Should have untap-artifacts mode")
      (is (contains? mode-ids :tap-creatures) "Should have tap-creatures mode")
      (is (contains? mode-ids :untap-creatures) "Should have untap-creatures mode")
      (is (contains? mode-ids :tap-lands) "Should have tap-lands mode")
      (is (contains? mode-ids :untap-lands-all) "Should have untap-lands-all mode")))

  (testing "Each mode has player targeting and correct effect"
    (let [modes (:card/modes turnabout/card)]
      (doseq [mode modes]
        (let [targeting (:mode/targeting mode)
              effects (:mode/effects mode)]
          (is (= 1 (count targeting)) "Each mode should have exactly 1 targeting req")
          (is (= :player (:target/type (first targeting))) "Each mode targets a player")
          (is (= 1 (count effects)) "Each mode should have exactly 1 effect")))
      ;; Tap modes should use :tap-all
      (doseq [mode (filter #(contains? #{:tap-artifacts :tap-creatures :tap-lands}
                                       (:mode/id %))
                           modes)]
        (is (= :tap-all (:effect/type (first (:mode/effects mode))))
            (str (:mode/id mode) " should use :tap-all effect")))
      ;; Untap modes should use :untap-all
      (doseq [mode (filter #(contains? #{:untap-artifacts :untap-creatures :untap-lands-all}
                                       (:mode/id %))
                           modes)]
        (is (= :untap-all (:effect/type (first (:mode/effects mode))))
            (str (:mode/id mode) " should use :untap-all effect"))))))


;; =====================================================
;; B. Cast-Resolve Happy Path (all 6 modes)
;; =====================================================

(defn- get-mode
  [card mode-id]
  (first (filter #(= mode-id (:mode/id %)) (:card/modes card))))


(deftest test-turnabout-tap-all-artifacts
  (testing "Tap-artifacts mode taps all untapped artifacts controlled by target player"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          ;; Add untapped artifacts for opponent
          [db art-1] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db art-2] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          ;; Already tapped artifact (should remain tapped)
          [db art-tapped] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          db (th/tap-permanent db art-tapped)
          ;; Player 1's artifact (should NOT be affected)
          [db p1-art] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          mode (get-mode turnabout/card :tap-artifacts)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      ;; Opponent's untapped artifacts should now be tapped
      (is (true? (:object/tapped (q/get-object db art-1)))
          "Opponent's first artifact should be tapped")
      (is (true? (:object/tapped (q/get-object db art-2)))
          "Opponent's second artifact should be tapped")
      ;; Already-tapped artifact should remain tapped
      (is (true? (:object/tapped (q/get-object db art-tapped)))
          "Already-tapped artifact should remain tapped")
      ;; Player 1's artifact should NOT be tapped
      (is (false? (:object/tapped (q/get-object db p1-art)))
          "Caster's own artifact should NOT be tapped"))))


(deftest test-turnabout-untap-all-artifacts
  (testing "Untap-artifacts mode untaps all tapped artifacts controlled by target player"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db art-1] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db art-2] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          ;; Untapped artifact (should remain untapped)
          [db art-untapped] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          db (th/tap-permanent db art-1)
          db (th/tap-permanent db art-2)
          mode (get-mode turnabout/card :untap-artifacts)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      (is (false? (:object/tapped (q/get-object db art-1)))
          "First tapped artifact should be untapped")
      (is (false? (:object/tapped (q/get-object db art-2)))
          "Second tapped artifact should be untapped")
      (is (false? (:object/tapped (q/get-object db art-untapped)))
          "Already-untapped artifact should remain untapped"))))


(deftest test-turnabout-tap-all-creatures
  (testing "Tap-creatures mode taps all untapped creatures controlled by target player"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db creature-1] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-2)
          [db creature-2] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-2)
          mode (get-mode turnabout/card :tap-creatures)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      (is (true? (:object/tapped (q/get-object db creature-1)))
          "Opponent's first creature should be tapped")
      (is (true? (:object/tapped (q/get-object db creature-2)))
          "Opponent's second creature should be tapped"))))


(deftest test-turnabout-untap-all-creatures
  (testing "Untap-creatures mode untaps all tapped creatures controlled by target player"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db creature-1] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-2)
          [db creature-2] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-2)
          db (th/tap-permanent db creature-1)
          db (th/tap-permanent db creature-2)
          mode (get-mode turnabout/card :untap-creatures)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      (is (false? (:object/tapped (q/get-object db creature-1)))
          "Opponent's first creature should be untapped")
      (is (false? (:object/tapped (q/get-object db creature-2)))
          "Opponent's second creature should be untapped"))))


(deftest test-turnabout-tap-all-lands
  (testing "Tap-lands mode taps all untapped lands controlled by target player"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db land-1] (th/add-card-to-zone db :island :battlefield :player-2)
          [db land-2] (th/add-card-to-zone db :island :battlefield :player-2)
          ;; Caster's lands should NOT be affected
          [db p1-land] (th/add-card-to-zone db :island :battlefield :player-1)
          mode (get-mode turnabout/card :tap-lands)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      (is (true? (:object/tapped (q/get-object db land-1)))
          "Opponent's first land should be tapped")
      (is (true? (:object/tapped (q/get-object db land-2)))
          "Opponent's second land should be tapped")
      (is (false? (:object/tapped (q/get-object db p1-land)))
          "Caster's own land should NOT be tapped"))))


(deftest test-turnabout-untap-all-lands
  (testing "Untap-lands-all mode untaps all tapped lands controlled by target player"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db land-1] (th/add-card-to-zone db :island :battlefield :player-2)
          [db land-2] (th/add-card-to-zone db :island :battlefield :player-2)
          db (th/tap-permanent db land-1)
          db (th/tap-permanent db land-2)
          mode (get-mode turnabout/card :untap-lands-all)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      (is (false? (:object/tapped (q/get-object db land-1)))
          "Opponent's first land should be untapped")
      (is (false? (:object/tapped (q/get-object db land-2)))
          "Opponent's second land should be untapped"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest test-turnabout-cannot-cast-without-mana
  (testing "Cannot cast Turnabout without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :turnabout :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana")))

  (testing "Cannot cast Turnabout with only 3 mana (needs 4)"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 2}})
          [db obj-id] (th/add-card-to-zone db :turnabout :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 3 mana"))))


(deftest test-turnabout-cannot-cast-from-graveyard
  (testing "Cannot cast Turnabout from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          [db obj-id] (th/add-card-to-zone db :turnabout :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest test-turnabout-increments-storm-count
  (testing "Casting Turnabout increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          mode (get-mode turnabout/card :tap-lands)]
      (is (= 0 (q/get-storm-count db :player-1)) "Storm count should start at 0")
      (let [db-cast (th/cast-mode-with-target db :player-1 tb-id mode :player-2)]
        (is (= 1 (q/get-storm-count db-cast :player-1))
            "Storm count should be 1 after casting")))))


;; =====================================================
;; E. Player Targeting
;; =====================================================

(deftest test-turnabout-can-target-self
  (testing "Turnabout can target self (tap own lands)"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db land-1] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land-2] (th/add-card-to-zone db :island :battlefield :player-1)
          mode (get-mode turnabout/card :tap-lands)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-1)
          {:keys [db]} (th/resolve-top db)]
      (is (true? (:object/tapped (q/get-object db land-1)))
          "Caster's own land should be tapped when self-targeting")
      (is (true? (:object/tapped (q/get-object db land-2)))
          "Caster's second land should be tapped when self-targeting"))))


(deftest test-turnabout-untap-own-lands
  (testing "Turnabout untap-lands-all targeting self untaps own tapped lands"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db land-1] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land-2] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db land-1)
          db (th/tap-permanent db land-2)
          mode (get-mode turnabout/card :untap-lands-all)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-1)
          {:keys [db]} (th/resolve-top db)]
      (is (false? (:object/tapped (q/get-object db land-1)))
          "Own tapped land should be untapped")
      (is (false? (:object/tapped (q/get-object db land-2)))
          "Own second tapped land should be untapped"))))


;; =====================================================
;; F. Edge Cases
;; =====================================================

(deftest test-turnabout-no-permanents-of-type
  (testing "Tap-all with no untapped permanents of type is a no-op"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          ;; Opponent has no artifacts
          mode (get-mode turnabout/card :tap-artifacts)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      ;; Should resolve without error
      (is (= :graveyard (:object/zone (q/get-object db tb-id)))
          "Turnabout should resolve to graveyard even with no targets"))))


(deftest test-turnabout-tap-all-only-affects-untapped
  (testing "Tap-all only taps untapped permanents, not already-tapped ones"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db land-untapped] (th/add-card-to-zone db :island :battlefield :player-2)
          [db land-tapped] (th/add-card-to-zone db :island :battlefield :player-2)
          db (th/tap-permanent db land-tapped)
          mode (get-mode turnabout/card :tap-lands)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      ;; Untapped land should now be tapped
      (is (true? (:object/tapped (q/get-object db land-untapped)))
          "Untapped land should be tapped by tap-all")
      ;; Already-tapped land should remain tapped (not double-tapped issue)
      (is (true? (:object/tapped (q/get-object db land-tapped)))
          "Already-tapped land should still be tapped"))))


(deftest test-turnabout-untap-all-only-affects-tapped
  (testing "Untap-all only untaps tapped permanents, not already-untapped ones"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 2}})
          db (th/add-opponent db)
          [db tb-id] (th/add-card-to-zone db :turnabout :hand :player-1)
          [db land-tapped] (th/add-card-to-zone db :island :battlefield :player-2)
          [db land-untapped] (th/add-card-to-zone db :island :battlefield :player-2)
          db (th/tap-permanent db land-tapped)
          mode (get-mode turnabout/card :untap-lands-all)
          db (th/cast-mode-with-target db :player-1 tb-id mode :player-2)
          {:keys [db]} (th/resolve-top db)]
      (is (false? (:object/tapped (q/get-object db land-tapped)))
          "Tapped land should be untapped by untap-all")
      (is (false? (:object/tapped (q/get-object db land-untapped)))
          "Already-untapped land should remain untapped"))))
