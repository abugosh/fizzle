(ns fizzle.history.descriptions-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.stack :as stack]
    [fizzle.history.descriptions :as descriptions]))


;; ============================================================
;; Test helpers
;; ============================================================

(defn- make-db
  "Create a minimal Datascript db with a player and game state.
   Accepts optional overrides for game state attrs."
  ([] (make-db {}))
  ([game-overrides]
   (let [conn (d/create-conn schema)]
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
                         :player/is-opponent true}])
     (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
       (d/transact! conn [(merge {:game/id :game-1
                                  :game/turn 1
                                  :game/phase :main1
                                  :game/active-player player-eid
                                  :game/priority player-eid}
                                 game-overrides)]))
     @conn)))


(defn- add-card
  "Add a card definition to db. Returns [db card-eid]."
  [db card-map]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [card-map])
    (let [card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]]
                        @conn (:card/id card-map))]
      [@conn card-eid])))


(defn- add-object
  "Add a game object to db. Returns [db object-id]."
  [db card-eid zone]
  (let [conn (d/conn-from-db db)
        player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(def ^:private test-instant
  {:card/id :test-instant
   :card/name "Dark Ritual"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/effects [{:effect/type :add-mana :effect/mana {:black 3}}]})


(def ^:private test-land
  {:card/id :test-land
   :card/name "Swamp"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:black 1}}]})


(def ^:private test-multi-ability-land
  {:card/id :test-multi-ability
   :card/name "Cephalid Coliseum"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:blue 1}}
                    {:ability/type :activated
                     :ability/name "Threshold"
                     :ability/description "Target player draws 3, discards 3"
                     :ability/cost {:tap true :sacrifice-self true :mana {:blue 1}}
                     :ability/effects [{:effect/type :draw :effect/amount 3}]}]})


(def ^:private test-single-activated
  {:card/id :test-single-activated
   :card/name "Fetchland"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/abilities [{:ability/type :activated
                     :ability/description "Search for a land"
                     :ability/cost {:sacrifice-self true}
                     :ability/effects [{:effect/type :tutor}]}]})


;; ============================================================
;; cast-spell tests
;; ============================================================

(deftest test-cast-spell-shows-card-name
  (testing "cast-spell shows the name of the cast card"
    (let [db (make-db)
          [db card-eid] (add-card db test-instant)
          [db obj-id] (add-object db card-eid :hand)
          ;; Put spell on stack (simulating what cast-spell does)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-after (stack/create-stack-item db {:stack-item/type :spell
                                                :stack-item/controller player-eid
                                                :stack-item/source obj-id
                                                :stack-item/object-ref obj-eid
                                                :stack-item/effects [{:effect/type :add-mana}]
                                                :stack-item/description "Dark Ritual"})]
      (is (= "Cast Dark Ritual"
             (descriptions/describe-event
               [:fizzle.events.game/cast-spell] nil db-after))))))


(deftest test-cast-spell-with-target-shows-target
  (testing "cast-spell with a player target (on stack-item) shows targeting info"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-targeted
                                      :card/name "Brain Freeze"
                                      :card/cmc 2
                                      :card/types #{:instant}
                                      :card/effects [{:effect/type :mill}]})
          [db obj-id] (add-object db card-eid :hand)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          ;; Put spell on stack with targets on the stack-item (matching real cast flow)
          db-after (stack/create-stack-item db {:stack-item/type :spell
                                                :stack-item/controller :player-1
                                                :stack-item/source obj-id
                                                :stack-item/object-ref obj-eid
                                                :stack-item/effects [{:effect/type :mill}]
                                                :stack-item/description "Brain Freeze"
                                                :stack-item/targets {:player :player-2}})]
      (is (= "Cast Brain Freeze targeting opponent"
             (descriptions/describe-event
               [:fizzle.events.game/cast-spell] nil db-after))))))


(deftest test-cast-spell-nil-db-falls-back
  (testing "cast-spell with nil game-db falls back to generic"
    (is (= "Cast spell"
           (descriptions/describe-event
             [:fizzle.events.game/cast-spell] nil nil)))))


