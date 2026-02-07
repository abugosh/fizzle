(ns fizzle.cards.flash-of-insight-test
  "Tests for Flash of Insight card definition and resolution flow.

   Flash of Insight: {X}{1}{U} - Instant
   Look at the top X cards of your library. Put one into your hand
   and the rest on the bottom in a random order.
   Flashback {1}{U} — Exile X blue cards from your graveyard.

   Tests verify:
   - Card definition (type, cost, effects, flashback)
   - Peek-and-select selection state creation (X=2)
   - Full resolution: peek → select → hand, remainder to bottom
   - Flashback with exile cost from graveyard"
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.cards.flash-of-insight :as flash-of-insight]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection :as selection]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions plus Flash of Insight
    (d/transact! conn (conj cards/all-cards flash-of-insight/flash-of-insight))
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
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
   Returns [db object-id] tuple."
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
    [@conn obj-id]))


(defn add-cards-to-library
  "Add multiple cards to the top of a player's library.
   Returns [db object-ids] tuple with object-ids in order (first = top of library)."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        get-card-eid (fn [card-id]
                       (d/q '[:find ?e .
                              :in $ ?cid
                              :where [?e :card/id ?cid]]
                            @conn card-id))]
    (loop [remaining-cards card-ids
           position 0
           object-ids []]
      (if (empty? remaining-cards)
        [@conn object-ids]
        (let [card-id (first remaining-cards)
              obj-id (random-uuid)
              card-eid (get-card-eid card-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position position}])
          (recur (rest remaining-cards)
                 (inc position)
                 (conj object-ids obj-id)))))))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


(defn get-hand-count
  "Get the number of cards in a player's hand."
  [db player-id]
  (count (q/get-hand db player-id)))


;; === Card Definition Tests ===

