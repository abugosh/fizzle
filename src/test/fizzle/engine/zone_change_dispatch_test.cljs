(ns fizzle.engine.zone-change-dispatch-test
  "Integration tests for zone-change trigger dispatch from move-to-zone.
   Uses programmatic fixture cards — no dependency on Gaea's Blessing (Epic 2).

   Zone-change triggers fire when move-to-zone commits a zone transition,
   filtered by a declarative :trigger/match map. :self sigil matches the
   specific object that moved."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as fx]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.events.init :as game-init]
    [fizzle.events.lands :as lands]
    [fizzle.events.opening-hand :as opening-hand]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.setup :as setup]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Fixture helpers
;; =====================================================

(defn- get-stack-items
  "Return all stack items by position."
  [db]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :stack-item/position _]]
       db))


(defn- add-fixture-to-library-with-trigger
  "Add a dark-ritual card to library with a zone-change trigger that matches
   library→graveyard moves of :self. Returns [db obj-id].

   The trigger uses :trigger/match {:event/from-zone :library
                                     :event/to-zone :graveyard
                                     :event/object-id :self}
   Effect is :draw with :effect/amount 0 (observable no-op)."
  [db owner]
  (let [[db obj-id] (th/add-card-to-zone db :dark-ritual :library owner)
        obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
        player-eid (q/get-player-eid db owner)
        trigger-tx (trigger-db/create-trigger-tx
                     {:trigger/type :zone-change
                      :trigger/event-type :zone-change
                      :trigger/source obj-eid
                      :trigger/controller player-eid
                      :trigger/match {:event/from-zone :library
                                      :event/to-zone :graveyard
                                      :event/object-id :self}
                      :trigger/effects [{:effect/type :draw :effect/amount 0}]
                      :trigger/uses-stack? true})
        db (d/db-with db trigger-tx)]
    [db obj-id]))


;; =====================================================
;; Integration: zone-change fires on library→graveyard
;; =====================================================

(deftest zone-change-fires-on-library-to-graveyard-match
  (testing "move-to-zone library→graveyard dispatches zone-change trigger to stack"
    (let [db (th/create-test-db)
          [db obj-id] (add-fixture-to-library-with-trigger db :player-1)
          initial-stack (get-stack-items db)
          db-after (zone-change-dispatch/move-to-zone db obj-id :graveyard)
          stack-after (get-stack-items db-after)]
      (is (= 0 (count initial-stack)) "Precondition: stack starts empty")
      (is (= 1 (count stack-after)) "Trigger fires exactly once on library→graveyard move"))))


(deftest zone-change-fires-exactly-once
  (testing "zone-change trigger appears exactly once on the stack (not 0, not 2)"
    (let [db (th/create-test-db)
          [db obj-id] (add-fixture-to-library-with-trigger db :player-1)
          db-after (zone-change-dispatch/move-to-zone db obj-id :graveyard)
          stack (get-stack-items db-after)]
      (is (= 1 (count stack)) "Exactly one stack item created"))))


(deftest zone-change-stack-item-source-is-fixture-uuid
  (testing "stack item source matches the fixture object's UUID"
    (let [db (th/create-test-db)
          [db obj-id] (add-fixture-to-library-with-trigger db :player-1)
          db-after (zone-change-dispatch/move-to-zone db obj-id :graveyard)
          stack (get-stack-items db-after)]
      (is (= 1 (count stack)) "Precondition: one stack item exists")
      (when (= 1 (count stack))
        (is (= obj-id (:stack-item/source (first stack)))
            "Stack item source UUID matches the fixture object")))))


;; =====================================================
;; Integration: negative - from-zone filter excludes
;; =====================================================

