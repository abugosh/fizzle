(ns fizzle.events.abilities-test
  "Integration (dispatch-sync) + unit tests for events/abilities.cljs.

   Integration tests dispatch through re-frame and verify state changes including
   SBA-adjacent behavior and history entry creation.
   Unit tests call pure functions directly for guard conditions and edge cases."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [clojure.string :as str]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.events.abilities :as abilities]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as h]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register history interceptor for dispatch-sync tests
(interceptor/register!)


(defn- setup-app-db
  "Create a full app-db with human + goldfish bot, libraries populated,
   starting at :main1 phase. Mirrors priority_test.cljs setup exactly."
  ([]
   (setup-app-db {}))
  ([opts]
   (h/create-game-scenario (merge {:bot-archetype :goldfish} opts))))


(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; ============================================================
;; Integration Tests (dispatch-sync through re-frame)
;; ============================================================

;; Test 1: activate-mana-ability adds mana to pool
(deftest activate-mana-ability-adds-mana
  (testing "::activate-mana-ability dispatching adds mana to pool and taps the land"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :swamp :battlefield :player-1)
          app-db' (assoc app-db :game/db game-db')
          _ (is (= 0 (:black (q/get-mana-pool game-db' :player-1)))
                "Precondition: no black mana")
          result (dispatch-event app-db' [::abilities/activate-mana-ability obj-id :black])
          result-db (:game/db result)]
      (is (= 1 (:black (q/get-mana-pool result-db :player-1)))
          "Black mana should be added to pool after activating Swamp")
      (is (true? (:object/tapped (q/get-object result-db obj-id)))
          "Swamp should be tapped after activation"))))


;; Test 2: activate-mana-ability creates history entry
(deftest activate-mana-ability-creates-history-entry
  (testing "::activate-mana-ability creates a history entry with correct event type"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :swamp :battlefield :player-1)
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::abilities/activate-mana-ability obj-id :black])
          history-entries (:history/main result)]
      ;; interceptor moves pending-entry to history/main — check history/main not pending-entry
      (is (seq history-entries)
          "history should have entries after activate-mana-ability")
      (let [last-entry (last history-entries)]
        (is (= ::abilities/activate-mana-ability (:entry/event-type last-entry))
            "last history entry event-type should be ::abilities/activate-mana-ability")
        (is (not (str/blank? (:entry/description last-entry)))
            "last history entry should have a non-blank :entry/description")))))


;; Test 3: activate-ability on fetchland places ability on stack (no targeting selection)
(deftest activate-ability-fetchland-goes-to-stack
  (testing "::activate-ability on a fetchland places ability on stack (no targeting — tutor uses selection)"
    (let [app-db (setup-app-db {:mana {:black 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :polluted-delta :battlefield :player-1)
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::abilities/activate-ability obj-id 0])]
      ;; Polluted Delta has sacrifice-self cost (not sacrifice-permanent) —
      ;; so it goes directly to stack without pausing for a sacrifice selection
      (is (seq (q/get-all-stack-items (:game/db result)))
          "Stack should have an ability item after activating Polluted Delta"))))


;; Test 4: activate-ability clears :game/selected-card
(deftest activate-ability-clears-selected-card
  (testing "::activate-ability dissociates :game/selected-card from app-db"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :swamp :battlefield :player-1)
          ;; Pre-set a selected card on app-db
          app-db' (assoc (assoc app-db :game/db game-db')
                         :game/selected-card obj-id)
          _ (is (= obj-id (:game/selected-card app-db'))
                "Precondition: selected-card is set")
          result (dispatch-event app-db' [::abilities/activate-ability obj-id 0])]
      ;; After dispatch, :game/selected-card should be cleared
      (is (nil? (:game/selected-card result))
          ":game/selected-card should be dissoc'd after ability activation attempt"))))


;; ============================================================
;; Unit Tests (direct function calls on pure functions)
;; ============================================================