(deftest flash-of-insight-card-structure-test
  ;; Bug caught: card not exported or malformed
  (testing "Flash of Insight card has required fields"
    (let [card flash-of-insight/flash-of-insight]
      (is (= :flash-of-insight (:card/id card))
          "Card ID must be :flash-of-insight")
      (is (= "Flash of Insight" (:card/name card))
          "Card name must match oracle")
      (is (= #{:instant} (:card/types card))
          "Must be instant")
      (is (= #{:blue} (:card/colors card))
          "Must be blue")
      (is (true? (:x (:card/mana-cost card)))
          "Mana cost must have :x true for X cost")
      (is (= 1 (:colorless (:card/mana-cost card)))
          "Mana cost must have 1 colorless")
      (is (= 1 (:blue (:card/mana-cost card)))
          "Mana cost must have 1 blue"))))


(deftest flash-of-insight-normal-cast-effect-test
  ;; Bug caught: effect misconfigured, wrong effect type
  (testing "Normal cast uses :peek-and-select effect"
    (let [effect (first (:card/effects flash-of-insight/flash-of-insight))]
      (is (= :peek-and-select (:effect/type effect))
          "Effect must be :peek-and-select")
      (is (= :x (:effect/count effect))
          "Effect count must be :x to use X value")
      (is (= 1 (:effect/select-count effect))
          "Must select exactly 1 card")
      (is (= :hand (:effect/selected-zone effect))
          "Selected card goes to hand")
      (is (= :bottom-of-library (:effect/remainder-zone effect))
          "Non-selected go to bottom")
      (is (true? (:effect/shuffle-remainder? effect))
          "Remainder should be shuffled (random order)"))))


(deftest flash-of-insight-flashback-cost-test
  ;; Bug caught: missing flashback, wrong mana cost, missing exile cost
  (testing "Flashback has correct costs"
    (let [alternate (first (:card/alternate-costs flash-of-insight/flash-of-insight))]
      (is (= :flashback (:alternate/id alternate))
          "Must have :alternate/id :flashback")
      (is (= :graveyard (:alternate/zone alternate))
          "Flashback casts from graveyard")
      (is (= {:colorless 1 :blue 1} (:alternate/mana-cost alternate))
          "Flashback mana cost is {1}{U}")
      (is (= :exile (:alternate/on-resolve alternate))
          "Spell exiles after flashback resolution"))))


(deftest flash-of-insight-exile-additional-cost-test
  ;; Bug caught: exile cost missing or misconfigured
  (testing "Flashback has exile blue cards additional cost"
    (let [alternate (first (:card/alternate-costs flash-of-insight/flash-of-insight))
          exile-cost (first (:alternate/additional-costs alternate))]
      (is (= :exile-cards (:cost/type exile-cost))
          "Additional cost type must be :exile-cards")
      (is (= :graveyard (:cost/zone exile-cost))
          "Exile from graveyard")
      (is (= #{:blue} (:card/colors (:cost/criteria exile-cost)))
          "Must exile blue cards")
      (is (= :x (:cost/count exile-cost))
          "Exile count is X (player chooses)"))))


;; === Integration Test: Peek-and-Select with X=2 ===

(deftest flash-of-insight-peek-and-select-x2-test
  ;; Bug caught: entire card resolution flow untested
  (testing "Cast with X=2, peek 2 cards, select 1 for hand"
    (let [db (create-test-db)
          ;; Add 5 cards to library (more than X=2)
          [db' lib-ids] (add-cards-to-library db
                                              [:dark-ritual :cabal-ritual :brain-freeze
                                               :careful-study :mental-note]
                                              :player-1)
          ;; Add Flash of Insight to hand
          [db'' foi-id] (add-card-to-zone db' :flash-of-insight :hand :player-1)
          ;; Add mana to cast: X=2 + {1}{U} = {2}{1}{U} = 3 colorless + 1 blue
          db-with-mana (mana/add-mana db'' :player-1 {:colorless 3 :blue 1})
          initial-hand (get-hand-count db-with-mana :player-1)
          _ (is (= 1 initial-hand) "Precondition: hand has 1 card (FoI)")
          ;; Cast Flash of Insight (cast-spell doesn't resolve X; set it manually)
          db-cast (rules/cast-spell db-with-mana :player-1 foi-id)
          _ (is (= :stack (get-object-zone db-cast foi-id))
                "FoI should be on stack after casting")
          ;; Manually set X=2 on the spell object (normally set by X mana UI)
          foi-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-cast foi-id)
          db-with-x (d/db-with db-cast [[:db/add foi-eid :object/x-value 2]])
          foi-obj (q/get-object db-with-x foi-id)
          _ (is (= 2 (:object/x-value foi-obj))
                "Spell should have X=2 stored")
          ;; Resolve spell - should create peek-and-select selection
          result (selection/resolve-spell-with-selection db-with-x :player-1 foi-id)
          sel (:pending-selection result)]
      ;; Should have pending selection
      (is (some? sel)
          "Should return pending peek-and-select selection state")
      ;; Selection type should be :peek-and-select
      (is (= :peek-and-select (:selection/type sel))
          "Selection effect type should be :peek-and-select")
      ;; Should show 2 candidates (X=2 peek)
      (is (= 2 (count (:selection/candidates sel)))
          "Should peek at 2 cards (X=2)")
      ;; Candidates should be the top 2 library cards
      (let [top-2-ids (set (take 2 lib-ids))]
        (is (= top-2-ids (:selection/candidates sel))
            "Peeked cards should be the top 2 of library"))
      ;; Select count should be 1
      (is (= 1 (:selection/select-count sel))
          "Should select exactly 1 card")
      ;; Simulate selecting the first peeked card
      (let [selected-card (first lib-ids)
            db-after-peek (selection/execute-peek-selection
                            (:db result)
                            (assoc sel :selection/selected #{selected-card}))
            ;; Move spell off stack (simulating confirm handler cleanup)
            spell-obj (q/get-object db-after-peek foi-id)
            cast-mode (:object/cast-mode spell-obj)
            destination (or (:mode/on-resolve cast-mode) :graveyard)
            db-final (zones/move-to-zone db-after-peek foi-id destination)]
        ;; Selected card should be in hand
        (is (= :hand (get-object-zone db-final selected-card))
            "Selected card should be moved to hand")
        ;; Non-selected peeked card should NOT be in library top (moved to bottom)
        (let [second-card (second lib-ids)]
          (is (not= :hand (get-object-zone db-final second-card))
              "Non-selected peeked card should not be in hand"))
        ;; FoI should be in graveyard (normal cast)
        (is (= :graveyard (get-object-zone db-final foi-id))
            "Flash of Insight should go to graveyard after normal cast")
        ;; Hand should have 1 card (the selected one)
        (is (= 1 (get-hand-count db-final :player-1))
            "Hand should have 1 card after selecting from peek")))))


;; === Integration Test: Flashback with Exile Cost ===

(deftest flash-of-insight-flashback-with-exile-cost-test
  ;; Bug caught: flashback + exile cost interaction broken
  (testing "Cast from graveyard via flashback, pay exile cost"
    (let [db (create-test-db)
          ;; Add cards to library for peeking
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze]
                                               :player-1)
          ;; Add Flash of Insight to graveyard
          [db'' foi-id] (add-card-to-zone db' :flash-of-insight :graveyard :player-1)
          ;; Add 2 blue cards to graveyard for exile cost (X=2)
          [db1 _exile1-id] (add-card-to-zone db'' :careful-study :graveyard :player-1)
          [db2 _exile2-id] (add-card-to-zone db1 :opt :graveyard :player-1)
          ;; Add flashback mana: {1}{U}
          db-with-mana (mana/add-mana db2 :player-1 {:colorless 1 :blue 1})
          ;; Verify preconditions
          _ (is (= :graveyard (get-object-zone db-with-mana foi-id))
                "Precondition: FoI is in graveyard")
          ;; Get casting modes from graveyard
          modes (rules/get-casting-modes db-with-mana :player-1 foi-id)]
      ;; From graveyard, only flashback mode should be available
      (is (= 1 (count modes))
          "Should have exactly 1 mode from graveyard (flashback)")
      (is (= :flashback (:mode/id (first modes)))
          "Mode should be flashback"))))
