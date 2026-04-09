(ns fizzle.events.selection.zone-ops-test
  "Pattern B (multimethod production-path slice) tests for
   events/selection/zone_ops.cljs defmethods.

   Covers all 9 defmethod registrations:

   build-selection-for-effect (2):
     :discard-from-revealed-hand - Duress (reveal opponent hand, caster picks noncreature/nonland)
     :chain-bounce               - Chain of Vapor (sacrifice land to copy spell)
     :counter-spell              - Mana Leak (counter unless controller pays)

   build-chain-selection (1):
     :chain-bounce               - continuation after land sacrifice

   execute-confirmed-selection (5):
     :discard                    - moves selected cards from hand to graveyard
                                   (standard path + cleanup path with grant expiry)
     :hand-reveal-discard        - opponent-reveals-hand variant (empty + non-empty)
     :chain-bounce               - declined (no copy) + accepted (sac land, create copy)
     :chain-bounce-target        - no-target (remove copy) + with-target (set copy target)
     :unless-pay                 - pay path (mana spent) + decline path (spell countered)

   Deletion-test standard: deleting src/test/fizzle/cards/** would NOT
   create a coverage gap here. These tests prove defmethod mechanism
   independently of any per-card oracle test.

   Pattern B entry: sel-spec/set-pending-selection + sel-core/confirm-selection-impl
   (or via th/confirm-selection for :selected-field selections)."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    ;; Direct card requires — NO cards.registry / cards.all-cards lookups
    [fizzle.cards.black.dark-ritual]
    [fizzle.cards.black.duress]
    [fizzle.cards.blue.careful-study]
    [fizzle.cards.blue.chain-of-vapor]
    [fizzle.cards.blue.mana-leak]
    [fizzle.cards.green.nimble-mongoose]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.events.selection.core :as sel-core]
    ;; Load zone-ops defmethods so they register on the multimethods
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.events.selection.zone-ops]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Setup
;; =====================================================

(defn- setup-db
  "Create a minimal game-db with player-1 and card registry loaded.
   Optional opts: {:mana {:black 1 :blue 2} :add-opponent? true}"
  ([]
   (setup-db {}))
  ([opts]
   (let [db (th/create-test-db (select-keys opts [:mana]))]
     (if (:add-opponent? opts)
       (th/add-opponent db)
       db))))


;; =====================================================
;; build-selection-for-effect :discard-from-revealed-hand
;; Real card: Duress ({B} - Sorcery, reveal opponent hand, pick noncreature/nonland)
;; =====================================================

(deftest discard-from-revealed-hand-builder-creates-correct-shape
  (testing ":discard-from-revealed-hand builder creates selection showing opponent hand"
    (let [db (setup-db {:add-opponent? true})
          ;; Add some cards to opponent's hand
          [db opp-ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          [db _opp-island-id] (th/add-card-to-zone db :island :hand :player-2)
          ;; Add Duress to player-1's hand as the casting object
          [db1 obj-id] (th/add-card-to-zone db :duress :hand :player-1)
          effect {:effect/type :discard-from-revealed-hand
                  :effect/target :player-2
                  :effect/criteria {:match/not-types #{:creature :land}}}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (= :hand-reveal-discard (:selection/type sel))
          "Builder returns :hand-reveal-discard selection type")
      (is (= :player-2 (:selection/target-player sel))
          "Target player should be :player-2 (opponent)")
      (is (= :player-1 (:selection/player-id sel))
          "Choosing player should be :player-1 (caster)")
      (is (= :opponent-hand (:selection/card-source sel))
          "Card source should be :opponent-hand")
      (is (= 1 (:selection/select-count sel))
          "Select count should be 1")
      ;; opp-ritual-id matches criteria (noncreature, nonland) — should be in valid-targets
      (is (contains? (set (:selection/valid-targets sel)) opp-ritual-id)
          "Dark Ritual (sorcery) should be a valid target — matches noncreature/nonland criteria")
      (is (= :exact-or-zero (:selection/validation sel))
          "Validation should be :exact-or-zero"))))


(deftest discard-from-revealed-hand-builder-filters-criteria-correctly
  (testing ":discard-from-revealed-hand builder excludes cards not matching criteria"
    (let [db (setup-db {:add-opponent? true})
          ;; Add a creature card in hand (should NOT match noncreature criteria)
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-2)
          ;; Add an island (should NOT match nonland criteria)
          [db _island-id] (th/add-card-to-zone db :island :hand :player-2)
          ;; Add a sorcery (SHOULD match noncreature/nonland)
          [db sorcery-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          [db1 obj-id] (th/add-card-to-zone db :duress :hand :player-1)
          effect {:effect/type :discard-from-revealed-hand
                  :effect/target :player-2
                  :effect/criteria {:match/not-types #{:creature :land}}}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (= [sorcery-id] (:selection/valid-targets sel))
          "Only the sorcery should match noncreature/nonland criteria"))))


(deftest discard-from-revealed-hand-builder-empty-hand-auto-confirms
  (testing ":discard-from-revealed-hand builder auto-confirms when no valid targets"
    (let [db (setup-db {:add-opponent? true})
          ;; Opponent has only lands (nothing matches noncreature/nonland)
          [db _island-id] (th/add-card-to-zone db :island :hand :player-2)
          [db1 obj-id] (th/add-card-to-zone db :duress :hand :player-1)
          effect {:effect/type :discard-from-revealed-hand
                  :effect/target :player-2
                  :effect/criteria {:match/not-types #{:creature :land}}}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (true? (:selection/auto-confirm? sel))
          "auto-confirm? should be true when no valid targets exist")
      (is (empty? (:selection/valid-targets sel))
          "valid-targets should be empty"))))


;; =====================================================
;; execute-confirmed-selection :discard
;; Standard discard: selected hand cards move to graveyard.
;; Cleanup discard: expire grants after discard.
;; =====================================================

(deftest discard-executor-standard-moves-cards-to-graveyard
  (testing ":discard executor moves selected hand cards to graveyard"
    (let [db (setup-db)
          [db1 card-a] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :dark-ritual :hand :player-1)
          ;; Build via zone-pick generic path — :discard derives from :zone-pick
          effect {:effect/type :discard
                  :effect/count 2}
          sel (sel-core/build-selection-for-effect db2 :player-1 (random-uuid) effect [])
          ;; Standard discard (no :selection/cleanup? flag)
          {:keys [db]} (th/confirm-selection db2 sel #{card-a card-b})]
      (is (= :graveyard (:object/zone (q/get-object db card-a)))
          "card-a should be in graveyard after discard")
      (is (= :graveyard (:object/zone (q/get-object db card-b)))
          "card-b should be in graveyard after discard"))))


(deftest discard-executor-cleanup-path-expires-grants
  (testing ":discard executor with :selection/cleanup? true expires grants after discard"
    (let [db (setup-db)
          [db1 card-a] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Build a cleanup-style discard selection (from events/cleanup.cljs pattern)
          sel {:selection/zone :hand
               :selection/card-source :hand
               :selection/select-count 1
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/type :discard
               :selection/lifecycle :finalized
               :selection/cleanup? true
               :selection/validation :exact
               :selection/auto-confirm? false}
          app-db (sel-spec/set-pending-selection {:game/db db1} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{card-a})
          result (sel-core/confirm-selection-impl app-db')
          db' (:game/db result)]
      (is (= :graveyard (:object/zone (q/get-object db' card-a)))
          "Card should be moved to graveyard via cleanup discard")
      ;; No pending selection after finalized lifecycle
      (is (nil? (:game/pending-selection result))
          "No pending selection after finalized cleanup discard"))))


(deftest discard-executor-empty-selection-noop
  (testing ":discard executor with empty selection is a no-op (e.g. discard 0)"
    (let [db (setup-db)
          [db1 card-a] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          effect {:effect/type :discard
                  :effect/count 2}
          sel (sel-core/build-selection-for-effect db1 :player-1 (random-uuid) effect [])
          ;; Zone-pick validation is :exact, so bypass via set-pending-selection+assoc
          sel-zero (assoc sel :selection/validation :exact-or-zero :selection/select-count 0)
          app-db (sel-spec/set-pending-selection {:game/db db1} sel-zero)
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{})
          result (sel-core/confirm-selection-impl app-db')
          db' (:game/db result)]
      ;; card-a should remain in hand
      (is (= :hand (:object/zone (q/get-object db' card-a)))
          "Card should remain in hand when nothing selected"))))


;; =====================================================
;; execute-confirmed-selection :hand-reveal-discard
;; Real card: Duress — opponent reveals hand, caster picks 1 noncreature/nonland to discard.
;; =====================================================

(deftest hand-reveal-discard-executor-discards-selected-card
  (testing ":hand-reveal-discard executor discards the selected card from opponent hand"
    (let [db (setup-db {:add-opponent? true})
          [db1 ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          [db2 obj-id] (th/add-card-to-zone db1 :duress :hand :player-1)
          effect {:effect/type :discard-from-revealed-hand
                  :effect/target :player-2
                  :effect/criteria {:match/not-types #{:creature :land}}}
          sel (sel-core/build-selection-for-effect db2 :player-1 obj-id effect [])
          {:keys [db]} (th/confirm-selection db2 sel #{ritual-id})]
      (is (= :graveyard (:object/zone (q/get-object db ritual-id)))
          "Ritual should be in graveyard after hand-reveal-discard"))))


(deftest hand-reveal-discard-executor-empty-selected-is-noop
  (testing ":hand-reveal-discard executor with empty selection is a no-op"
    (let [db (setup-db {:add-opponent? true})
          [db1 ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          [db2 obj-id] (th/add-card-to-zone db1 :duress :hand :player-1)
          ;; Build selection but confirm with empty — uses :exact-or-zero so valid
          effect {:effect/type :discard-from-revealed-hand
                  :effect/target :player-2
                  :effect/criteria {:match/not-types #{:creature :land}}}
          sel (sel-core/build-selection-for-effect db2 :player-1 obj-id effect [])
          {:keys [db]} (th/confirm-selection db2 sel #{})]
      (is (= :hand (:object/zone (q/get-object db ritual-id)))
          "Ritual should remain in hand when nothing selected"))))


;; =====================================================
;; build-selection-for-effect :chain-bounce
;; Real card: Chain of Vapor ({U} - return nonland permanent; controller may sac land to copy)
;; The engine enriches the :chain-bounce effect with :chain/controller and :chain/target-id
;; before calling the builder. Tests pass these directly.
;; =====================================================

(deftest chain-bounce-builder-creates-correct-selection-shape
  (testing ":chain-bounce builder creates selection with controller's lands as candidates"
    (let [db (setup-db {:add-opponent? true})
          ;; Add a land on battlefield for player-2 (the chain controller)
          [db1 land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          ;; Add a nonland permanent (the bounced target) for player-2
          [db2 target-id] (th/add-test-creature db1 :player-2 2 2)
          ;; Chain of Vapor is on the stack as the spell being copied
          [db3 cov-id] (th/add-card-to-zone db2 :chain-of-vapor :stack :player-1)
          ;; The engine resolves the :chain-bounce effect and enriches it with controller info
          effect {:effect/type :chain-bounce
                  :effect/target target-id
                  :chain/controller :player-2
                  :chain/target-id target-id}
          sel (sel-core/build-selection-for-effect db3 :player-1 cov-id effect [])]
      (is (= :chain-bounce (:selection/type sel))
          "Builder should return :chain-bounce selection type")
      (is (= :player-2 (:selection/player-id sel))
          "Selecting player should be chain-controller (player-2)")
      (is (= :chaining (:selection/lifecycle sel))
          "Lifecycle should be :chaining")
      (is (= :exact-or-zero (:selection/validation sel))
          "Validation should be :exact-or-zero")
      (is (contains? (set (:selection/valid-targets sel)) land-id)
          "Player-2's land should be in valid-targets")
      (is (= target-id (:selection/chain-target-id sel))
          "chain-target-id should be propagated")
      (is (= :player-2 (:selection/chain-controller sel))
          "chain-controller should be set"))))


(deftest chain-bounce-builder-no-lands-auto-confirms
  (testing ":chain-bounce builder auto-confirms when controller has no lands"
    (let [db (setup-db {:add-opponent? true})
          ;; Player-2 has no lands on battlefield
          [db1 target-id] (th/add-test-creature db :player-2 2 2)
          [db2 cov-id] (th/add-card-to-zone db1 :chain-of-vapor :stack :player-1)
          effect {:effect/type :chain-bounce
                  :effect/target target-id
                  :chain/controller :player-2
                  :chain/target-id target-id}
          sel (sel-core/build-selection-for-effect db2 :player-1 cov-id effect [])]
      (is (true? (:selection/auto-confirm? sel))
          "auto-confirm? should be true when controller has no lands")
      (is (empty? (:selection/valid-targets sel))
          "valid-targets should be empty"))))


;; =====================================================
;; build-chain-selection :chain-bounce
;; Builds the :chain-bounce-target selection after a land was sacrificed
;; and a copy was created on the stack.
;; =====================================================

(deftest chain-bounce-build-chain-selection-builds-target-selection
  (testing ":chain-bounce build-chain-selection builds :chain-bounce-target selection when land was selected"
    (let [db (setup-db {:add-opponent? true})
          ;; Add a nonland permanent as potential copy target for player-2 (the chain controller)
          [db1 target-id] (th/add-test-creature db :player-2 2 2)
          ;; Chain of Vapor is the original spell
          [db2 cov-id] (th/add-card-to-zone db1 :chain-of-vapor :stack :player-1)
          ;; Land for player-2 to sacrifice
          [db3 land-id] (th/add-card-to-zone db2 :island :battlefield :player-2)
          effect {:effect/type :chain-bounce
                  :effect/target target-id
                  :chain/controller :player-2
                  :chain/target-id target-id}
          sel (sel-core/build-selection-for-effect db3 :player-1 cov-id effect [])
          ;; Execute :chain-bounce (player-2 sacrifices the land -> copy created, chain to target-sel)
          sel-with-land (assoc sel :selection/selected #{land-id})
          app-db (sel-spec/set-pending-selection {:game/db db3} sel-with-land)
          after-chain (sel-core/confirm-selection-impl app-db)
          next-sel (:game/pending-selection after-chain)]
      ;; Should chain to :chain-bounce-target selection
      (is (= :chain-bounce-target (:selection/type next-sel))
          "Next selection type should be :chain-bounce-target")
      ;; Target selection includes player-2's nonland permanents (chain-controller's side)
      ;; Note: get-opponent-id uses :player/is-opponent flag — in tests with add-opponent,
      ;; player-2 is the opponent so get-opponent-id(db, :player-2) returns nil.
      ;; Only player-2's own creatures appear in valid-targets.
      (is (contains? (set (:selection/valid-targets next-sel)) target-id)
          "Player-2's creature should be a valid copy target")
      ;; The copy object ID and stack item EID should be set on the chained selection
      (is (some? (:selection/chain-copy-object-id next-sel))
          "chain-copy-object-id should be set on the chained selection")
      (is (some? (:selection/chain-copy-stack-item-eid next-sel))
          "chain-copy-stack-item-eid should be set"))))


;; =====================================================
;; execute-confirmed-selection :chain-bounce
;; Two branches: declined (empty selected) and accepted (sac land, create copy)
;; =====================================================

(deftest chain-bounce-executor-declined-is-noop
  (testing ":chain-bounce executor with empty selection — chain ends, no copy created"
    (let [db (setup-db {:add-opponent? true})
          [db1 land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          [db2 target-id] (th/add-test-creature db1 :player-2 2 2)
          [db3 cov-id] (th/add-card-to-zone db2 :chain-of-vapor :stack :player-1)
          effect {:effect/type :chain-bounce
                  :effect/target target-id
                  :chain/controller :player-2
                  :chain/target-id target-id}
          sel (sel-core/build-selection-for-effect db3 :player-1 cov-id effect [])
          ;; Player-2 declines (selects nothing)
          {:keys [db]} (th/confirm-selection db3 sel #{})]
      ;; Land should remain on battlefield (not sacrificed)
      (is (= :battlefield (:object/zone (q/get-object db land-id)))
          "Land should remain on battlefield when chain declined")
      ;; No copy should exist — when declined, standard-path cleanup runs
      ;; (CoV spell moves off stack). No is-copy objects should be in db.
      (let [copies (d/q '[:find [(pull ?e [:object/id :object/is-copy]) ...]
                          :where [?e :object/is-copy true]]
                        db)]
        (is (empty? copies)
            "No copy should be created when chain is declined")))))


(deftest chain-bounce-executor-accepted-sacrifices-land-and-creates-copy
  (testing ":chain-bounce executor with land selected — sacs land and creates stack copy"
    (let [db (setup-db {:add-opponent? true})
          [db1 land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          [db2 target-id] (th/add-test-creature db1 :player-2 2 2)
          [db3 cov-id] (th/add-card-to-zone db2 :chain-of-vapor :stack :player-1)
          effect {:effect/type :chain-bounce
                  :effect/target target-id
                  :chain/controller :player-2
                  :chain/target-id target-id}
          sel (sel-core/build-selection-for-effect db3 :player-1 cov-id effect [])
          ;; Player-2 accepts — sacrifice the land (chains to build-chain-selection)
          sel-with-land (assoc sel :selection/selected #{land-id})
          app-db (sel-spec/set-pending-selection {:game/db db3} sel-with-land)
          after-chain (sel-core/confirm-selection-impl app-db)
          db' (:game/db after-chain)]
      ;; Land should be sacrificed
      (is (= :graveyard (:object/zone (q/get-object db' land-id)))
          "Land should be in graveyard after sacrifice")
      ;; A copy object should now be on the stack alongside the original
      (let [stack-objects (d/q '[:find [(pull ?e [:object/id :object/is-copy]) ...]
                                 :where [?e :object/zone :stack]]
                               db')]
        (is (= 2 (count stack-objects))
            "Two objects in :stack zone: original CoV + copy")
        (is (some :object/is-copy stack-objects)
            "One of the stack objects should be marked as a copy")))))


;; =====================================================
;; execute-confirmed-selection :chain-bounce-target
;; Two branches: no-target (remove copy from stack) and with-target (set copy target)
;; =====================================================

(deftest chain-bounce-target-executor-no-target-removes-copy
  (testing ":chain-bounce-target with empty selection removes the copy from stack"
    (let [db (setup-db {:add-opponent? true})
          [db1 land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          [db2 target-id] (th/add-test-creature db1 :player-2 2 2)
          [db3 cov-id] (th/add-card-to-zone db2 :chain-of-vapor :stack :player-1)
          effect {:effect/type :chain-bounce
                  :effect/target target-id
                  :chain/controller :player-2
                  :chain/target-id target-id}
          sel (sel-core/build-selection-for-effect db3 :player-1 cov-id effect [])
          ;; Player-2 accepts the chain (creates the copy)
          sel-with-land (assoc sel :selection/selected #{land-id})
          app-db (sel-spec/set-pending-selection {:game/db db3} sel-with-land)
          after-chain (sel-core/confirm-selection-impl app-db)
          ;; Now we have a :chain-bounce-target selection
          target-sel (:game/pending-selection after-chain)
          db-with-copy (:game/db after-chain)
          copy-si-eid (:selection/chain-copy-stack-item-eid target-sel)
          ;; Decline: select no target (copy fizzles — its stack-item is removed)
          {:keys [db]} (th/confirm-selection db-with-copy target-sel #{})]
      ;; The copy stack item should be removed (retracted entity returns empty map)
      (let [copy-item (d/pull db '[:db/id :stack-item/type] copy-si-eid)]
        (is (nil? (:stack-item/type copy-item))
            "Copy stack item should be removed from db when no target chosen"))
      ;; The copy's stack-item is gone; original CoV object remains in :stack zone
      (let [stack-items (d/q '[:find [(pull ?e [:db/id]) ...]
                               :where [?e :stack-item/position _]]
                             db)]
        (is (zero? (count stack-items))
            "No stack-items remain (original CoV was added via add-card-to-zone, not a stack-item)")))))


(deftest chain-bounce-target-executor-with-target-sets-copy-target
  (testing ":chain-bounce-target with target selected sets target on copy stack item"
    (let [db (setup-db {:add-opponent? true})
          [db1 land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          [db2 target-id] (th/add-test-creature db1 :player-2 2 2)
          [db3 cov-id] (th/add-card-to-zone db2 :chain-of-vapor :stack :player-1)
          effect {:effect/type :chain-bounce
                  :effect/target target-id
                  :chain/controller :player-2
                  :chain/target-id target-id}
          sel (sel-core/build-selection-for-effect db3 :player-1 cov-id effect [])
          ;; Player-2 sacrifices the land (creates copy, chains to target selection)
          sel-with-land (assoc sel :selection/selected #{land-id})
          app-db (sel-spec/set-pending-selection {:game/db db3} sel-with-land)
          after-chain (sel-core/confirm-selection-impl app-db)
          target-sel (:game/pending-selection after-chain)
          db-with-copy (:game/db after-chain)
          copy-si-eid (:selection/chain-copy-stack-item-eid target-sel)
          ;; Choose target-id for the copy
          {:keys [db]} (th/confirm-selection db-with-copy target-sel #{target-id})]
      ;; Copy stack item should have targets set
      (let [copy-item (d/pull db '[:db/id :stack-item/targets] copy-si-eid)]
        (is (some? (:stack-item/targets copy-item))
            "Copy stack item should have targets set")
        (is (= target-id (:target-permanent (:stack-item/targets copy-item)))
            "target-permanent in copy's targets should be the chosen creature"))
      ;; Copy's stack-item remains (target was set, copy stays on stack for resolution)
      ;; Original CoV was added via add-card-to-zone (object only, no stack-item)
      (let [stack-items (d/q '[:find [(pull ?e [:db/id]) ...]
                               :where [?e :stack-item/position _]]
                             db)]
        (is (= 1 (count stack-items))
            "Copy's stack-item remains on stack with target set")))))


;; =====================================================
;; build-selection-for-effect :counter-spell
;; Real card: Mana Leak ({1}{U} - counter unless pays {3})
;; The engine enriches the :counter-spell effect with :unless-pay/controller
;; before calling the builder. Tests pass these directly.
;; =====================================================

(deftest counter-spell-builder-creates-unless-pay-selection
  (testing ":counter-spell builder creates :unless-pay selection with correct shape"
    (let [db (setup-db {:add-opponent? true})
          ;; Put a spell on the stack as the target of the counter
          [db1 ritual-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          ;; Mana Leak is the counterspell (already on stack above ritual)
          [db2 ml-id] (th/add-card-to-zone db1 :mana-leak :stack :player-1)
          ;; Build the effect as the engine would enrich it
          effect {:effect/type :counter-spell
                  :effect/target ritual-id
                  :effect/unless-pay {:colorless 3}
                  :unless-pay/controller :player-2}
          sel (sel-core/build-selection-for-effect db2 :player-1 ml-id effect [])]
      (is (= :unless-pay (:selection/type sel))
          "Builder should return :unless-pay selection type")
      (is (= :player-2 (:selection/player-id sel))
          "Choosing player should be the targeted spell's controller (player-2)")
      (is (= ritual-id (:selection/counter-target-id sel))
          "counter-target-id should reference the targeted spell")
      (is (= {:colorless 3} (:selection/unless-pay-cost sel))
          "unless-pay-cost should be {3}")
      (is (= [:pay :decline] (:selection/valid-targets sel))
          "valid-targets should be [:pay :decline]")
      (is (= :exact (:selection/validation sel))
          "Validation should be :exact")
      (is (true? (:selection/auto-confirm? sel))
          "auto-confirm? is true — auto-presented per game rule"))))


;; =====================================================
;; execute-confirmed-selection :unless-pay
;; Two branches: pay (mana spent, spell survives) and decline (spell countered)
;; =====================================================

(deftest unless-pay-executor-decline-counters-spell
  (testing ":unless-pay executor with :decline counters the targeted spell"
    (let [db (setup-db {:add-opponent? true :mana {:colorless 3}})
          ;; Put Dark Ritual on stack as target of the counter
          [db1 ritual-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          [db2 ml-id] (th/add-card-to-zone db1 :mana-leak :stack :player-1)
          effect {:effect/type :counter-spell
                  :effect/target ritual-id
                  :effect/unless-pay {:colorless 3}
                  :unless-pay/controller :player-2}
          sel (sel-core/build-selection-for-effect db2 :player-1 ml-id effect [])
          ;; Player-2 declines to pay
          {:keys [db]} (th/confirm-selection db2 sel #{:decline})]
      (is (= :graveyard (:object/zone (q/get-object db ritual-id)))
          "Dark Ritual should be in graveyard (countered) when player declines"))))


(deftest unless-pay-executor-pay-path-spends-mana-and-spell-survives
  (testing ":unless-pay executor with :pay spends controller's mana and spell stays on stack"
    (let [db (setup-db {:add-opponent? true})
          ;; Give player-2 enough mana to pay {3}
          db (mana/add-mana db :player-2 {:colorless 3})
          [db1 ritual-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          [db2 ml-id] (th/add-card-to-zone db1 :mana-leak :stack :player-1)
          effect {:effect/type :counter-spell
                  :effect/target ritual-id
                  :effect/unless-pay {:colorless 3}
                  :unless-pay/controller :player-2}
          sel (sel-core/build-selection-for-effect db2 :player-1 ml-id effect [])
          ;; Player-2 pays {3}
          {:keys [db]} (th/confirm-selection db2 sel #{:pay})]
      ;; Dark Ritual should still be on the stack
      (is (= :stack (:object/zone (q/get-object db ritual-id)))
          "Dark Ritual should remain on stack when controller pays")
      ;; Player-2's mana should be spent
      (let [pool (q/get-mana-pool db :player-2)]
        (is (= 0 (get pool :colorless 0))
            "Player-2 should have paid 3 colorless mana")))))


(deftest unless-pay-executor-decline-with-no-mana-still-counters
  (testing ":unless-pay executor with :decline counters regardless of mana available"
    (let [db (setup-db {:add-opponent? true})
          ;; Player-2 has NO mana at all
          [db1 ritual-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          [db2 ml-id] (th/add-card-to-zone db1 :mana-leak :stack :player-1)
          effect {:effect/type :counter-spell
                  :effect/target ritual-id
                  :effect/unless-pay {:colorless 3}
                  :unless-pay/controller :player-2}
          sel (sel-core/build-selection-for-effect db2 :player-1 ml-id effect [])
          {:keys [db]} (th/confirm-selection db2 sel #{:decline})]
      (is (= :graveyard (:object/zone (q/get-object db ritual-id)))
          "Spell should be countered when player declines even with no mana"))))
