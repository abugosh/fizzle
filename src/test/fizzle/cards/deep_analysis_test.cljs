(ns fizzle.cards.deep-analysis-test
  "Tests for Deep Analysis card and flashback counter/fizzle behavior.

   Deep Analysis: 3U - Sorcery
   Target player draws two cards.
   Flashback - 1U, Pay 3 life.

   This tests:
   - Card definition (type, cost, alternate costs)
   - Flashback mode availability from graveyard
   - Counter/fizzle behavior (flashback spells exile when leaving stack)
   - Normal spells go to graveyard when countered"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.deep-analysis :as deep-analysis]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]))


;; === Test helpers ===

(defn add-card-to-zone
  "Add a card definition and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    ;; Add card definition if not already present
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn (:card/id card))
      (d/transact! conn [card]))
    ;; Create object in zone
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn (:card/id card))
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone zone
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])
      [obj-id @conn])))


;; === Card Definition Tests ===

(deftest deep-analysis-card-definition-test
  (testing "Deep Analysis has correct mana cost"
    (is (= {:colorless 3 :blue 1} (:card/mana-cost deep-analysis/deep-analysis))
        "Deep Analysis should cost {3}{U}"))

  (testing "Deep Analysis is a sorcery"
    (is (contains? (:card/types deep-analysis/deep-analysis) :sorcery)
        "Deep Analysis should be a sorcery"))

  (testing "Deep Analysis has correct cmc"
    (is (= 4 (:card/cmc deep-analysis/deep-analysis))
        "Deep Analysis should have CMC 4"))

  (testing "Deep Analysis is blue"
    (is (contains? (:card/colors deep-analysis/deep-analysis) :blue)
        "Deep Analysis should be blue")))


(deftest deep-analysis-flashback-definition-test
  (testing "Deep Analysis has flashback alternate cost"
    (let [flashback (first (:card/alternate-costs deep-analysis/deep-analysis))]
      (is (some? flashback)
          "Deep Analysis should have alternate costs")
      (is (= :flashback (:alternate/id flashback))
          "Alternate should be flashback")
      (is (= :graveyard (:alternate/zone flashback))
          "Flashback should be castable from graveyard")
      (is (= {:colorless 1 :blue 1} (:alternate/mana-cost flashback))
          "Flashback should cost {1}{U}")
      (is (= [{:cost/type :pay-life :cost/amount 3}] (:alternate/additional-costs flashback))
          "Flashback should require paying 3 life")
      (is (= :exile (:alternate/on-resolve flashback))
          "Flashback should exile on resolve"))))


(deftest deep-analysis-effects-test
  (testing "Deep Analysis has draw effect"
    (let [effects (:card/effects deep-analysis/deep-analysis)
          draw-effect (first effects)]
      (is (= 1 (count effects))
          "Deep Analysis should have 1 effect")
      (is (= :draw (:effect/type draw-effect))
          "Effect should be :draw")
      (is (= 2 (:effect/amount draw-effect))
          "Should draw 2 cards"))))


;; === Cast from hand tests ===

(deftest deep-analysis-cast-from-hand-test
  (testing "Deep Analysis castable from hand for 3U"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :hand)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first (filter #(= :primary (:mode/id %)) modes))]
      (is (some? primary-mode)
          "Should have primary mode")
      (is (rules/can-cast-mode? db :player-1 obj-id primary-mode)
          "Should be castable with {3}{U}"))))


(deftest deep-analysis-not-castable-from-hand-without-mana-test
  (testing "Deep Analysis not castable from hand without mana"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :hand)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


;; === Flashback cast tests ===

(deftest deep-analysis-flashback-cast-test
  (testing "Deep Analysis castable from graveyard for 1U + 3 life"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)]
      (is (= 1 (count modes))
          "Should only have flashback mode from graveyard")
      (is (= :flashback (:mode/id flashback-mode))
          "Mode should be flashback")
      (is (rules/can-cast-mode? db :player-1 obj-id flashback-mode)
          "Should be castable with {1}{U} and life"))))


(deftest deep-analysis-flashback-not-castable-without-life-test
  (testing "Deep Analysis flashback not castable without enough life"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Set player life to 2 (less than required 3)
          player-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add player-eid :player/life 2]])
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)]
      (is (false? (rules/can-cast-mode? db :player-1 obj-id flashback-mode))
          "Should not be castable without enough life"))))


(deftest deep-analysis-flashback-increments-storm-test
  (testing "Flashback cast increments storm count"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm should increment for flashback cast"))))


;; === Flashback resolution tests ===

(deftest flashback-resolves-to-exile-test
  (testing "Flashback Deep Analysis exiles after resolution"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= :exile (:object/zone (q/get-object db-resolved obj-id)))
          "Flashback spell should go to exile after resolution"))))


;; === Counter/remove from stack tests ===

(deftest flashback-spell-countered-exiles-test
  (testing "Countered flashback spell goes to exile not graveyard"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)
          ;; Counter = move off stack without resolving
          db-countered (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :exile (:object/zone (q/get-object db-countered obj-id)))
          "Countered flashback spell should go to exile"))))


(deftest normal-spell-countered-goes-to-graveyard-test
  (testing "Countered normal spell goes to graveyard"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :hand)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id primary-mode)
          db-countered (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-countered obj-id)))
          "Countered normal spell should go to graveyard"))))


(deftest move-spell-off-stack-no-op-if-not-on-stack-test
  (testing "move-spell-off-stack is no-op if spell not on stack"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis/deep-analysis :hand)
          ;; Don't cast - spell is still in hand
          db' (rules/move-spell-off-stack db :player-1 obj-id)]
      (is (= :hand (:object/zone (q/get-object db' obj-id)))
          "Spell should remain in hand (no-op)"))))


(deftest move-spell-off-stack-nil-cast-mode-test
  (testing "move-spell-off-stack handles nil cast-mode (old cast-spell API)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)  ; Dark Ritual - cast via old API
          obj-id (:object/id ritual)
          ;; Cast using old API (no mode tracking)
          db-cast (rules/cast-spell db :player-1 obj-id)
          ;; Counter the spell
          db-countered (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-countered obj-id)))
          "Spell without cast-mode should go to graveyard (default)"))))