(deftest test-confirm-cast-time-target-shows-card-name
  (testing "confirm-selection with cast-time-targeting shows the cast card name with target"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-targeted-spell
                                      :card/name "Orim's Chant"
                                      :card/cmc 1
                                      :card/types #{:instant}
                                      :card/effects [{:effect/type :add-restriction}]})
          [db obj-id] (add-object db card-eid :hand)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          ;; Put spell on stack with targets on the stack-item (matching real cast flow)
          db-after (stack/create-stack-item db {:stack-item/type :spell
                                                :stack-item/controller :player-1
                                                :stack-item/source obj-id
                                                :stack-item/object-ref obj-eid
                                                :stack-item/effects [{:effect/type :add-restriction}]
                                                :stack-item/description "Orim's Chant"
                                                :stack-item/targets {:player :player-2}})]
      (is (= "Cast Orim's Chant targeting opponent"
             (descriptions/describe-event
               [:fizzle.events.selection/confirm-selection]
               nil db-after :cast-time-targeting))))))


(deftest test-confirm-x-mana-selection-shows-card-name
  (testing "confirm-selection with x-mana-cost shows the cast card name"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-x-spell
                                      :card/name "Flash of Insight"
                                      :card/cmc 2
                                      :card/types #{:instant}
                                      :card/effects [{:effect/type :peek}]})
          [db obj-id] (add-object db card-eid :hand)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-after (stack/create-stack-item db {:stack-item/type :spell
                                                :stack-item/controller player-eid
                                                :stack-item/source obj-id
                                                :stack-item/object-ref obj-eid
                                                :stack-item/effects [{:effect/type :peek}]
                                                :stack-item/description "Flash of Insight"})]
      (is (= "Cast Flash of Insight"
             (descriptions/describe-event
               [:fizzle.events.selection/confirm-selection]
               nil db-after :x-mana-cost))))))


(deftest test-confirm-exile-cards-selection-shows-card-name
  (testing "confirm-selection with exile-cards-cost shows the cast card name"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-exile-spell
                                      :card/name "Flash of Insight"
                                      :card/cmc 2
                                      :card/types #{:instant}
                                      :card/effects [{:effect/type :peek}]})
          [db obj-id] (add-object db card-eid :hand)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-after (stack/create-stack-item db {:stack-item/type :spell
                                                :stack-item/controller player-eid
                                                :stack-item/source obj-id
                                                :stack-item/object-ref obj-eid
                                                :stack-item/effects [{:effect/type :peek}]
                                                :stack-item/description "Flash of Insight"})]
      (is (= "Cast Flash of Insight"
             (descriptions/describe-event
               [:fizzle.events.selection/confirm-selection]
               nil db-after :exile-cards-cost))))))


(deftest test-selection-cast-events-nil-db-fall-back
  (testing "Selection cast events fall back to generic with nil db"
    (is (= "Cast spell"
           (descriptions/describe-event
             [:fizzle.events.selection/confirm-selection] nil nil :cast-time-targeting)))
    (is (= "Cast spell"
           (descriptions/describe-event
             [:fizzle.events.selection/confirm-selection] nil nil :x-mana-cost)))
    (is (= "Cast spell"
           (descriptions/describe-event
             [:fizzle.events.selection/confirm-selection] nil nil :exile-cards-cost)))))


(deftest test-confirm-ability-target-shows-card-and-target
  (testing "confirm-selection with ability-targeting shows card name and target"
    (let [db (make-db)
          [db card-eid] (add-card db test-multi-ability-land)
          [db obj-id] (add-object db card-eid :battlefield)
          ;; Ability is on stack after confirmation (source may be sacrificed)
          db-after (stack/create-stack-item db {:stack-item/type :activated-ability
                                                :stack-item/controller :player-1
                                                :stack-item/source obj-id
                                                :stack-item/effects [{:effect/type :draw
                                                                      :effect/amount 3}]
                                                :stack-item/targets {:player :player-2}
                                                :stack-item/description "Target player draws 3 cards, then discards 3 cards"})]
      (is (= "Activate Cephalid Coliseum targeting opponent"
             (descriptions/describe-event
               [:fizzle.events.selection/confirm-selection]
               nil db-after :ability-targeting))))))