(deftest zone-change-does-not-fire-from-hand
  (testing "fixture trigger does NOT fire when object moves hand→graveyard (from-zone filter)"
    (let [db (th/create-test-db)
          [db obj-id] (add-fixture-to-library-with-trigger db :player-1)
          ;; First move fixture from library to hand (silently replaces position in library)
          db-in-hand (zone-change-dispatch/move-to-zone db obj-id :hand)
          ;; Clear any stack items from that move
          stack-before (count (get-stack-items db-in-hand))
          ;; Now move from hand to graveyard — should NOT fire the library-triggered trigger
          db-after (zone-change-dispatch/move-to-zone db-in-hand obj-id :graveyard)
          new-stack-items (- (count (get-stack-items db-after)) stack-before)]
      (is (= 0 new-stack-items)
          "Trigger should NOT fire: from-zone filter requires :library not :hand"))))


(deftest zone-change-does-not-fire-between-non-matching-zones
  (testing "fixture trigger does NOT fire when moving battlefield→exile"
    (let [db (th/create-test-db)
          [db obj-id] (add-fixture-to-library-with-trigger db :player-1)
          ;; Move fixture to battlefield first (bypasses library step)
          db-bf (zone-change-dispatch/move-to-zone db obj-id :battlefield)
          stack-before (count (get-stack-items db-bf))
          ;; Move battlefield→exile — both from/to zones differ from trigger match-map
          db-after (zone-change-dispatch/move-to-zone db-bf obj-id :exile)
          new-items (- (count (get-stack-items db-after)) stack-before)]
      (is (= 0 new-items)
          "Trigger does NOT fire: battlefield→exile doesn't match library→graveyard"))))


;; =====================================================
;; Integration: :self sigil excludes other objects
;; =====================================================

(deftest zone-change-self-sigil-excludes-other-cards
  (testing ":self filter: milling a different card does NOT fire the fixture's trigger"
    (let [db (th/create-test-db)
          ;; Add fixture with zone-change trigger to library
          [db _fixture-id] (add-fixture-to-library-with-trigger db :player-1)
          ;; Add a plain card (no trigger) to library
          [db plain-id] (th/add-card-to-zone db :dark-ritual :library :player-1)
          stack-before (count (get-stack-items db))
          ;; Mill only the plain card (no trigger)
          db-after (zone-change-dispatch/move-to-zone db plain-id :graveyard)
          new-items (- (count (get-stack-items db-after)) stack-before)]
      (is (= 0 new-items)
          ":self filter: fixture trigger does NOT fire when a different card moves"))))


;; =====================================================
;; Integration: two fixture cards in sequence
;; =====================================================

