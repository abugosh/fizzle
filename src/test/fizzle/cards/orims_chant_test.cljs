(ns fizzle.cards.orims-chant-test
  "Tests for Orim's Chant card.

   Orim's Chant: W - Instant
   Kicker {W}
   Target player can't cast spells this turn.
   If this spell was kicked, creatures can't attack this turn.

   This tests:
   - Card definition (type, cost, kicker, targeting, effects)
   - Kicker mode generation (primary and kicked modes)
   - Mode costs (base and combined)
   - Effect definitions (cannot-cast-spells, cannot-attack)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.cards.orims-chant :as orims-chant]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]))


;; === Test Helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions plus Orim's Chant
    (d/transact! conn (conj cards/all-cards orims-chant/orims-chant))
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}
                       {:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/is-opponent true
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact game state
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   Returns [object-id db] tuple."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [obj-id @conn]))


;; === Card Definition Tests ===

(deftest card-properties-test
  ;; Bug caught: Incorrect oracle properties break card behavior
  (testing "card has correct oracle properties"
    (let [card orims-chant/orims-chant]
      (is (= :orims-chant (:card/id card))
          "Card ID should be :orims-chant")
      (is (= "Orim's Chant" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:white 1} (:card/mana-cost card))
          "Mana cost should be {W}")
      (is (= #{:white} (:card/colors card))
          "Card should be white")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant"))))


(deftest kicker-cost-test
  ;; Bug caught: Missing kicker breaks kicked mode generation in get-casting-modes
  (testing "card has kicker cost defined"
    (let [card orims-chant/orims-chant]
      (is (= {:white 1} (:card/kicker card))
          "Kicker cost should be {W}"))))


(deftest targeting-format-test
  ;; Bug caught: Wrong attribute (:card/targets vs :card/targeting) breaks targeting system
  (testing "card uses correct targeting format for targeting system"
    (let [card orims-chant/orims-chant
          targeting (:card/targeting card)]
      (is (vector? targeting)
          "Targeting must be a vector for targeting system")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :player (:target/id req))
            "Target ID should be :player")
        (is (= :player (:target/type req))
            "Target type should be :player")
        (is (contains? (:target/options req) :any-player)
            "Should allow targeting any player")
        (is (:target/required req)
            "Target should be required")))))


(deftest base-effects-test
  ;; Bug caught: Missing restriction type in base effects breaks cannot-cast enforcement
  (testing "base effects add cannot-cast-spells restriction"
    (let [card orims-chant/orims-chant
          effects (:card/effects card)]
      (is (= 1 (count effects))
          "Should have exactly one base effect")
      (let [effect (first effects)]
        (is (= :add-restriction (:effect/type effect))
            "Effect type should be :add-restriction")
        (is (= :targeted-player (:effect/target effect))
            "Effect target should be :targeted-player")
        (is (= :cannot-cast-spells (:restriction/type effect))
            "Restriction type should be :cannot-cast-spells")))))


(deftest kicked-effects-test
  ;; Bug caught: Kicked effects missing one restriction (replace semantics)
  (testing "kicked effects add both restrictions"
    (let [card orims-chant/orims-chant
          effects (:card/kicked-effects card)]
      (is (= 2 (count effects))
          "Should have two kicked effects")
      (is (= #{:cannot-cast-spells :cannot-attack}
             (set (map :restriction/type effects)))
          "Should include both restriction types"))))


;; === Kicker Mode Tests ===

(deftest casting-modes-test
  ;; Bug caught: Kicker mode not generated when card in hand
  (testing "get-casting-modes returns primary and kicked modes"
    (let [db (create-test-db)
          [obj-id db] (add-card-to-zone db :orims-chant :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)]
      (is (= 2 (count modes))
          "Should return 2 casting modes")
      (is (some #(= :primary (:mode/id %)) modes)
          "Should include primary mode")
      (is (some #(= :kicked (:mode/id %)) modes)
          "Should include kicked mode"))))


(deftest primary-mode-cost-test
  ;; Bug caught: Primary mode has wrong cost (using kicked cost instead)
  (testing "primary mode costs {W}"
    (let [db (create-test-db)
          [obj-id db] (add-card-to-zone db :orims-chant :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary (first (filter #(= :primary (:mode/id %)) modes))]
      (is (= {:white 1} (:mode/mana-cost primary))
          "Primary mode should cost {W}"))))


(deftest kicked-mode-cost-test
  ;; Bug caught: Kicked mode cost not merged correctly (base + kicker)
  (testing "kicked mode costs {WW}"
    (let [db (create-test-db)
          [obj-id db] (add-card-to-zone db :orims-chant :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          kicked (first (filter #(= :kicked (:mode/id %)) modes))]
      (is (= {:white 2} (:mode/mana-cost kicked))
          "Kicked mode should cost {WW} (base {W} + kicker {W})"))))


(deftest kicked-mode-effects-test
  ;; Bug caught: Kicked effects not attached to mode
  (testing "kicked mode carries kicked effects"
    (let [db (create-test-db)
          [obj-id db] (add-card-to-zone db :orims-chant :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          kicked (first (filter #(= :kicked (:mode/id %)) modes))]
      (is (seq (:mode/effects kicked))
          "Kicked mode should have effects")
      (is (= 2 (count (:mode/effects kicked)))
          "Kicked mode should have 2 effects"))))


;; === Targeting System Integration Tests ===

(deftest has-valid-targets-test
  ;; Bug caught: Targeting system can't find player targets
  (testing "has-valid-targets? returns true when opponent exists"
    (let [db (create-test-db)]
      (is (targeting/has-valid-targets? db :player-1 orims-chant/orims-chant)
          "Should have valid targets (can target self or opponent)"))))


(deftest find-valid-targets-test
  ;; Bug caught: Target options format wrong (vector vs set)
  (testing "find-valid-targets returns both players with :any-player"
    (let [db (create-test-db)
          req (first (:card/targeting orims-chant/orims-chant))
          targets (targeting/find-valid-targets db :player-1 req)]
      (is (= 2 (count targets))
          "Should find 2 valid targets (self and opponent)")
      (is (contains? (set targets) :player-1)
          "Should include self as valid target")
      (is (contains? (set targets) :player-2)
          "Should include opponent as valid target"))))


(deftest goldfish-mode-targets-test
  ;; Bug caught: Can't cast in goldfish mode (no opponent)
  (testing "has-valid-targets? returns true even without opponent (can target self)"
    (let [conn (d/create-conn schema)
          ;; Only create player, no opponent
          _ (d/transact! conn (conj cards/all-cards orims-chant/orims-chant))
          _ (d/transact! conn [{:player/id :player-1
                                :player/name "Player"
                                :player/life 20
                                :player/mana-pool {}
                                :player/storm-count 0
                                :player/land-plays-left 1}])
          db @conn]
      ;; Even without opponent, can target self
      (is (targeting/has-valid-targets? db :player-1 orims-chant/orims-chant)
          "Should have valid targets (can target self even in goldfish)"))))