(deftest test-confirm-ability-target-self
  (testing "confirm-selection with ability-targeting targeting self shows 'targeting self'"
    (let [db (make-db)
          [db card-eid] (add-card db test-multi-ability-land)
          [db obj-id] (add-object db card-eid :battlefield)
          db-after (stack/create-stack-item db {:stack-item/type :activated-ability
                                                :stack-item/controller :player-1
                                                :stack-item/source obj-id
                                                :stack-item/effects [{:effect/type :draw
                                                                      :effect/amount 3}]
                                                :stack-item/targets {:player :player-1}
                                                :stack-item/description "Target player draws 3 cards, then discards 3 cards"})]
      (is (= "Activate Cephalid Coliseum targeting self"
             (descriptions/describe-event
               [:fizzle.events.selection/confirm-selection]
               nil db-after :ability-targeting))))))


(deftest test-confirm-ability-target-nil-db-falls-back
  (testing "confirm-selection with ability-targeting and nil db falls back to generic"
    (is (= "Activate ability"
           (descriptions/describe-event
             [:fizzle.events.selection/confirm-selection] nil nil :ability-targeting)))))


;; ============================================================
;; cast-spell fallback to casting-spell-id
;; ============================================================

(deftest test-cast-spell-uses-casting-spell-id-when-not-on-stack
  (testing "cast-spell uses casting-spell-id fallback when spell hasn't reached the stack"
    (let [db (make-db)
          [db card-eid] (add-card db test-instant)
          ;; Object is in hand (not on stack yet — mid-chain through X cost)
          [db obj-id] (add-object db card-eid :hand)]
      (is (= "Cast Dark Ritual"
             (descriptions/describe-event
               [:fizzle.events.game/cast-spell] nil db nil obj-id))
          "Should use casting-spell-id to find card name when stack is empty"))))


(deftest test-confirm-x-mana-uses-casting-spell-id-when-not-on-stack
  (testing "confirm-selection with x-mana-cost uses casting-spell-id when spell not on stack"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-x-spell
                                      :card/name "Flash of Insight"
                                      :card/cmc 2
                                      :card/types #{:instant}
                                      :card/effects [{:effect/type :peek}]})
          ;; Object is in hand (X value set on object, but not cast to stack yet)
          [db obj-id] (add-object db card-eid :hand)]
      (is (= "Cast Flash of Insight"
             (descriptions/describe-event
               [:fizzle.events.selection/confirm-selection]
               nil db :x-mana-cost obj-id))
          "Should use casting-spell-id when spell not on stack during X cost confirm"))))


(deftest test-confirm-exile-cards-uses-casting-spell-id-when-not-on-stack
  (testing "confirm-selection with exile-cards-cost uses casting-spell-id when spell not on stack"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-exile-spell
                                      :card/name "Flash of Insight"
                                      :card/cmc 2
                                      :card/types #{:instant}
                                      :card/effects [{:effect/type :peek}]})
          ;; Object is in graveyard (flashback — not on stack yet)
          [db obj-id] (add-object db card-eid :graveyard)]
      (is (= "Cast Flash of Insight"
             (descriptions/describe-event
               [:fizzle.events.selection/confirm-selection]
               nil db :exile-cards-cost obj-id))
          "Should use casting-spell-id for flashback exile-cards confirm"))))


(deftest test-cast-spell-prefers-stack-top-over-fallback
  (testing "cast-spell uses stack top when available, ignoring casting-spell-id"
    (let [db (make-db)
          [db card-eid] (add-card db test-instant)
          [db obj-id] (add-object db card-eid :hand)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-after (stack/create-stack-item db {:stack-item/type :spell
                                                :stack-item/controller player-eid
                                                :stack-item/source obj-id
                                                :stack-item/object-ref obj-eid
                                                :stack-item/effects [{:effect/type :add-mana}]
                                                :stack-item/description "Dark Ritual"})]
      (is (= "Cast Dark Ritual"
             (descriptions/describe-event
               [:fizzle.events.game/cast-spell] nil db-after nil (random-uuid)))
          "Should use stack top card name, not the fallback spell-id"))))