(deftest zone-change-fires-for-each-matching-card-in-sequence
  (testing "milling two fixture cards produces two distinct trigger stack items"
    (let [db (th/create-test-db)
          [db obj-id-1] (add-fixture-to-library-with-trigger db :player-1)
          [db obj-id-2] (add-fixture-to-library-with-trigger db :player-1)
          db-after-1 (zone-change-dispatch/move-to-zone db obj-id-1 :graveyard)
          db-after-2 (zone-change-dispatch/move-to-zone db-after-1 obj-id-2 :graveyard)
          stack (get-stack-items db-after-2)
          sources (set (map :stack-item/source stack))]
      (is (= 2 (count stack)) "Two mills produce two trigger stack items")
      (is (= #{obj-id-1 obj-id-2} sources)
          "Each stack item has a distinct source UUID"))))


;; =====================================================
;; Integration: EID stability after move
;; =====================================================

(deftest zone-change-trigger-source-is-stable-after-move
  (testing "stack item source UUID remains valid (object queryable) after zone transition"
    (let [db (th/create-test-db)
          [db obj-id] (add-fixture-to-library-with-trigger db :player-1)
          db-after (zone-change-dispatch/move-to-zone db obj-id :graveyard)
          stack (get-stack-items db-after)]
      (is (= 1 (count stack)) "Precondition: trigger fired")
      (when (= 1 (count stack))
        (let [source-uuid (:stack-item/source (first stack))
              obj-after-move (q/get-object db-after source-uuid)]
          (is (= obj-id source-uuid) "Stack item source matches the moved object's UUID")
          (is (= :graveyard (:object/zone obj-after-move))
              "Object is still queryable via get-object after move, zone is graveyard"))))))


;; =====================================================
;; Backwards compatibility: City of Brass still fires
;; =====================================================

(deftest city-of-brass-becomes-tapped-still-fires
  (testing "existing :becomes-tapped trigger on City of Brass still fires after zone-change wiring"
    (let [db (th/create-test-db)
          [db cob-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          ;; Play the land (registers Datascript triggers)
          db-bf (lands/play-land db :player-1 cob-id)
          _ (is (= :battlefield (:object/zone (q/get-object db-bf cob-id)))
                "Precondition: City of Brass on battlefield")
          ;; Dispatch permanent-tapped event (simulating what mana-activation does)
          event (game-events/permanent-tapped-event cob-id :player-1)
          db-dispatched (dispatch/dispatch-event db-bf event)
          stack-items (get-stack-items db-dispatched)
          cob-triggers (filter #(= :permanent-tapped (:stack-item/type %)) stack-items)]
      (is (pos? (count cob-triggers))
          "City of Brass :becomes-tapped trigger should still fire (backwards compat)"))))


;; =====================================================
;; :land-entered dispatch from move-to-zone chokepoint
;; =====================================================

(deftest land-entered-fires-when-land-moves-to-battlefield-via-chokepoint
  (testing "move-to-zone dispatches :land-entered when a land enters the battlefield"
    (let [db (th/create-test-db)
          [db island-id] (th/add-card-to-zone db :island :hand :player-1)
          player-eid (q/get-player-eid db :player-1)
          ;; Register a :land-entered trigger to observe the event
          trigger-tx (trigger-db/create-trigger-tx
                       {:trigger/event-type :land-entered
                        :trigger/controller player-eid
                        :trigger/filter {}
                        :trigger/uses-stack? true
                        :trigger/effects [{:effect/type :draw :effect/amount 0}]})
          db (d/db-with db trigger-tx)
          stack-before (count (get-stack-items db))
          ;; Call chokepoint directly — NOT through events/lands/play-land
          db-after (zone-change-dispatch/move-to-zone db island-id :battlefield)
          stack-after (count (get-stack-items db-after))]
      (is (= 0 stack-before) "Precondition: stack starts empty")
      (is (= 1 (- stack-after stack-before))
          ":land-entered trigger fires when land enters battlefield via chokepoint"))))


(deftest land-entered-does-not-fire-for-non-land-entering-battlefield
  (testing "move-to-zone does NOT dispatch :land-entered for a non-land card entering battlefield"
    (let [db (th/create-test-db)
          [db spell-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          player-eid (q/get-player-eid db :player-1)
          ;; Register :land-entered trigger
          trigger-tx (trigger-db/create-trigger-tx
                       {:trigger/event-type :land-entered
                        :trigger/controller player-eid
                        :trigger/filter {}
                        :trigger/uses-stack? true
                        :trigger/effects [{:effect/type :draw :effect/amount 0}]})
          db (d/db-with db trigger-tx)
          stack-before (count (get-stack-items db))
          ;; Move a non-land to battlefield
          db-after (zone-change-dispatch/move-to-zone db spell-id :battlefield)
          stack-after (count (get-stack-items db-after))]
      (is (= 0 stack-before) "Precondition: stack starts empty")
      (is (= 0 (- stack-after stack-before))
          ":land-entered trigger must NOT fire when non-land card enters battlefield"))))


(deftest land-entered-does-not-fire-when-land-moves-to-non-battlefield-zone
  (testing "move-to-zone does NOT dispatch :land-entered when a land moves to graveyard"
    (let [db (th/create-test-db)
          [db island-id] (th/add-card-to-zone db :island :hand :player-1)
          player-eid (q/get-player-eid db :player-1)
          ;; Register :land-entered trigger
          trigger-tx (trigger-db/create-trigger-tx
                       {:trigger/event-type :land-entered
                        :trigger/controller player-eid
                        :trigger/filter {}
                        :trigger/uses-stack? true
                        :trigger/effects [{:effect/type :draw :effect/amount 0}]})
          db (d/db-with db trigger-tx)
          stack-before (count (get-stack-items db))
          ;; Move land to graveyard — should NOT fire :land-entered
          db-after (zone-change-dispatch/move-to-zone db island-id :graveyard)
          stack-after (count (get-stack-items db-after))]
      (is (= 0 stack-before) "Precondition: stack starts empty")
      (is (= 0 (- stack-after stack-before))
          ":land-entered must NOT fire when land moves to graveyard (only :battlefield)"))))


;; =====================================================
;; Mulligan non-crash test (real production path)
;; =====================================================

(deftest mulligan-with-zone-change-fixture-does-not-crash
  (testing "real mulligan via opening-hand/mulligan-handler does not crash with zone-change triggers in library"
    ;; Uses the actual mulligan code path (opening_hand.cljs), not a synthetic round-trip.
    ;; The fixture's trigger is library→graveyard.
    ;; Mulligan moves (library↔hand) do not match the trigger's from-zone: :library, to-zone: :graveyard.
    ;; Therefore no zone-change trigger stack items should be created.
    (let [;; Create a real opening-hand app-db with a full 60-card library
          app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)})
          game-db (:game/db app-db)
          human-pid (q/get-human-player-id game-db)
          ;; Add fixture with library→graveyard zone-change trigger to library
          [game-db-with-fixture _fixture-id] (add-fixture-to-library-with-trigger game-db human-pid)
          app-db-with-fixture (assoc app-db :game/db game-db-with-fixture)
          ;; Run the real mulligan handler
          result-app-db (opening-hand/mulligan-handler app-db-with-fixture)
          result-db (:game/db result-app-db)
          stack-after (get-stack-items result-db)]
      ;; No crash occurred (if we reach here, test completes normally)
      (is (= 7 (count (q/get-objects-in-zone result-db human-pid :hand)))
          "Hand has 7 cards after mulligan")
      (is (= 0 (count stack-after))
          "No zone-change stack items: mulligan moves hand↔library, not library→graveyard"))))


;; =====================================================
;; Production path: play-land fires :land-entered once
;; =====================================================

(deftest land-entered-fires-exactly-once-via-play-land
  (testing "lands/play-land fires :land-entered exactly once (regression guard against double-dispatch)"
    ;; This test exercises the INTEGRATED path: play-land → zone-change-dispatch/move-to-zone.
    ;; It guards against double-firing if someone re-adds an explicit :land-entered dispatch
    ;; to events/lands.cljs while the chokepoint dispatch is also active.
    (let [db (th/create-test-db {:land-plays 1})
          [db island-id] (th/add-card-to-zone db :island :hand :player-1)
          player-eid (q/get-player-eid db :player-1)
          ;; Register a :land-entered observer trigger (does no harm, just counts)
          trigger-tx (trigger-db/create-trigger-tx
                       {:trigger/event-type :land-entered
                        :trigger/controller player-eid
                        :trigger/filter {}
                        :trigger/uses-stack? true
                        :trigger/effects [{:effect/type :draw :effect/amount 0}]})
          db (d/db-with db trigger-tx)
          stack-before (count (get-stack-items db))
          ;; Play the land via the production path (NOT move-to-zone directly)
          db-after (lands/play-land db :player-1 island-id)
          stack-after (count (get-stack-items db-after))]
      (is (= 0 stack-before) "Precondition: stack starts empty")
      (is (= 1 (- stack-after stack-before))
          ":land-entered fires exactly once via play-land (not 0, not 2)"))))


;; =====================================================
;; Production path: graveyard→battlefield fires :land-entered
;; =====================================================

(deftest land-entered-fires-when-land-returns-to-battlefield
  (testing "move-to-zone fires :land-entered when land moves graveyard→battlefield (any origin zone)"
    ;; This tests the epic's core motivation: a land returned from graveyard (e.g., via
    ;; a reanimate effect) fires :land-entered. This would NOT work with the old explicit
    ;; dispatch in events/lands.cljs — it only fired from play-land.
    (let [db (th/create-test-db)
          [db island-id] (th/add-card-to-zone db :island :graveyard :player-1)
          player-eid (q/get-player-eid db :player-1)
          ;; Register a :land-entered observer trigger
          trigger-tx (trigger-db/create-trigger-tx
                       {:trigger/event-type :land-entered
                        :trigger/controller player-eid
                        :trigger/filter {}
                        :trigger/uses-stack? true
                        :trigger/effects [{:effect/type :draw :effect/amount 0}]})
          db (d/db-with db trigger-tx)
          stack-before (count (get-stack-items db))
          ;; Move from graveyard to battlefield — simulates return/reanimate effects
          db-after (zone-change-dispatch/move-to-zone db island-id :battlefield)
          stack-after (count (get-stack-items db-after))]
      (is (= 0 stack-before) "Precondition: stack starts empty")
      (is (= 1 (- stack-after stack-before))
          ":land-entered fires when land moves graveyard→battlefield via move-to-zone"))))


;; =====================================================
;; Production path: City of Traitors real card integration
;; =====================================================

(deftest city-of-traitors-sacrifices-when-second-land-played-via-production-path
  (testing "City of Traitors sacrifice trigger fires and resolves via production path"
    ;; This is the crown jewel test: proves the REAL City of Traitors card works
    ;; end-to-end after the :land-entered dispatch migration.
    ;; If this test passes, the card is not broken in the actual game.
    ;; Path: play-land (CoT) → play-land (Island) → CoT trigger on stack → resolve → CoT in graveyard
    (let [db (th/create-test-db {:land-plays 2})
          [db cot-id] (th/add-card-to-zone db :city-of-traitors :hand :player-1)
          ;; Play City of Traitors (trigger registers, no :land-entered fires due to :exclude-self)
          db (lands/play-land db :player-1 cot-id)
          _ (is (= :battlefield (:object/zone (q/get-object db cot-id)))
                "Precondition: City of Traitors on battlefield")
          _ (is (= 0 (count (get-stack-items db)))
                "Precondition: no triggers on stack after CoT enters (exclude-self)")
          ;; Add Island to hand and play it (fires :land-entered, CoT trigger activates)
          [db island-id] (th/add-card-to-zone db :island :hand :player-1)
          db (lands/play-land db :player-1 island-id)
          _ (is (= :battlefield (:object/zone (q/get-object db island-id)))
                "Precondition: Island on battlefield")
          _ (is (= 1 (count (get-stack-items db)))
                "CoT sacrifice trigger should be on stack")
          ;; Resolve the trigger — sacrifice should execute
          db-resolved (:db (resolution/resolve-one-item db))]
      ;; City of Traitors should now be in graveyard
      (is (= :graveyard (:object/zone (q/get-object db-resolved cot-id)))
          "City of Traitors should be in graveyard after sacrifice trigger resolves")
      ;; Island should still be on battlefield
      (is (= :battlefield (:object/zone (q/get-object db-resolved island-id)))
          "Island should remain on battlefield")
      ;; Stack should be empty
      (is (= 0 (count (get-stack-items db-resolved)))
          "Stack should be empty after trigger resolves"))))


;; =====================================================
;; HIGH corner case: :land-entered does NOT fire during
;; init/mulligan (add lands to library/hand)
;; =====================================================

(deftest land-entered-does-not-fire-when-land-moves-to-library
  (testing ":land-entered does NOT fire when a land moves to library (init/mulligan path)"
    ;; Bug caught: if move-to-zone dispatches :land-entered for ANY zone transition
    ;; (not just :battlefield), init/mulligan would spam spurious :land-entered events.
    ;; The guard is (= new-zone :battlefield) in zone_change_dispatch.cljs.
    (let [db (th/create-test-db)
          [db island-id] (th/add-card-to-zone db :island :hand :player-1)
          player-eid (q/get-player-eid db :player-1)
          ;; Register a :land-entered observer trigger so we can detect any firing
          trigger-tx (trigger-db/create-trigger-tx
                       {:trigger/event-type :land-entered
                        :trigger/controller player-eid
                        :trigger/filter {}
                        :trigger/uses-stack? true
                        :trigger/effects [{:effect/type :draw :effect/amount 0}]})
          db (d/db-with db trigger-tx)
          stack-before (count (get-stack-items db))
          ;; Move land to library (simulates init/mulligan returning a card)
          db-after (zone-change-dispatch/move-to-zone db island-id :library)
          stack-after (count (get-stack-items db-after))]
      (is (= 0 stack-before) "Precondition: stack starts empty")
      (is (= 0 (- stack-after stack-before))
          ":land-entered must NOT fire when land moves to :library (only :battlefield)"))))


(deftest land-entered-does-not-fire-when-land-moves-to-hand
  (testing ":land-entered does NOT fire when a land is drawn to hand (init/draw path)"
    ;; Bug caught: a draw (library→hand) during the draw step fires move-to-zone.
    ;; :land-entered should not trigger during draws; only entering :battlefield matters.
    (let [db (th/create-test-db)
          [db island-id] (th/add-card-to-zone db :island :library :player-1)
          player-eid (q/get-player-eid db :player-1)
          ;; Register a :land-entered observer trigger
          trigger-tx (trigger-db/create-trigger-tx
                       {:trigger/event-type :land-entered
                        :trigger/controller player-eid
                        :trigger/filter {}
                        :trigger/uses-stack? true
                        :trigger/effects [{:effect/type :draw :effect/amount 0}]})
          db (d/db-with db trigger-tx)
          stack-before (count (get-stack-items db))
          ;; Move land to hand (simulates drawing a land card)
          db-after (zone-change-dispatch/move-to-zone db island-id :hand)
          stack-after (count (get-stack-items db-after))]
      (is (= 0 stack-before) "Precondition: stack starts empty")
      (is (= 0 (- stack-after stack-before))
          ":land-entered must NOT fire when land moves to :hand (draw step — not entering battlefield)"))))


;; =====================================================
;; Counter-spell routes through zone_change_dispatch
;; chokepoint (fizzle-tu01)
;; =====================================================

(defn- add-spell-to-stack-with-zone-change-trigger
  "Add a dark-ritual card to the :stack zone with a zone-change trigger that matches
   :stack→:graveyard moves of :self. Returns [db obj-id].

   This fixture proves that :counter-spell routes through zone_change_dispatch/move-to-zone
   so that zone-change triggers fire on countered spells."
  [db owner]
  (let [[db obj-id] (th/add-card-to-zone db :dark-ritual :stack owner)
        obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
        player-eid (q/get-player-eid db owner)
        trigger-tx (trigger-db/create-trigger-tx
                     {:trigger/type :zone-change
                      :trigger/event-type :zone-change
                      :trigger/source obj-eid
                      :trigger/controller player-eid
                      :trigger/match {:event/from-zone :stack
                                      :event/to-zone :graveyard
                                      :event/object-id :self}
                      :trigger/effects [{:effect/type :draw :effect/amount 0}]
                      :trigger/uses-stack? true})
        db (d/db-with db trigger-tx)]
    [db obj-id]))


(deftest counter-spell-effect-routes-through-zone-change-dispatch
  (testing ":counter-spell effect routes zone change through zone_change_dispatch chokepoint"
    ;; Catches: counter-target-spell using zones/move-to-zone* directly (bypassing triggers)
    ;; After fizzle-tu01 fix: counter-target-spell uses zone-change-dispatch/move-to-zone
    ;; so :zone-change triggers on the countered spell fire (stack→graveyard).
    (let [db (th/create-test-db)
          [db spell-id] (add-spell-to-stack-with-zone-change-trigger db :player-1)
          stack-before (count (get-stack-items db))
          effect {:effect/type :counter-spell
                  :effect/target spell-id}
          db-after (fx/execute-effect db :player-1 effect)
          stack-after (get-stack-items db-after)
          ;; Count zone-change trigger items (excluding any items from the spell itself)
          zone-change-triggers (filter #(= :zone-change (:stack-item/type %)) stack-after)]
      (is (= 0 stack-before) "Precondition: no stack-items initially (spell object in :stack zone, not a stack-item)")
      (is (= :graveyard (:object/zone (q/get-object db-after spell-id)))
          "Countered spell moves to :graveyard")
      (is (= 1 (count zone-change-triggers))
          "Zone-change trigger fires when spell is countered (routes through chokepoint)"))))
