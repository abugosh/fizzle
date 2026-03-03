(ns fizzle.cards.white.abeyance-test
  "Tests for Abeyance card.

   Abeyance: 1W - Instant
   Until end of turn, target player can't cast instant or sorcery spells,
   and that player can't activate abilities that aren't mana abilities.
   Draw a card.

   Rulings:
   - (2008-08-01): Can't be used as a counterspell. No effect on spells already on stack.
   - (2004-10-04): Prohibits activated abilities of cards not on the battlefield.
   - (2004-10-04): Never prevents mana abilities from being activated.
   - (2004-10-04): Does not affect triggered abilities, static abilities, or combat."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.white.abeyance :as abeyance]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Abeyance" — verified against Scryfall
(deftest abeyance-card-definition-test
  (testing "card has correct oracle properties"
    (let [card abeyance/card]
      (is (= :abeyance (:card/id card))
          "Card ID should be :abeyance")
      (is (= "Abeyance" (:card/name card))
          "Card name should match oracle")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 1 :white 1} (:card/mana-cost card))
          "Mana cost should be {1}{W}")
      (is (= #{:white} (:card/colors card))
          "Card should be white")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")))

  (testing "card has correct targeting"
    (let [targeting (:card/targeting abeyance/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :player (:target/id req))
            "Target ID should be :player")
        (is (= :player (:target/type req))
            "Target type should be :player")
        (is (contains? (:target/options req) :any-player)
            "Should allow targeting any player")
        (is (true? (:target/required req))
            "Target should be required"))))

  ;; Oracle: "can't cast instant or sorcery spells" + "can't activate abilities
  ;;          that aren't mana abilities" + "Draw a card."
  (testing "card has three effects: two restrictions + draw"
    (let [effects (:card/effects abeyance/card)]
      (is (= 3 (count effects))
          "Should have exactly three effects")
      (let [[restrict-cast restrict-ability draw] effects]
        (is (= :add-restriction (:effect/type restrict-cast))
            "First effect should add restriction")
        (is (= :cannot-cast-instants-sorceries (:restriction/type restrict-cast))
            "First restriction should block instants/sorceries")
        (is (= :targeted-player (:effect/target restrict-cast))
            "First restriction targets targeted player")
        (is (= :add-restriction (:effect/type restrict-ability))
            "Second effect should add restriction")
        (is (= :cannot-activate-non-mana-abilities (:restriction/type restrict-ability))
            "Second restriction should block non-mana abilities")
        (is (= :targeted-player (:effect/target restrict-ability))
            "Second restriction targets targeted player")
        (is (= :draw (:effect/type draw))
            "Third effect should be draw")
        (is (= 1 (:effect/amount draw))
            "Should draw exactly 1 card")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Until end of turn, target player can't cast instant or sorcery spells"
(deftest abeyance-cast-resolve-applies-restrictions-test
  (testing "Casting and resolving Abeyance applies both restrictions and draws a card"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :abeyance :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :white 1})
          ;; Add library cards so draw doesn't trigger loss
          [db-with-lib _] (th/add-cards-to-library db-with-mana [:dark-ritual :dark-ritual] :player-1)
          hand-before (th/get-hand-count db-with-lib :player-1)
          ;; Cast with targeting flow via production helper
          db-cast (th/cast-with-target db-with-lib :player-1 obj-id :player-2)
          _ (is (= :stack (:object/zone (q/get-object db-cast obj-id)))
                "Precondition: Abeyance on stack")
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Spell should be in graveyard
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Abeyance should be in graveyard after resolution")
      ;; Player-2 should have both restriction grants
      (let [p2-grants (grants/get-player-grants db-resolved :player-2)]
        (is (= 2 (count p2-grants))
            "Player-2 should have two grants")
        (is (= #{:cannot-cast-instants-sorceries :cannot-activate-non-mana-abilities}
               (set (map #(get-in % [:grant/data :restriction/type]) p2-grants)))
            "Player-2 should have both restriction types"))
      ;; Caster should have drawn a card
      ;; hand-before counts Abeyance which is now on stack/graveyard, so -1 + 1 draw = same
      ;; Actually cast removes from hand, so hand is (hand-before - 1), then draw adds 1
      (is (= hand-before (th/get-hand-count db-resolved :player-1))
          "Caster should have drawn a card (net: -1 cast +1 draw = same)"))))


;; === C. Cannot-Cast Guards ===

(deftest abeyance-cannot-cast-without-mana-test
  (testing "Cannot cast Abeyance without sufficient mana"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [abeyance/card])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :abeyance :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be able to cast without mana"))))


(deftest abeyance-cannot-cast-wrong-zone-test
  (testing "Cannot cast Abeyance from graveyard (no flashback)"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [abeyance/card])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :abeyance :graveyard :player-1)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :white 1})]
      (is (false? (rules/can-cast? db-with-mana :player-1 obj-id))
          "Should not be able to cast from graveyard"))))