;; ============================================================
;; resolve-top tests
;; ============================================================

(deftest test-resolve-spell-shows-card-name
  (testing "resolve-top with spell on stack shows card name"
    (let [db (make-db)
          [db card-eid] (add-card db test-instant)
          [db obj-id] (add-object db card-eid :stack)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          pre-db (stack/create-stack-item db {:stack-item/type :spell
                                              :stack-item/controller player-eid
                                              :stack-item/source obj-id
                                              :stack-item/object-ref obj-eid
                                              :stack-item/effects [{:effect/type :add-mana}]
                                              :stack-item/description "Dark Ritual"})]
      (is (= "Resolve Dark Ritual"
             (descriptions/describe-event
               [:fizzle.events.game/resolve-top] pre-db nil))))))


(deftest test-resolve-storm-copy-shows-card-name
  (testing "resolve-top with storm-copy shows card name"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-storm-spell
                                      :card/name "Brain Freeze"
                                      :card/cmc 2
                                      :card/types #{:instant}
                                      :card/effects [{:effect/type :mill}]})
          [db obj-id] (add-object db card-eid :stack)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          pre-db (stack/create-stack-item db {:stack-item/type :storm-copy
                                              :stack-item/controller player-eid
                                              :stack-item/source obj-id
                                              :stack-item/object-ref obj-eid
                                              :stack-item/is-copy true
                                              :stack-item/effects [{:effect/type :mill}]
                                              :stack-item/description "Brain Freeze (storm copy)"})]
      (is (= "Resolve Brain Freeze"
             (descriptions/describe-event
               [:fizzle.events.game/resolve-top] pre-db nil))))))


(deftest test-resolve-activated-ability-shows-source
  (testing "resolve-top with activated-ability shows source card name"
    (let [db (make-db)
          [db card-eid] (add-card db test-multi-ability-land)
          [db obj-id] (add-object db card-eid :battlefield)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          pre-db (stack/create-stack-item db {:stack-item/type :activated-ability
                                              :stack-item/controller player-eid
                                              :stack-item/source obj-id
                                              :stack-item/effects [{:effect/type :draw}]
                                              :stack-item/description "Target player draws 3, discards 3"})]
      (is (= "Resolve Cephalid Coliseum ability"
             (descriptions/describe-event
               [:fizzle.events.game/resolve-top] pre-db nil))))))


(deftest test-resolve-storm-item-uses-description
  (testing "resolve-top with :storm type uses stack-item description"
    (let [db (make-db)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          pre-db (stack/create-stack-item db {:stack-item/type :storm
                                              :stack-item/controller player-eid
                                              :stack-item/effects [{:effect/type :storm-copies
                                                                    :effect/count 5}]
                                              :stack-item/description "Storm — create 5 copies"})]
      (is (= "Resolve Storm — create 5 copies"
             (descriptions/describe-event
               [:fizzle.events.game/resolve-top] pre-db nil))))))


(deftest test-resolve-etb-trigger-shows-source
  (testing "resolve-top with :etb trigger shows source card name"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-etb
                                      :card/name "Mystic Sanctuary"
                                      :card/cmc 0
                                      :card/types #{:land}})
          [db obj-id] (add-object db card-eid :battlefield)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          pre-db (stack/create-stack-item db {:stack-item/type :etb
                                              :stack-item/controller player-eid
                                              :stack-item/source obj-id
                                              :stack-item/effects [{:effect/type :return-from-graveyard}]})]
      (is (= "Resolve Mystic Sanctuary trigger"
             (descriptions/describe-event
               [:fizzle.events.game/resolve-top] pre-db nil))))))


(deftest test-resolve-permanent-tapped-trigger-shows-source
  (testing "resolve-top with :permanent-tapped trigger shows source card"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-tapped-trigger
                                      :card/name "City of Brass"
                                      :card/cmc 0
                                      :card/types #{:land}})
          [db obj-id] (add-object db card-eid :battlefield)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          pre-db (stack/create-stack-item db {:stack-item/type :permanent-tapped
                                              :stack-item/controller player-eid
                                              :stack-item/source obj-id
                                              :stack-item/effects [{:effect/type :deal-damage}]})]
      (is (= "Resolve City of Brass trigger"
             (descriptions/describe-event
               [:fizzle.events.game/resolve-top] pre-db nil))))))