;; Test 5: activate-ability wrong zone returns unchanged
(deftest activate-ability-wrong-zone
  (testing "activate-ability with object in :hand (not :battlefield) returns unchanged"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :polluted-delta :hand :player-1)
          player-id :player-1
          result (abilities/activate-ability db' player-id obj-id 0)]
      (is (= {:db db' :pending-selection nil} result)
          "Should return unchanged db when object is not on battlefield"))))


;; Test 6: activate-ability wrong controller returns unchanged
(deftest activate-ability-wrong-controller
  (testing "activate-ability with object controlled by opponent returns unchanged"
    (let [db (-> (h/create-test-db)
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-2)
          ;; player-1 tries to activate player-2's land
          result (abilities/activate-ability db' :player-1 obj-id 0)]
      (is (= {:db db' :pending-selection nil} result)
          "Should return unchanged db when player is not the controller"))))


;; Test 7: activate-ability in non-priority phase returns unchanged
(deftest activate-ability-not-in-priority-phase
  (testing "activate-ability in :untap phase returns unchanged"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
          ;; Set game phase to :untap (not a priority phase)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db')
          db-untap (d/db-with db' [[:db/add game-eid :game/phase :untap]])
          result (abilities/activate-ability db-untap :player-1 obj-id 0)]
      (is (= {:db db-untap :pending-selection nil} result)
          "Should return unchanged db when in non-priority phase"))))


;; Test 8: activate-ability with restriction returns unchanged
(deftest activate-ability-with-restriction
  (testing "activate-ability returns unchanged when player has :cannot-activate-non-mana-abilities"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
          ;; Add restriction grant to player
          restriction-grant {:grant/id (random-uuid)
                             :grant/type :restriction
                             :grant/source (random-uuid)
                             :grant/data {:restriction/type :cannot-activate-non-mana-abilities}}
          db-restricted (grants/add-player-grant db' :player-1 restriction-grant)
          result (abilities/activate-ability db-restricted :player-1 obj-id 0)]
      (is (= {:db db-restricted :pending-selection nil} result)
          "Should return unchanged db when player has cannot-activate-non-mana-abilities restriction"))))


;; Test 9: activate-ability with invalid ability-index returns unchanged
(deftest activate-ability-invalid-ability-index
  (testing "activate-ability with out-of-bounds ability-index returns unchanged"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
          ;; Index 99 does not exist
          result (abilities/activate-ability db' :player-1 obj-id 99)]
      (is (= {:db db' :pending-selection nil} result)
          "Should return unchanged db when ability-index is out of bounds"))))


;; Test 10: activate-ability on already-tapped land (can-activate? = false) returns unchanged
(deftest activate-ability-can-activate-false-when-tapped
  (testing "activate-ability returns unchanged when land is already tapped (tap-cost can't be paid)"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
          ;; Tap the land manually so can-activate? will return false (tap cost can't be paid)
          obj-eid (q/get-object-eid db' obj-id)
          db-tapped (d/db-with db' [[:db/add obj-eid :object/tapped true]])
          result (abilities/activate-ability db-tapped :player-1 obj-id 0)]
      (is (= {:db db-tapped :pending-selection nil} result)
          "Should return unchanged db when land is already tapped"))))


;; Test 11: activate-granted-mana-ability wrong zone returns unchanged
(deftest activate-granted-mana-ability-wrong-zone
  (testing "activate-granted-mana-ability with object not on battlefield returns db unchanged"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :swamp :hand :player-1)
          grant-id (random-uuid)
          result (abilities/activate-granted-mana-ability db' :player-1 obj-id grant-id)]
      (is (= db' result)
          "Should return unchanged db when object is not on battlefield"))))


;; Test 12: activate-granted-mana-ability in non-priority phase returns unchanged
(deftest activate-granted-mana-ability-not-priority
  (testing "activate-granted-mana-ability returns db unchanged in non-priority phase"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :swamp :battlefield :player-1)
          ;; Set game phase to :untap (not a priority phase)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db')
          db-untap (d/db-with db' [[:db/add game-eid :game/phase :untap]])
          grant-id (random-uuid)
          result (abilities/activate-granted-mana-ability db-untap :player-1 obj-id grant-id)]
      (is (= db-untap result)
          "Should return unchanged db in non-priority phase"))))


;; ============================================================
;; SBA Verification Tests
;; ============================================================

;; Test 13: activate-mana-ability tap cost taps the permanent
(deftest activate-mana-ability-tap-cost-taps-permanent
  (testing "::activate-mana-ability via dispatch-sync taps the land as cost"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :swamp :battlefield :player-1)
          _ (is (false? (:object/tapped (q/get-object game-db' obj-id)))
                "Precondition: land starts untapped")
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::abilities/activate-mana-ability obj-id :black])
          land-after (q/get-object (:game/db result) obj-id)]
      (is (true? (:object/tapped land-after))
          "Land should be tapped as cost after activate-mana-ability"))))


;; Test 14: activate-ability with fetchland creates stack item (sacrifice-self cost + tutor effect)
(deftest activate-ability-fetchland-creates-stack-item
  (testing "activating fetchland places ability on stack with tutor effect"
    (let [db (h/create-test-db {:life 20})
          [db' obj-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
          ;; Add some lands to library so tutor has targets
          [db'' _] (h/add-cards-to-library db' [:island :swamp] :player-1)
          result (abilities/activate-ability db'' :player-1 obj-id 0)]
      ;; Polluted delta has sacrifice-self cost (no sacrifice-permanent cost)
      ;; so it doesn't pause for selection — directly pays costs and creates stack item
      (is (nil? (:pending-selection result))
          "Fetchland with sacrifice-self (not sacrifice-permanent) goes to stack directly")
      (let [stack-items (q/get-all-stack-items (:db result))]
        (is (seq stack-items)
            "Stack should have the activated ability item")
        (is (= :activated-ability (:stack-item/type (first stack-items)))
            "Stack item type should be :activated-ability")))))


;; Test 15: SBA — sacrifice cost moves permanent to graveyard
(deftest activate-ability-sacrifice-moves-to-graveyard
  (testing "activating fetchland (sacrifice-self cost) moves the fetchland to :graveyard"
    (let [db (h/create-test-db {:life 20})
          [db' obj-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
          [db'' _] (h/add-cards-to-library db' [:island :swamp] :player-1)
          _ (is (= :battlefield (:object/zone (q/get-object db'' obj-id)))
                "Precondition: fetchland starts on battlefield")
          result (abilities/activate-ability db'' :player-1 obj-id 0)
          result-db (:db result)
          land-after (q/get-object result-db obj-id)]
      (is (= :graveyard (:object/zone land-after))
          "Fetchland should be in :graveyard after sacrifice-self cost is paid on activation"))))