;; === D. Storm Count ===

(deftest abeyance-increments-storm-count-test
  (testing "Casting Abeyance increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :abeyance :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :white 1})
          storm-before (q/get-storm-count db-with-mana :player-1)
          db-cast (th/cast-with-target db-with-mana :player-1 obj-id :player-2)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Restriction Enforcement Tests ===

;; Oracle: "target player can't cast instant or sorcery spells"
(deftest abeyance-restriction-blocks-instant-test
  (testing "Player with cannot-cast-instants-sorceries restriction cannot cast instants"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add an instant to player-2's hand
          [db spell-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          ;; Give player-2 mana
          db (mana/add-mana db :player-2 {:black 1})
          ;; Verify CAN cast before restriction
          _ (is (true? (rules/can-cast? db :player-2 spell-id))
                "Precondition: player-2 can cast Dark Ritual before restriction")
          ;; Apply restriction
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-cast-instants-sorceries}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})]
      (is (false? (rules/can-cast? db-restricted :player-2 spell-id))
          "Player-2 should not be able to cast instant with restriction"))))


;; Oracle: "target player can't cast instant or sorcery spells"
(deftest abeyance-restriction-blocks-sorcery-test
  (testing "Player with cannot-cast-instants-sorceries restriction cannot cast sorceries"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Careful Study is a sorcery
          [db spell-id] (th/add-card-to-zone db :careful-study :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          _ (is (true? (rules/can-cast? db :player-2 spell-id))
                "Precondition: player-2 can cast Careful Study before restriction")
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-cast-instants-sorceries}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})]
      (is (false? (rules/can-cast? db-restricted :player-2 spell-id))
          "Player-2 should not be able to cast sorcery with restriction"))))


;; Oracle: "can't cast instant or sorcery spells" (lands are NOT instants/sorceries)
(deftest abeyance-restriction-allows-lands-test
  (testing "Player with cannot-cast-instants-sorceries can still play lands"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db land-id] (th/add-card-to-zone db :island :hand :player-2)
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-cast-instants-sorceries}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})]
      (is (true? (rules/can-play-land? db-restricted :player-2 land-id))
          "Player should still be able to play lands under Abeyance"))))


;; Oracle: "can't cast instant or sorcery spells" (artifacts are NOT instants/sorceries)
(deftest abeyance-restriction-allows-non-instant-sorcery-test
  (testing "Player with cannot-cast-instants-sorceries can still cast artifacts"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db art-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)
          db (mana/add-mana db :player-2 {:colorless 0})
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-cast-instants-sorceries}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})]
      ;; Lotus Petal is an artifact with CMC 0 — should still be castable
      (is (true? (rules/can-cast? db-restricted :player-2 art-id))
          "Player should still be able to cast artifacts under Abeyance"))))


;; === F. Ability Restriction Tests ===

;; Oracle: "can't activate abilities that aren't mana abilities"
;; Ruling (2004-10-04): "Abeyance never prevents mana abilities from being activated."
(deftest abeyance-restriction-allows-mana-abilities-test
  (testing "Player with cannot-activate-non-mana-abilities can still activate mana abilities"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add an Island (mana ability: tap for blue)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-2)
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-activate-non-mana-abilities}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})
          ;; Activate mana ability
          db-after (ability-events/activate-mana-ability db-restricted :player-2 island-id :blue)]
      (is (= 1 (:blue (q/get-mana-pool db-after :player-2)))
          "Mana ability should still work under Abeyance"))))