(deftest test-resolve-empty-stack-falls-back
  (testing "resolve-top with empty stack falls back to generic"
    (let [db (make-db)]
      (is (= "Resolve top of stack"
             (descriptions/describe-event
               [:fizzle.events.game/resolve-top] db nil))))))


;; ============================================================
;; advance-phase tests
;; ============================================================

(deftest test-advance-phase-shows-phase-name
  (testing "advance-phase shows the phase name"
    (let [db-after (make-db {:game/phase :main1})]
      (is (= "Advance to Main 1"
             (descriptions/describe-event
               [:fizzle.events.game/advance-phase] nil db-after))))))


(deftest test-advance-phase-all-phases
  (testing "advance-phase shows correct name for all phases"
    (doseq [[phase expected] [[:untap "Advance to Untap"]
                              [:upkeep "Advance to Upkeep"]
                              [:draw "Advance to Draw"]
                              [:main1 "Advance to Main 1"]
                              [:combat "Advance to Combat"]
                              [:main2 "Advance to Main 2"]
                              [:end "Advance to End"]
                              [:cleanup "Advance to Cleanup"]]]
      (let [db-after (make-db {:game/phase phase})]
        (is (= expected
               (descriptions/describe-event
                 [:fizzle.events.game/advance-phase] nil db-after))
            (str "Phase " phase " should produce: " expected))))))


(deftest test-advance-phase-nil-db-falls-back
  (testing "advance-phase with nil game-db falls back to generic"
    (is (= "Advance phase"
           (descriptions/describe-event
             [:fizzle.events.game/advance-phase] nil nil)))))


;; ============================================================
;; start-turn tests
;; ============================================================

(deftest test-start-turn-shows-number
  (testing "start-turn shows the turn number"
    (let [db-after (make-db {:game/turn 3})]
      (is (= "Start Turn 3"
             (descriptions/describe-event
               [:fizzle.events.game/start-turn] nil db-after))))))


(deftest test-start-turn-nil-db-falls-back
  (testing "start-turn with nil game-db falls back to generic"
    (is (= "Start new turn"
           (descriptions/describe-event
             [:fizzle.events.game/start-turn] nil nil)))))


;; ============================================================
;; play-land tests
;; ============================================================

(deftest test-play-land-shows-card-name
  (testing "play-land shows the land name"
    (let [db (make-db)
          [db card-eid] (add-card db test-land)
          [db obj-id] (add-object db card-eid :battlefield)]
      (is (= "Play Swamp"
             (descriptions/describe-event
               [:fizzle.events.game/play-land obj-id] nil db))))))


(deftest test-play-land-missing-object-falls-back
  (testing "play-land with nonexistent object falls back to generic"
    (let [db (make-db)]
      (is (= "Play land"
             (descriptions/describe-event
               [:fizzle.events.game/play-land (random-uuid)] nil db))))))


(deftest test-play-land-nil-db-falls-back
  (testing "play-land with nil game-db falls back to generic"
    (is (= "Play land"
           (descriptions/describe-event
             [:fizzle.events.game/play-land (random-uuid)] nil nil)))))


;; ============================================================
;; activate-mana-ability tests
;; ============================================================

(deftest test-activate-mana-shows-card-name-and-color
  (testing "activate-mana-ability shows card name and mana color"
    (let [db (make-db)
          [db card-eid] (add-card db {:card/id :test-mana-land
                                      :card/name "City of Brass"
                                      :card/cmc 0
                                      :card/types #{:land}
                                      :card/abilities [{:ability/type :mana
                                                        :ability/cost {:tap true}
                                                        :ability/produces {:colorless 1}}]})
          [db obj-id] (add-object db card-eid :battlefield)]
      (is (= "Tap City of Brass for B"
             (descriptions/describe-event
               [:fizzle.events.abilities/activate-mana-ability obj-id :black]
               db nil)))
      (is (= "Tap City of Brass for U"
             (descriptions/describe-event
               [:fizzle.events.abilities/activate-mana-ability obj-id :blue]
               db nil)))
      (is (= "Tap City of Brass for C"
             (descriptions/describe-event
               [:fizzle.events.abilities/activate-mana-ability obj-id :colorless]
               db nil))))))


(deftest test-activate-mana-nil-db-falls-back
  (testing "activate-mana-ability with nil pre-db falls back to generic"
    (is (= "Activate mana ability"
           (descriptions/describe-event
             [:fizzle.events.abilities/activate-mana-ability (random-uuid) :black]
             nil nil)))))


;; ============================================================
;; activate-ability tests
;; ============================================================

(deftest test-activate-ability-single-shows-card-name
  (testing "activate-ability with single activated ability shows card name"
    (let [db (make-db)
          [db card-eid] (add-card db test-single-activated)
          [db obj-id] (add-object db card-eid :battlefield)]
      (is (= "Activate Fetchland"
             (descriptions/describe-event
               [:fizzle.events.abilities/activate-ability obj-id 0]
               db nil))))))


(deftest test-activate-ability-multi-shows-description
  (testing "activate-ability on multi-ability card shows ability description"
    (let [db (make-db)
          [db card-eid] (add-card db test-multi-ability-land)
          [db obj-id] (add-object db card-eid :battlefield)]
      ;; Ability index 1 is the threshold activated ability
      (is (= "Activate Cephalid Coliseum: Target player draws 3, discards 3"
             (descriptions/describe-event
               [:fizzle.events.abilities/activate-ability obj-id 1]
               db nil))))))


(deftest test-activate-ability-sacrificed-uses-pre-db
  (testing "activate-ability still shows name when source sacrificed (not in post-db)"
    (let [db (make-db)
          [db card-eid] (add-card db test-single-activated)
          [db obj-id] (add-object db card-eid :battlefield)
          ;; Create a post-db where the object has been removed (sacrificed)
          post-db (make-db)]
      ;; pre-db has the object, post-db does not
      (is (= "Activate Fetchland"
             (descriptions/describe-event
               [:fizzle.events.abilities/activate-ability obj-id 0]
               db post-db))))))


(deftest test-activate-ability-nil-db-falls-back
  (testing "activate-ability with nil pre-db falls back to generic"
    (is (= "Activate ability"
           (descriptions/describe-event
             [:fizzle.events.abilities/activate-ability (random-uuid) 0]
             nil nil)))))


;; ============================================================
;; init-game test
;; ============================================================

(deftest test-init-game-unchanged
  (testing "init-game always returns 'Game started' regardless of dbs"
    (is (= "Game started"
           (descriptions/describe-event
             [:fizzle.events.game/init-game] nil nil)))
    (is (= "Game started"
           (descriptions/describe-event
             [:fizzle.events.game/init-game] (make-db) (make-db))))))


;; ============================================================
;; Backward compatibility / nil event tests
;; ============================================================

(deftest test-selection-events-return-nil
  (testing "Non-cast selection events are mid-resolution choices — return nil"
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/confirm-selection] nil nil)))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/confirm-selection] nil nil :tutor)))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/confirm-selection] nil nil :scry)))))


(deftest test-unknown-event-returns-nil
  (testing "Unknown events return nil"
    (is (nil? (descriptions/describe-event [:some.unknown/event] nil nil)))))


(deftest test-ui-only-events-return-nil
  (testing "UI-only events that don't change game state return nil"
    (is (nil? (descriptions/describe-event [:fizzle.events.game/select-card :obj-1] nil nil)))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/toggle-selection :obj-1] nil nil)))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/cancel-selection] nil nil)))
    (is (nil? (descriptions/describe-event [:fizzle.events.game/cancel-mode-selection] nil nil)))))


(deftest test-mid-action-events-return-nil
  (testing "Mid-action events (mode selection) return nil"
    (is (nil? (descriptions/describe-event [:fizzle.events.game/select-casting-mode {:mode :normal}] nil nil)))))