;; Oracle: "can't activate abilities that aren't mana abilities"
(deftest abeyance-restriction-blocks-activated-abilities-test
  (testing "Player with cannot-activate-non-mana-abilities cannot activate non-mana abilities"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Seal of Cleansing has an activated ability (sacrifice to destroy)
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-2)
          ;; Add a target for the ability
          [db _target-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-activate-non-mana-abilities}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})
          ;; Try to activate Seal of Cleansing's ability
          result (ability-events/activate-ability db-restricted :player-2 seal-id 0)]
      ;; Seal should still be on battlefield (activation blocked)
      (is (= :battlefield (th/get-object-zone (:db result) seal-id))
          "Seal of Cleansing should still be on battlefield (activation blocked)")
      (is (nil? (:pending-selection result))
          "Should not enter targeting selection"))))


;; === G. Edge Cases ===

;; Oracle: target can be any player (including self)
(deftest abeyance-can-target-self-test
  (testing "Abeyance can target self (common play: draw a card)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :abeyance :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :white 1})
          [db-with-lib _] (th/add-cards-to-library db-with-mana [:dark-ritual] :player-1)
          ;; Target SELF via production helper
          db-cast (th/cast-with-target db-with-lib :player-1 obj-id :player-1)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Player-1 should have the restrictions (targeting self)
      (let [p1-grants (grants/get-player-grants db-resolved :player-1)]
        (is (= 2 (count p1-grants))
            "Player-1 should have two restriction grants when targeting self")))))


;; Oracle: "Draw a card" (draw happens even when targeting self)
(deftest abeyance-draw-with-empty-library-test
  (testing "Abeyance draw triggers loss condition on empty library"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :abeyance :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :white 1})
          ;; NO library cards — draw should trigger loss
          db-cast (th/cast-with-target db-with-mana :player-1 obj-id :player-2)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Restrictions should still be applied (effects execute sequentially)
      (let [p2-grants (grants/get-player-grants db-resolved :player-2)]
        (is (= 2 (count p2-grants))
            "Player-2 should have restrictions even when draw triggers loss"))
      ;; Loss condition set from empty library draw (via SBA)
      (let [db-after-sba (sba/check-and-execute-sbas db-resolved)]
        (is (= :empty-library (:game/loss-condition (q/get-game-state db-after-sba)))
            "Should have loss condition from drawing on empty library")))))


;; Ruling (2008-08-01): "will have no effect on spells which were on the stack"
(deftest abeyance-does-not-affect-spells-on-stack-test
  (testing "Abeyance restriction does not retroactively affect spells already on stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Player-2 has an instant on the stack already
          [db _spell-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          _ (is (false? (q/stack-empty? db))
                "Precondition: stack is not empty")
          ;; Applying restriction doesn't remove existing stack items
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-cast-instants-sorceries}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})]
      (is (false? (q/stack-empty? db-restricted))
          "Spells on stack should remain (restriction is not a counterspell)"))))


;; Stacking with Orim's Chant: both restrictions coexist
(deftest abeyance-stacks-with-cannot-cast-spells-test
  (testing "cannot-cast-instants-sorceries coexists with cannot-cast-spells"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db spell-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; Apply both restrictions
          db (grants/add-player-grant db :player-2
                                      {:grant/type :restriction
                                       :grant/data {:restriction/type :cannot-cast-spells}
                                       :grant/expires {:expires/turn 1 :expires/phase :cleanup}})
          db (grants/add-player-grant db :player-2
                                      {:grant/type :restriction
                                       :grant/data {:restriction/type :cannot-cast-instants-sorceries}
                                       :grant/expires {:expires/turn 1 :expires/phase :cleanup}})]
      (is (false? (rules/can-cast? db :player-2 spell-id))
          "Player should not be able to cast with both restrictions"))))


;; Targeting test: finds valid targets
(deftest abeyance-has-valid-targets-test
  (testing "has-valid-targets? returns true when opponent exists"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [abeyance/card])
          db (th/add-opponent @conn)]
      (is (targeting/has-valid-targets? db :player-1 abeyance/card)
          "Should have valid targets (can target self or opponent)"))))