(deftest test-descriptions-are-non-empty-strings
  (testing "All priority event descriptions are non-empty strings (with nil dbs = fallback)"
    (let [events [[:fizzle.events.game/init-game]
                  [:fizzle.events.game/cast-spell]
                  [:fizzle.events.game/cast-and-yield]
                  [:fizzle.events.game/resolve-top]
                  [:fizzle.events.game/advance-phase]
                  [:fizzle.events.game/start-turn]
                  [:fizzle.events.game/play-land :obj-1]
                  [:fizzle.events.abilities/activate-mana-ability :obj-1 :black]
                  [:fizzle.events.abilities/activate-ability :obj-1 0]]]
      (doseq [event events]
        (let [desc (descriptions/describe-event event nil nil)]
          (is (pos? (count desc)) (str "Description for " (first event) " should be non-empty"))))))
  (testing "Priority selection types produce non-empty descriptions (with nil dbs = fallback)"
    (doseq [sel-type [:cast-time-targeting :x-mana-cost :exile-cards-cost :ability-targeting]]
      (let [desc (descriptions/describe-event
                   [:fizzle.events.selection/confirm-selection] nil nil sel-type)]
        (is (pos? (count desc))
            (str "Description for confirm-selection/" sel-type " should be non-empty"))))))


;; ============================================================
;; cast-and-yield tests
;; ============================================================

(deftest test-cast-and-yield-shows-card-name-via-casting-spell-id
  (testing "cast-and-yield shows card name using casting-spell-id (spell may be resolved off stack)"
    (let [db (make-db)
          [db card-eid] (add-card db test-instant)
          [db obj-id] (add-object db card-eid :graveyard)]
      ;; Spell is in graveyard (already resolved), but casting-spell-id identifies it
      (is (= "Cast & Yield Dark Ritual"
             (descriptions/describe-event
               [:fizzle.events.game/cast-and-yield] db db nil obj-id))))))


(deftest test-cast-and-yield-falls-back-to-stack-top
  (testing "cast-and-yield falls back to stack top when casting-spell-id not available"
    (let [db (make-db)
          [db card-eid] (add-card db test-instant)
          [db obj-id] (add-object db card-eid :hand)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-after (stack/create-stack-item db {:stack-item/type :spell
                                                :stack-item/controller player-eid
                                                :stack-item/source obj-id
                                                :stack-item/object-ref obj-eid
                                                :stack-item/effects [{:effect/type :add-mana}]
                                                :stack-item/description "Dark Ritual"})]
      (is (= "Cast & Yield Dark Ritual"
             (descriptions/describe-event
               [:fizzle.events.game/cast-and-yield] nil db-after nil nil))))))


(deftest test-cast-and-yield-nil-dbs-falls-back
  (testing "cast-and-yield with nil dbs falls back to generic"
    (is (= "Cast & Yield"
           (descriptions/describe-event
             [:fizzle.events.game/cast-and-yield] nil nil)))))


;; ============================================================
;; Yield description uses standard format for all players
;; ============================================================

(deftest test-yield-uses-standard-format-for-all-players
  (testing "yield always uses standard Yield format (no bot-specific descriptions)"
    (let [db (make-db)
          [db card-eid] (add-card db test-instant)
          [db obj-id] (add-object db card-eid :stack)
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] db)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          pre-db (stack/create-stack-item db {:stack-item/type :spell
                                              :stack-item/controller player-eid
                                              :stack-item/source obj-id
                                              :stack-item/object-ref obj-eid
                                              :stack-item/effects [{:effect/type :add-mana}]
                                              :stack-item/description "Dark Ritual"})
          post-db (make-db)]
      (is (= "Yield: Dark Ritual \u2192 Main 1"
             (descriptions/describe-event
               [:fizzle.events.game/yield] pre-db post-db))
          "Yield should use standard format")
      (is (= "Yield: Dark Ritual \u2192 Main 1"
             (descriptions/describe-event
               [:fizzle.events.game/yield] pre-db post-db nil nil))
          "5-arity call should produce standard Yield format"))))
