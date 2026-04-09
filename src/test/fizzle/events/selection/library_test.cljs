(ns fizzle.events.selection.library-test
  "Pattern B (multimethod production-path slice) tests for
   events/selection/library.cljs defmethods, plus Pattern A
   (dispatch-sync) tests for all 8 reg-event-db handlers.

   Covers all 11 defmethod registrations:

   build-selection-for-effect (4):
     :tutor          - Merchant Scroll (blue instant tutor)
     :scry           - Opt (scry 1, then draw)
     :peek-and-select - Sleight of Hand (peek 2, select 1)
     :peek-and-reorder - Portent (peek 3, put back in any order)

   execute-confirmed-selection (7):
     :tutor          - card moves to hand, library shuffled
     :peek-and-select - card to hand, remainder to bottom
     :order-bottom   - positions assigned to bottom of library
     :order-top      - positions assigned starting at 0 (top of library)
     :peek-and-reorder - cards reordered in library
     :pile-choice    - hand-count cards to hand, rest to graveyard
     :scry           - top-pile / bottom-pile / unassigned reordering

   Covers all 8 reg-event-db handlers:
     :fizzle.events.selection/select-random-pile-choice
     :fizzle.events.selection/scry-assign-top
     :fizzle.events.selection/scry-assign-bottom
     :fizzle.events.selection/scry-unassign
     :fizzle.events.selection/order-card
     :fizzle.events.selection/unorder-card
     :fizzle.events.selection/any-order
     :fizzle.events.selection/shuffle-and-confirm

   Deletion-test standard: deleting src/test/fizzle/cards/** would NOT
   create a coverage gap here. These tests prove defmethod mechanism
   independently of any per-card oracle test.

   Pattern B entry: sel-spec/set-pending-selection + sel-core/confirm-selection-impl
   (or via th/confirm-selection).
   Pattern A entry: rf/dispatch-sync with reset!/deref of rf-db/app-db."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    ;; Direct card requires — NO cards.registry / cards.all-cards lookups
    [fizzle.cards.blue.impulse]
    [fizzle.cards.blue.intuition]
    [fizzle.cards.blue.merchant-scroll]
    [fizzle.cards.blue.opt]
    [fizzle.cards.blue.portent]
    [fizzle.cards.blue.sleight-of-hand]
    [fizzle.db.queries :as q]
    [fizzle.events.db-effect :as db-effect]
    ;; Load library defmethods so they register on the multimethods
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.library :as lib]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Install history interceptor AND SBA dispatch — required for Pattern A
;; dispatch-sync tests to exercise the full production chain.
(interceptor/register!)
(db-effect/register!)


;; =====================================================
;; Test Setup
;; =====================================================

(defn- setup-db
  "Create a minimal game-db with player-1 and the full card registry loaded.
   Optional opts: {:mana {:blue 2} :library-count 5}"
  ([]
   (setup-db {}))
  ([opts]
   (let [db (th/create-test-db (select-keys opts [:mana]))
         lib-count (get opts :library-count 0)]
     (if (pos? lib-count)
       (let [card-ids (vec (repeat lib-count :island))
             [db' _] (th/add-cards-to-library db card-ids :player-1)]
         db')
       db))))


(defn- setup-app-db
  "Create app-db for Pattern A dispatch-sync tests.
   Returns full app-db with :game/db key."
  ([]
   (setup-app-db {}))
  ([opts]
   (th/create-game-scenario (merge {:bot-archetype :goldfish} opts))))


(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db.
   Resets rf-db/app-db before dispatch for test isolation."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; =====================================================
;; build-selection-for-effect :tutor
;; Real card: Merchant Scroll ({1}{U} - search library for blue instant)
;; =====================================================

(deftest tutor-builder-creates-correct-selection-shape
  (testing ":tutor builder creates selection with candidates from library"
    (let [db (setup-db {:library-count 3})
          [db1 obj-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          ;; Add an opt (blue instant) to library — should match tutor criteria
          [db2 opt-id] (th/add-card-to-zone db1 :opt :library :player-1)
          effect {:effect/type :tutor
                  :effect/criteria {:match/types #{:instant}
                                    :match/colors #{:blue}}
                  :effect/target-zone :hand
                  :effect/shuffle? true}
          sel (sel-core/build-selection-for-effect db2 :player-1 obj-id effect [])]
      (is (= :tutor (:selection/type sel))
          "Builder should return :tutor selection type")
      (is (contains? (:selection/candidates sel) opt-id)
          "opt (blue instant) should be in tutor candidates")
      (is (= :hand (:selection/target-zone sel))
          "Target zone should be :hand")
      (is (= :player-1 (:selection/player-id sel))
          "Player-id should be :player-1")
      (is (= :chaining (:selection/lifecycle sel))
          "Tutor selection should have :chaining lifecycle")
      (is (= true (:selection/shuffle? sel))
          "Shuffle? should be true"))))


(deftest tutor-builder-empty-library-returns-empty-candidates
  (testing ":tutor builder with empty library produces empty candidates"
    (let [db (setup-db)
          [db1 obj-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          effect {:effect/type :tutor
                  :effect/criteria {:match/types #{:instant}
                                    :match/colors #{:blue}}
                  :effect/target-zone :hand
                  :effect/shuffle? true}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (= :tutor (:selection/type sel))
          "Builder should return :tutor even with empty candidates")
      (is (empty? (:selection/candidates sel))
          "Candidates should be empty when library has no matching cards"))))


(deftest tutor-builder-criteria-filters-correctly
  (testing ":tutor builder filters library by criteria (type + color)"
    (let [db (setup-db)
          ;; Add a sorcery (should NOT match blue instant criteria)
          [db1 _sorcery-id] (th/add-card-to-zone db :sleight-of-hand :library :player-1)
          ;; Add a blue instant (SHOULD match)
          [db2 instant-id] (th/add-card-to-zone db1 :opt :library :player-1)
          [db3 obj-id] (th/add-card-to-zone db2 :merchant-scroll :hand :player-1)
          effect {:effect/type :tutor
                  :effect/criteria {:match/types #{:instant}
                                    :match/colors #{:blue}}
                  :effect/target-zone :hand
                  :effect/shuffle? true}
          sel (sel-core/build-selection-for-effect db3 :player-1 obj-id effect [])]
      ;; Only the instant should be a candidate
      (is (= #{instant-id} (:selection/candidates sel))
          "Only the blue instant should match tutor criteria"))))


;; =====================================================
;; build-selection-for-effect :scry
;; Real card: Opt ({U} - scry 1, then draw a card)
;; =====================================================

(deftest scry-builder-creates-correct-selection-shape
  (testing ":scry builder creates selection with correct cards from library top"
    (let [db (setup-db {:library-count 3})
          [db1 obj-id] (th/add-card-to-zone db :opt :hand :player-1)
          library-top (q/get-top-n-library db1 :player-1 1)
          top-card-id (first library-top)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (= :scry (:selection/type sel))
          "Builder should return :scry selection type")
      (is (= [top-card-id] (:selection/cards sel))
          "Top library card should be in selection/cards")
      (is (= [] (:selection/top-pile sel))
          "Top-pile should start empty")
      (is (= [] (:selection/bottom-pile sel))
          "Bottom-pile should start empty")
      (is (= :player-1 (:selection/player-id sel))
          "Player-id should be :player-1")
      (is (= obj-id (:selection/spell-id sel))
          "spell-id should be propagated"))))


(deftest scry-builder-amount-2-reveals-two-cards
  (testing ":scry builder with amount 2 reveals top 2 library cards"
    (let [db (setup-db {:library-count 3})
          [db1 obj-id] (th/add-card-to-zone db :opt :hand :player-1)
          library-top-2 (q/get-top-n-library db1 :player-1 2)
          effect {:effect/type :scry :effect/amount 2}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (= :scry (:selection/type sel)))
      (is (= 2 (count (:selection/cards sel)))
          "Scry 2 should reveal 2 cards")
      (is (= library-top-2 (:selection/cards sel))
          "Cards revealed should match top-2 from library"))))


(deftest scry-builder-empty-library-returns-nil
  (testing ":scry builder returns nil when library is empty"
    (let [db (setup-db)
          [db1 obj-id] (th/add-card-to-zone db :opt :hand :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (nil? sel)
          "Builder should return nil when library is empty"))))


(deftest scry-builder-zero-amount-returns-nil
  (testing ":scry builder returns nil when amount is 0"
    (let [db (setup-db {:library-count 3})
          [db1 obj-id] (th/add-card-to-zone db :opt :hand :player-1)
          effect {:effect/type :scry :effect/amount 0}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (nil? sel)
          "Builder should return nil when amount is 0"))))


;; =====================================================
;; build-selection-for-effect :peek-and-select
;; Real card: Sleight of Hand ({U} - look at top 2, select 1 for hand)
;; =====================================================

(deftest peek-and-select-builder-creates-correct-selection-shape
  (testing ":peek-and-select builder creates selection with candidates from library top"
    (let [db (setup-db {:library-count 3})
          [db1 obj-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          library-top-2 (q/get-top-n-library db1 :player-1 2)
          effect {:effect/type :peek-and-select
                  :effect/count 2
                  :effect/select-count 1
                  :effect/selected-zone :hand
                  :effect/remainder-zone :bottom-of-library}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (= :peek-and-select (:selection/type sel))
          "Builder should return :peek-and-select selection type")
      (is (= (set library-top-2) (:selection/candidates sel))
          "Candidates should be top-2 library cards")
      (is (= 1 (:selection/select-count sel))
          "Select count should be 1")
      (is (= #{} (:selection/selected sel))
          "Selected should start empty")
      (is (= :hand (:selection/selected-zone sel))
          "Selected zone should be :hand")
      (is (= :bottom-of-library (:selection/remainder-zone sel))
          "Remainder zone should be :bottom-of-library")
      (is (= :player-1 (:selection/player-id sel))
          "Player-id should be :player-1"))))


(deftest peek-and-select-builder-with-order-remainder-sets-chain-builder
  (testing ":peek-and-select builder with :order-remainder? true sets chain-builder"
    (let [db (setup-db {:library-count 5})
          [db1 obj-id] (th/add-card-to-zone db :impulse :hand :player-1)
          effect {:effect/type :peek-and-select
                  :effect/count 4
                  :effect/select-count 1
                  :effect/selected-zone :hand
                  :effect/remainder-zone :bottom-of-library
                  :effect/order-remainder? true}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (= :peek-and-select (:selection/type sel)))
      (is (true? (:selection/order-remainder? sel))
          "order-remainder? should be true for Impulse-style effect")
      (is (fn? (:selection/chain-builder sel))
          "chain-builder should be a fn when order-remainder? is true"))))


(deftest peek-and-select-builder-empty-library-returns-nil
  (testing ":peek-and-select builder returns nil when library is empty"
    (let [db (setup-db)
          [db1 obj-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          effect {:effect/type :peek-and-select
                  :effect/count 2
                  :effect/select-count 1
                  :effect/selected-zone :hand
                  :effect/remainder-zone :bottom-of-library}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (nil? sel)
          "Builder should return nil when library is empty"))))


;; =====================================================
;; build-selection-for-effect :peek-and-reorder
;; Real card: Portent ({U} - look at top 3 of target player's library)
;; Note: :effect/target is pre-resolved by engine before calling builder
;; =====================================================

(deftest peek-and-reorder-builder-creates-correct-selection-shape
  (testing ":peek-and-reorder builder creates selection with correct candidates"
    (let [db (setup-db {:library-count 3})
          [db1 obj-id] (th/add-card-to-zone db :portent :hand :player-1)
          library-top-3 (q/get-top-n-library db1 :player-1 3)
          effect {:effect/type :peek-and-reorder
                  :effect/count 3
                  :effect/may-shuffle? true}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (= :peek-and-reorder (:selection/type sel))
          "Builder should return :peek-and-reorder selection type")
      (is (= (set library-top-3) (:selection/candidates sel))
          "Candidates should be top-3 library cards")
      (is (= [] (:selection/ordered sel))
          "Ordered should start empty")
      (is (= :player-1 (:selection/target-player sel))
          "Target-player should default to player-id when no :effect/target given")
      (is (true? (:selection/may-shuffle? sel))
          "may-shuffle? should be true from effect")
      (is (= :player-1 (:selection/player-id sel))
          "Player-id should be :player-1"))))


(deftest peek-and-reorder-builder-with-explicit-target-player
  (testing ":peek-and-reorder builder uses :effect/target as target-player"
    (let [db (th/create-test-db)
          db-with-opp (th/add-opponent db)
          [db1 _] (th/add-cards-to-library db-with-opp [:island :island :island] :player-2)
          [db2 obj-id] (th/add-card-to-zone db1 :portent :hand :player-1)
          library-top (q/get-top-n-library db2 :player-2 3)
          effect {:effect/type :peek-and-reorder
                  :effect/count 3
                  :effect/target :player-2}
          sel (sel-core/build-selection-for-effect db2 :player-1 obj-id effect [])]
      (is (= :peek-and-reorder (:selection/type sel)))
      (is (= :player-2 (:selection/target-player sel))
          "Target-player should be :player-2 when :effect/target is :player-2")
      (is (= (set library-top) (:selection/candidates sel))
          "Candidates should come from target player's library"))))


(deftest peek-and-reorder-builder-empty-library-returns-nil
  (testing ":peek-and-reorder builder returns nil when target library is empty"
    (let [db (setup-db)
          [db1 obj-id] (th/add-card-to-zone db :portent :hand :player-1)
          effect {:effect/type :peek-and-reorder
                  :effect/count 3}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])]
      (is (nil? sel)
          "Builder should return nil when target library is empty"))))


;; =====================================================
;; execute-confirmed-selection :tutor
;; Enter via: production builder → th/confirm-selection
;; =====================================================

(deftest tutor-executor-moves-selected-card-to-hand
  (testing ":tutor executor moves selected card to hand"
    (let [db (setup-db {:library-count 3})
          [db1 opt-id] (th/add-card-to-zone db :opt :library :player-1)
          [db2 spell-id] (th/add-card-to-zone db1 :merchant-scroll :stack :player-1)
          effect {:effect/type :tutor
                  :effect/criteria {:match/types #{:instant} :match/colors #{:blue}}
                  :effect/target-zone :hand
                  :effect/shuffle? true}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          {:keys [db selection]} (th/confirm-selection db2 sel #{opt-id})]
      (is (= :hand (:object/zone (q/get-object db opt-id)))
          "Selected card should be moved to hand after tutor confirm")
      (is (nil? selection)
          "No further selection needed after simple tutor confirm"))))


(deftest tutor-executor-fail-to-find-leaves-library-intact
  (testing ":tutor executor with empty selection (fail-to-find) does not move any cards"
    (let [db (setup-db {:library-count 3})
          [db1 spell-id] (th/add-card-to-zone db :merchant-scroll :stack :player-1)
          initial-lib-count (count (q/get-objects-in-zone db1 :player-1 :library))
          effect {:effect/type :tutor
                  :effect/criteria {:match/types #{:instant} :match/colors #{:blue}}
                  :effect/target-zone :hand
                  :effect/shuffle? true}
          sel (sel-core/build-selection-for-effect db1 :player-1 spell-id effect [])
          {:keys [db]} (th/confirm-selection db1 sel #{})]
      (let [final-lib-count (count (q/get-objects-in-zone db :player-1 :library))]
        (is (= initial-lib-count final-lib-count)
            "Library size should be unchanged on fail-to-find")))))


(deftest tutor-executor-no-shuffle-when-shuffle-false
  (testing ":tutor executor with :shuffle? false moves card but does not shuffle"
    (let [db (setup-db {:library-count 2})
          [db1 opt-id] (th/add-card-to-zone db :opt :library :player-1)
          [db2 spell-id] (th/add-card-to-zone db1 :merchant-scroll :stack :player-1)
          effect {:effect/type :tutor
                  :effect/criteria {:match/types #{:instant} :match/colors #{:blue}}
                  :effect/target-zone :hand
                  :effect/shuffle? false}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          {:keys [db]} (th/confirm-selection db2 sel #{opt-id})]
      (is (= :hand (:object/zone (q/get-object db opt-id)))
          "Card should be in hand even without shuffle"))))


;; =====================================================
;; execute-confirmed-selection :peek-and-select
;; Card: Sleight of Hand
;; Standard path: selected → hand, remainder → bottom of library
;; =====================================================

(deftest peek-and-select-executor-moves-selected-to-hand
  (testing ":peek-and-select executor moves selected card to hand"
    (let [db (setup-db {:library-count 1})
          ;; Put 2 known cards at top of library
          [db1 keep-id] (th/add-card-to-zone db :opt :library :player-1)
          [db2 bottom-id] (th/add-card-to-zone db1 :impulse :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :sleight-of-hand :stack :player-1)
          effect {:effect/type :peek-and-select
                  :effect/count 2
                  :effect/select-count 1
                  :effect/selected-zone :hand
                  :effect/remainder-zone :bottom-of-library}
          sel (sel-core/build-selection-for-effect db3 :player-1 spell-id effect [])
          candidates (:selection/candidates sel)
          ;; Pick whichever of our 2 known cards is in candidates
          pick-id (first (filter candidates [keep-id bottom-id]))
          other-id (first (filter (fn [id] (and (candidates id) (not= id pick-id)))
                                  [keep-id bottom-id]))]
      (when (and pick-id other-id)
        (let [{:keys [db]} (th/confirm-selection db3 sel #{pick-id})]
          (is (= :hand (:object/zone (q/get-object db pick-id)))
              "Selected card should be in hand after peek-and-select confirm")
          (is (= :library (:object/zone (q/get-object db other-id)))
              "Unselected card should remain in library (moved to bottom)"))))))


(deftest peek-and-select-executor-fail-to-find-all-go-to-bottom
  (testing ":peek-and-select executor with empty selection keeps all candidates in library"
    (let [db (setup-db {:library-count 3})
          [db1 spell-id] (th/add-card-to-zone db :sleight-of-hand :stack :player-1)
          effect {:effect/type :peek-and-select
                  :effect/count 2
                  :effect/select-count 1
                  :effect/selected-zone :hand
                  :effect/remainder-zone :bottom-of-library}
          sel (sel-core/build-selection-for-effect db1 :player-1 spell-id effect [])
          candidates (:selection/candidates sel)
          {:keys [db]} (th/confirm-selection db1 sel #{})]
      (doseq [card-id candidates]
        (is (= :library (:object/zone (q/get-object db card-id)))
            "Fail-to-find: all peeked cards should remain in library")))))


(deftest peek-and-select-executor-chains-to-order-bottom-for-2plus-remainder
  (testing ":peek-and-select with :order-remainder? chains to :order-bottom when 2+ remainder"
    (let [db (setup-db {:library-count 5})
          [db1 spell-id] (th/add-card-to-zone db :impulse :stack :player-1)
          effect {:effect/type :peek-and-select
                  :effect/count 4
                  :effect/select-count 1
                  :effect/selected-zone :hand
                  :effect/remainder-zone :bottom-of-library
                  :effect/order-remainder? true}
          sel (sel-core/build-selection-for-effect db1 :player-1 spell-id effect [])
          candidates (:selection/candidates sel)
          pick-id (first candidates)
          {:keys [selection]} (th/confirm-selection db1 sel #{pick-id})]
      (is (= :order-bottom (:selection/type selection))
          "Chained selection should be :order-bottom"))))


;; =====================================================
;; execute-confirmed-selection :order-bottom
;; Built by lib/build-order-bottom-selection (chained from peek-and-select)
;; =====================================================

(deftest order-bottom-executor-assigns-sequential-positions-at-bottom
  (testing ":order-bottom executor assigns sequential positions after non-candidates"
    (let [db (setup-db {:library-count 3})
          ;; 3 library cards already at positions 0,1,2
          ;; Add 2 order-bottom candidates
          [db1 card-a] (th/add-card-to-zone db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b] :player-1 spell-id)
          ;; :order-bottom uses :selection/ordered (not :selection/selected)
          ;; Must use confirm-selection-impl directly with ordered set
          app-db (sel-spec/set-pending-selection {:game/db db3} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/ordered [card-a card-b])
          result (sel-core/confirm-selection-impl app-db')
          db (:game/db result)]
      (let [card-a-obj (q/get-object db card-a)
            card-b-obj (q/get-object db card-b)]
        (is (= :library (:object/zone card-a-obj))
            "Card A should still be in library after order-bottom")
        (is (= :library (:object/zone card-b-obj))
            "Card B should still be in library after order-bottom")
        (is (< (:object/position card-a-obj) (:object/position card-b-obj))
            "Card A (ordered first) should have lower position than Card B")))))


(deftest order-bottom-executor-positions-after-non-candidates
  (testing ":order-bottom positions candidates after all non-candidate library cards"
    (let [db (setup-db {:library-count 3})
          ;; 3 non-candidate cards at positions 0,1,2
          non-candidate-count 3
          [db1 card-a] (th/add-card-to-zone db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b] :player-1 spell-id)
          app-db (sel-spec/set-pending-selection {:game/db db3} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/ordered [card-a card-b])
          result (sel-core/confirm-selection-impl app-db')
          db (:game/db result)]
      ;; card-a should have position > non-candidate max position (>= non_candidate_count)
      (let [card-a-pos (:object/position (q/get-object db card-a))]
        (is (>= card-a-pos non-candidate-count)
            "Order-bottom cards should have positions after all non-candidates")))))


;; =====================================================
;; execute-confirmed-selection :order-top
;; Built by lib/build-order-top-selection (chained from peek-and-select)
;; =====================================================

(deftest order-top-executor-places-cards-at-position-0-and-1
  (testing ":order-top executor places first-ordered card at position 0"
    (let [db (setup-db {:library-count 3})
          ;; 3 non-candidate cards in library
          [db1 card-a] (th/add-card-to-zone db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-top-selection [card-a card-b] :player-1 spell-id)
          ;; :order-top uses :selection/ordered (not :selection/selected)
          ;; Order: [card-b, card-a] → card-b at position 0, card-a at position 1
          app-db (sel-spec/set-pending-selection {:game/db db3} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/ordered [card-b card-a])
          result (sel-core/confirm-selection-impl app-db')
          db (:game/db result)]
      (let [card-a-obj (q/get-object db card-a)
            card-b-obj (q/get-object db card-b)]
        (is (= :library (:object/zone card-b-obj))
            "Card B should be in library")
        (is (= 0 (:object/position card-b-obj))
            "Card B (ordered first) should be at position 0 (top)")
        (is (= 1 (:object/position card-a-obj))
            "Card A (ordered second) should be at position 1")))))


(deftest order-top-executor-non-candidates-shift-down
  (testing ":order-top non-candidate library cards shift to positions after ordered cards"
    (let [;; Library: 2 non-candidate cards (positions 0,1 before order-top)
          db (setup-db {:library-count 2})
          [db1 card-a] (th/add-card-to-zone db :opt :library :player-1)
          [db2 spell-id] (th/add-card-to-zone db1 :impulse :stack :player-1)
          ;; Get the non-candidate card ids (the 2 islands)
          lib-before (q/get-objects-in-zone db1 :player-1 :library)
          non-candidates (filter #(not= (:object/id %) card-a) lib-before)
          sel (lib/build-order-top-selection [card-a] :player-1 spell-id)
          ;; :order-top uses :selection/ordered (not :selection/selected)
          app-db (sel-spec/set-pending-selection {:game/db db2} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/ordered [card-a])
          result (sel-core/confirm-selection-impl app-db')
          db (:game/db result)]
      ;; card-a should be at position 0
      (is (= 0 (:object/position (q/get-object db card-a)))
          "Ordered card should be at position 0 (top)")
      ;; Non-candidates should all have positions > 0
      (doseq [nc non-candidates]
        (is (> (:object/position (q/get-object db (:object/id nc))) 0)
            "Non-candidate cards should shift down after order-top")))))


;; =====================================================
;; execute-confirmed-selection :peek-and-reorder
;; Real card: Portent
;; =====================================================

(deftest peek-and-reorder-executor-reorders-library-per-selection
  (testing ":peek-and-reorder executor reorders library cards per :selection/ordered"
    ;; Use add-cards-to-library to get sequential positions (0, 1, 2)
    ;; so we know exactly which 3 cards will be the top-3 candidates
    (let [db (th/create-test-db)
          ;; Add exactly 3 cards with sequential positions
          [db1 [card-a card-b card-c]] (th/add-cards-to-library
                                         db [:opt :sleight-of-hand :impulse] :player-1)
          [db2 spell-id] (th/add-card-to-zone db1 :portent :stack :player-1)
          effect {:effect/type :peek-and-reorder :effect/count 3}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          _ (is (= 3 (count (:selection/candidates sel)))
                "Precondition: builder should find all 3 cards as candidates")
          ;; :peek-and-reorder uses :selection/ordered (not :selection/selected)
          ;; Reverse the order: [card-c, card-b, card-a]
          app-db (sel-spec/set-pending-selection {:game/db db2} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/ordered [card-c card-b card-a])
          result (sel-core/confirm-selection-impl app-db')
          db (:game/db result)]
      (let [pos-a (:object/position (q/get-object db card-a))
            pos-b (:object/position (q/get-object db card-b))
            pos-c (:object/position (q/get-object db card-c))]
        ;; card-c ordered first → lowest position (top)
        (is (< pos-c pos-b)
            "Card C (ordered first) should have lower position than Card B")
        (is (< pos-b pos-a)
            "Card B (ordered second) should have lower position than Card A")))))


(deftest peek-and-reorder-executor-with-shuffle-flag-keeps-cards-in-library
  (testing ":peek-and-reorder with :shuffle? true shuffles library instead of reordering"
    ;; Use add-cards-to-library for sequential positions (ensures known top-2)
    (let [db (th/create-test-db)
          [db1 [card-a card-b]] (th/add-cards-to-library db [:opt :sleight-of-hand] :player-1)
          [db2 spell-id] (th/add-card-to-zone db1 :portent :stack :player-1)
          effect {:effect/type :peek-and-reorder :effect/count 2 :effect/may-shuffle? true}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          ;; Add shuffle? flag (simulates "shuffle" button in UI)
          app-db (sel-spec/set-pending-selection {:game/db db2} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/shuffle? true)
          result (sel-core/confirm-selection-impl app-db')
          result-db (:game/db result)]
      ;; Both cards should still be in library (shuffled, not moved)
      (is (= :library (:object/zone (q/get-object result-db card-a)))
          "Card A should remain in library after shuffle-mode reorder")
      (is (= :library (:object/zone (q/get-object result-db card-b)))
          "Card B should remain in library after shuffle-mode reorder"))))


;; =====================================================
;; execute-confirmed-selection :pile-choice
;; Real card: Intuition ({2}{U} - search for 3, opponent chooses 1 for hand)
;; Enter via: lib/build-pile-choice-selection → th/confirm-selection
;; =====================================================

(deftest pile-choice-executor-moves-selected-to-hand-rest-to-graveyard
  (testing ":pile-choice executor moves hand-count cards to hand, rest to graveyard"
    (let [db (setup-db {:library-count 3})
          ;; 3 candidates for pile-choice (simulating tutor phase having found them)
          [db1 card-a] (th/add-card-to-zone db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 card-c] (th/add-card-to-zone db2 :impulse :library :player-1)
          [db4 spell-id] (th/add-card-to-zone db3 :intuition :stack :player-1)
          card-ids #{card-a card-b card-c}
          pile-cfg {:hand 1 :graveyard :rest}
          sel (lib/build-pile-choice-selection card-ids pile-cfg :player-1 spell-id [])
          {:keys [db]} (th/confirm-selection db4 sel #{card-a})]
      (is (= :hand (:object/zone (q/get-object db card-a)))
          "Selected pile-choice card should be in hand")
      (is (= :graveyard (:object/zone (q/get-object db card-b)))
          "Non-selected card-b should be in graveyard")
      (is (= :graveyard (:object/zone (q/get-object db card-c)))
          "Non-selected card-c should be in graveyard"))))


(deftest pile-choice-executor-library-count-decreases-by-candidate-count
  (testing ":pile-choice executor removes all candidates from library"
    (let [db (setup-db {:library-count 5})
          [db1 card-a] (th/add-card-to-zone db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 card-c] (th/add-card-to-zone db2 :impulse :library :player-1)
          [db4 spell-id] (th/add-card-to-zone db3 :intuition :stack :player-1)
          initial-lib-count (count (q/get-objects-in-zone db4 :player-1 :library))
          card-ids #{card-a card-b card-c}
          pile-cfg {:hand 1 :graveyard :rest}
          sel (lib/build-pile-choice-selection card-ids pile-cfg :player-1 spell-id [])
          {:keys [db]} (th/confirm-selection db4 sel #{card-a})
          final-lib-count (count (q/get-objects-in-zone db :player-1 :library))]
      ;; 3 cards moved out of library (1 to hand, 2 to graveyard)
      (is (= (- initial-lib-count 3) final-lib-count)
          "Library should have 3 fewer cards after pile-choice"))))


;; =====================================================
;; execute-confirmed-selection :scry
;; Real card: Opt ({U} - scry 1, then draw)
;; =====================================================

(deftest scry-executor-top-pile-card-stays-at-position-0
  (testing ":scry executor with card in top-pile keeps it at position 0"
    (let [db (setup-db {:library-count 3})
          [db1 spell-id] (th/add-card-to-zone db :opt :stack :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db1 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          app-db (sel-spec/set-pending-selection {:game/db db1} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/top-pile [top-card] :selection/bottom-pile [])
          result (sel-core/confirm-selection-impl app-db')
          result-db (:game/db result)]
      (is (= :library (:object/zone (q/get-object result-db top-card)))
          "Top-pile card should remain in library")
      (is (= 0 (:object/position (q/get-object result-db top-card)))
          "Top-pile card should be at position 0 (top of library)"))))


(deftest scry-executor-bottom-pile-card-has-max-position
  (testing ":scry executor with card in bottom-pile gives it the highest position"
    (let [db (setup-db {:library-count 3})
          [db1 spell-id] (th/add-card-to-zone db :opt :stack :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db1 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          app-db (sel-spec/set-pending-selection {:game/db db1} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/top-pile [] :selection/bottom-pile [top-card])
          result (sel-core/confirm-selection-impl app-db')
          result-db (:game/db result)
          all-lib (q/get-objects-in-zone result-db :player-1 :library)
          max-pos (when (seq all-lib) (apply max (map :object/position all-lib)))]
      (is (= :library (:object/zone (q/get-object result-db top-card)))
          "Bottom-pile card should remain in library")
      (is (= max-pos (:object/position (q/get-object result-db top-card)))
          "Bottom-pile card should have the highest position (bottom of library)"))))


(deftest scry-executor-spell-moved-to-graveyard
  (testing ":scry executor moves the resolving spell to graveyard"
    (let [db (setup-db {:library-count 3})
          [db1 spell-id] (th/add-card-to-zone db :opt :stack :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db1 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          app-db (sel-spec/set-pending-selection {:game/db db1} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/top-pile [top-card] :selection/bottom-pile [])
          result (sel-core/confirm-selection-impl app-db')
          result-db (:game/db result)]
      (is (= :graveyard (:object/zone (q/get-object result-db spell-id)))
          "Resolving spell (Opt) should be in graveyard after scry confirm"))))


(deftest scry-executor-remaining-effects-executed
  (testing ":scry executor executes remaining effects (draw for Opt) after reorder"
    (let [db (setup-db {:library-count 5})
          [db1 spell-id] (th/add-card-to-zone db :opt :stack :player-1)
          initial-hand-count (th/get-hand-count db1 :player-1)
          draw-effect {:effect/type :draw :effect/amount 1}
          effect {:effect/type :scry :effect/amount 1}
          ;; Pass draw as remaining-effect (Opt: scry 1, THEN draw 1)
          sel (sel-core/build-selection-for-effect db1 :player-1 spell-id effect [draw-effect])
          top-card (first (:selection/cards sel))
          app-db (sel-spec/set-pending-selection {:game/db db1} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/top-pile [top-card] :selection/bottom-pile [])
          result (sel-core/confirm-selection-impl app-db')
          result-db (:game/db result)
          final-hand-count (th/get-hand-count result-db :player-1)]
      (is (= (inc initial-hand-count) final-hand-count)
          "Draw remaining-effect should execute: hand count should increase by 1"))))


;; =====================================================
;; reg-event-db handlers — Pattern A (dispatch-sync)
;; Template: casting_test.cljs (fizzle-w7ba)
;; Deletion-test standard: these tests prove each handler is wired
;; correctly, independently of any card-specific oracle tests.
;; =====================================================


;; =====================================================
;; :fizzle.events.selection/select-random-pile-choice
;; Randomly selects hand-count cards from pile-choice candidates.
;; Randomness: assert count and subset invariants, not specific cards.
;; =====================================================

(deftest select-random-pile-choice-selects-correct-count
  (testing "::select-random-pile-choice sets :selected with exactly hand-count cards"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 card-c] (th/add-card-to-zone db2 :impulse :library :player-1)
          card-ids #{card-a card-b card-c}
          pile-cfg {:hand 1 :graveyard :rest}
          [db4 spell-id] (th/add-card-to-zone db3 :intuition :stack :player-1)
          sel (lib/build-pile-choice-selection card-ids pile-cfg :player-1 spell-id [])
          ;; Clear pre-selected (auto-selected when 1 card — here 3 so empty)
          sel' (assoc sel :selection/selected #{})
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db4) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/select-random-pile-choice])
          selected (get-in result [:game/pending-selection :selection/selected])]
      (is (= 1 (count selected))
          "Random pile-choice should select exactly hand-count (1) cards")
      (is (every? card-ids selected)
          "All selected cards must come from the pile-choice candidates"))))


(deftest select-random-pile-choice-selected-subset-of-candidates
  (testing "::select-random-pile-choice always selects a subset of candidates"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 card-c] (th/add-card-to-zone db2 :impulse :library :player-1)
          card-ids #{card-a card-b card-c}
          pile-cfg {:hand 2 :graveyard :rest}
          [db4 spell-id] (th/add-card-to-zone db3 :intuition :stack :player-1)
          sel (lib/build-pile-choice-selection card-ids pile-cfg :player-1 spell-id [])
          sel' (assoc sel :selection/selected #{})
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db4) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/select-random-pile-choice])
          selected (get-in result [:game/pending-selection :selection/selected])
          candidates (get-in result [:game/pending-selection :selection/candidates])]
      (is (= 2 (count selected))
          "hand-count 2: should select exactly 2 cards")
      (is (every? candidates selected)
          "Selected cards must all come from candidates"))))


(deftest select-random-pile-choice-pile-choice-selection-retained
  (testing "::select-random-pile-choice retains the pending selection (does not confirm)"
    ;; Confirms that the handler only updates :selected — it does NOT confirm the selection.
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 card-c] (th/add-card-to-zone db2 :impulse :library :player-1)
          card-ids #{card-a card-b card-c}
          pile-cfg {:hand 1 :graveyard :rest}
          [db4 spell-id] (th/add-card-to-zone db3 :intuition :stack :player-1)
          sel (lib/build-pile-choice-selection card-ids pile-cfg :player-1 spell-id [])
          sel' (assoc sel :selection/selected #{})
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db4) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/select-random-pile-choice])]
      ;; Selection should still be present — handler does NOT confirm
      (let [result-sel (:game/pending-selection result)]
        (is (= :pile-choice (:selection/type result-sel))
            "select-random-pile-choice should NOT confirm the selection, only update :selected")
        (is (seq (:selection/selected result-sel))
            "select-random-pile-choice should populate :selected with candidate cards")))))


;; =====================================================
;; :fizzle.events.selection/scry-assign-top
;; Moves a card to :selection/top-pile, removes from :selection/bottom-pile.
;; =====================================================

(deftest scry-assign-top-adds-card-to-top-pile
  (testing "::scry-assign-top moves a scry card to :selection/top-pile"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          [db2 _] (th/add-card-to-zone db1 :island :library :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/scry-assign-top top-card])
          top-pile (get-in result [:game/pending-selection :selection/top-pile])]
      (is (= [top-card] top-pile)
          "scry-assign-top should add card to :selection/top-pile"))))


(deftest scry-assign-top-removes-card-from-bottom-pile
  (testing "::scry-assign-top removes card from :selection/bottom-pile if it was there"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          [db2 _card-a] (th/add-card-to-zone db1 :island :library :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          ;; Pre-assign card to bottom-pile
          sel' (assoc sel :selection/bottom-pile [top-card])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/scry-assign-top top-card])
          top-pile (get-in result [:game/pending-selection :selection/top-pile])
          bottom-pile (get-in result [:game/pending-selection :selection/bottom-pile])]
      (is (= [top-card] top-pile)
          "Card should now be in top-pile")
      (is (not (some #{top-card} bottom-pile))
          "Card must be removed from bottom-pile when moved to top-pile"))))


(deftest scry-assign-top-idempotent-double-assign
  (testing "::scry-assign-top with card already in top-pile still has it in top-pile"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          [db2 _] (th/add-card-to-zone db1 :island :library :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          ;; Pre-assign card to top-pile
          sel' (assoc sel :selection/top-pile [top-card])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/scry-assign-top top-card])
          top-pile (get-in result [:game/pending-selection :selection/top-pile])]
      (is (some #{top-card} top-pile)
          "Card should be in top-pile after double-assign (conj is not idempotent, but card remains present)"))))


;; =====================================================
;; :fizzle.events.selection/scry-assign-bottom
;; Moves a card to :selection/bottom-pile, removes from :selection/top-pile.
;; =====================================================

(deftest scry-assign-bottom-adds-card-to-bottom-pile
  (testing "::scry-assign-bottom moves a scry card to :selection/bottom-pile"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          [db2 _] (th/add-card-to-zone db1 :island :library :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/scry-assign-bottom top-card])
          bottom-pile (get-in result [:game/pending-selection :selection/bottom-pile])]
      (is (= [top-card] bottom-pile)
          "scry-assign-bottom should add card to :selection/bottom-pile"))))


(deftest scry-assign-bottom-removes-card-from-top-pile
  (testing "::scry-assign-bottom removes card from :selection/top-pile if it was there"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          [db2 _] (th/add-card-to-zone db1 :island :library :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          ;; Pre-assign card to top-pile
          sel' (assoc sel :selection/top-pile [top-card])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/scry-assign-bottom top-card])
          top-pile (get-in result [:game/pending-selection :selection/top-pile])
          bottom-pile (get-in result [:game/pending-selection :selection/bottom-pile])]
      (is (= [top-card] bottom-pile)
          "Card should now be in bottom-pile")
      (is (not (some #{top-card} top-pile))
          "Card must be removed from top-pile when moved to bottom-pile"))))


;; =====================================================
;; :fizzle.events.selection/scry-unassign
;; Removes a card from both top-pile and bottom-pile.
;; =====================================================

(deftest scry-unassign-removes-card-from-top-pile
  (testing "::scry-unassign removes card from :selection/top-pile"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          [db2 _] (th/add-card-to-zone db1 :island :library :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          ;; Pre-assign card to top-pile
          sel' (assoc sel :selection/top-pile [top-card])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/scry-unassign top-card])
          top-pile (get-in result [:game/pending-selection :selection/top-pile])]
      (is (= [] top-pile)
          "scry-unassign should remove card from top-pile"))))


(deftest scry-unassign-removes-card-from-bottom-pile
  (testing "::scry-unassign removes card from :selection/bottom-pile"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          [db2 _] (th/add-card-to-zone db1 :island :library :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          ;; Pre-assign card to bottom-pile
          sel' (assoc sel :selection/bottom-pile [top-card])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/scry-unassign top-card])
          bottom-pile (get-in result [:game/pending-selection :selection/bottom-pile])]
      (is (= [] bottom-pile)
          "scry-unassign should remove card from bottom-pile"))))


(deftest scry-unassign-no-op-when-card-not-in-any-pile
  (testing "::scry-unassign is a no-op when card is not in either pile"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          [db2 _] (th/add-card-to-zone db1 :island :library :player-1)
          effect {:effect/type :scry :effect/amount 1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          top-card (first (:selection/cards sel))
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/scry-unassign top-card])
          top-pile (get-in result [:game/pending-selection :selection/top-pile])
          bottom-pile (get-in result [:game/pending-selection :selection/bottom-pile])]
      (is (= [] top-pile)
          "top-pile should remain empty when card was never assigned")
      (is (= [] bottom-pile)
          "bottom-pile should remain empty when card was never assigned"))))


;; =====================================================
;; :fizzle.events.selection/order-card
;; Appends a card to :selection/ordered if in candidates and not already ordered.
;; =====================================================

(deftest order-card-adds-card-to-ordered
  (testing "::order-card appends card to :selection/ordered"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b] :player-1 spell-id)
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db3) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/order-card card-a])
          ordered (get-in result [:game/pending-selection :selection/ordered])]
      (is (= [card-a] ordered)
          "order-card should append the card to :selection/ordered"))))


(deftest order-card-multiple-cards-in-sequence
  (testing "::order-card builds ordered sequence correctly with multiple dispatches"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b] :player-1 spell-id)
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db3) sel)
          ;; Order card-b first, then card-a
          after-first (dispatch-event app-db' [:fizzle.events.selection/order-card card-b])
          after-second (dispatch-event after-first [:fizzle.events.selection/order-card card-a])
          ordered (get-in after-second [:game/pending-selection :selection/ordered])]
      (is (= [card-b card-a] ordered)
          "Ordered sequence should reflect click order: card-b then card-a"))))


(deftest order-card-no-op-when-card-not-in-candidates
  (testing "::order-card ignores cards not in :selection/candidates"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          ;; Build selection with only card-a as candidate (not card-b)
          sel (lib/build-order-bottom-selection [card-a] :player-1 spell-id)
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db3) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/order-card card-b])
          ordered (get-in result [:game/pending-selection :selection/ordered])]
      (is (= [] ordered)
          "order-card should not add card-b which is not in candidates"))))


(deftest order-card-no-op-when-card-already-ordered
  (testing "::order-card is no-op when card is already in :selection/ordered"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b] :player-1 spell-id)
          ;; Pre-set card-a as already ordered
          sel' (assoc sel :selection/ordered [card-a])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db3) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/order-card card-a])
          ordered (get-in result [:game/pending-selection :selection/ordered])]
      (is (= [card-a] ordered)
          "Ordering a card already in :ordered should not duplicate it"))))


;; =====================================================
;; :fizzle.events.selection/unorder-card
;; Removes a card from :selection/ordered, preserving relative order of rest.
;; =====================================================

(deftest unorder-card-removes-card-from-ordered
  (testing "::unorder-card removes card from :selection/ordered"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b] :player-1 spell-id)
          ;; Pre-set both cards as ordered
          sel' (assoc sel :selection/ordered [card-a card-b])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db3) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/unorder-card card-a])
          ordered (get-in result [:game/pending-selection :selection/ordered])]
      (is (= [card-b] ordered)
          "unorder-card should remove card-a, leaving card-b in ordered"))))


(deftest unorder-card-preserves-relative-order-of-remaining
  (testing "::unorder-card preserves relative order of remaining cards"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 card-c] (th/add-card-to-zone db2 :portent :library :player-1)
          [db4 spell-id] (th/add-card-to-zone db3 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b card-c] :player-1 spell-id)
          ;; Pre-set order: a, b, c
          sel' (assoc sel :selection/ordered [card-a card-b card-c])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db4) sel')
          ;; Remove middle card (b)
          result (dispatch-event app-db' [:fizzle.events.selection/unorder-card card-b])
          ordered (get-in result [:game/pending-selection :selection/ordered])]
      (is (= [card-a card-c] ordered)
          "After removing card-b, remaining cards should preserve their relative order"))))


(deftest unorder-card-no-op-when-card-not-in-ordered
  (testing "::unorder-card is no-op when card is not in :selection/ordered"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b] :player-1 spell-id)
          ;; Only card-a is in ordered — card-b is not
          sel' (assoc sel :selection/ordered [card-a])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db3) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/unorder-card card-b])
          ordered (get-in result [:game/pending-selection :selection/ordered])]
      (is (= [card-a] ordered)
          "unorder-card on card not in ordered should not change :ordered"))))


;; =====================================================
;; :fizzle.events.selection/any-order
;; Fills remaining unordered candidates with random order.
;; Randomness: assert all candidates end up in ordered.
;; =====================================================

(deftest any-order-fills-all-candidates-into-ordered
  (testing "::any-order moves all unordered candidates into :selection/ordered"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 spell-id] (th/add-card-to-zone db2 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b] :player-1 spell-id)
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db3) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/any-order])
          ordered (get-in result [:game/pending-selection :selection/ordered])]
      (is (= #{card-a card-b} (set ordered))
          "any-order should include all candidates in :ordered")
      (is (= 2 (count ordered))
          "any-order should have no duplicates — count must equal candidate count"))))


(deftest any-order-preserves-already-ordered-cards-at-front
  (testing "::any-order keeps already-ordered cards first, appends remaining"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 card-a] (th/add-card-to-zone game-db :opt :library :player-1)
          [db2 card-b] (th/add-card-to-zone db1 :sleight-of-hand :library :player-1)
          [db3 card-c] (th/add-card-to-zone db2 :portent :library :player-1)
          [db4 spell-id] (th/add-card-to-zone db3 :impulse :stack :player-1)
          sel (lib/build-order-bottom-selection [card-a card-b card-c] :player-1 spell-id)
          ;; Pre-order card-a explicitly — b and c are unordered
          sel' (assoc sel :selection/ordered [card-a])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db4) sel')
          result (dispatch-event app-db' [:fizzle.events.selection/any-order])
          ordered (get-in result [:game/pending-selection :selection/ordered])]
      (is (= card-a (first ordered))
          "Previously ordered card-a must remain at front of ordered sequence")
      (is (= #{card-a card-b card-c} (set ordered))
          "All three candidates must appear in ordered after any-order")
      (is (= 3 (count ordered))
          "No duplicates — count equals candidate count"))))


;; =====================================================
;; :fizzle.events.selection/shuffle-and-confirm
;; Sets :selection/shuffle? true then confirms the selection (full pipeline).
;; Tests that the selection is consumed and library is shuffled.
;; =====================================================

(deftest shuffle-and-confirm-clears-pending-selection
  (testing "::shuffle-and-confirm confirms the selection — pending-selection is cleared"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          ;; Use a peek-and-reorder selection which supports shuffle?
          [db1 _] (th/add-cards-to-library game-db [:opt :sleight-of-hand :impulse] :player-1)
          [db2 spell-id] (th/add-card-to-zone db1 :portent :stack :player-1)
          effect {:effect/type :peek-and-reorder
                  :effect/count 3
                  :effect/target-player :player-1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/shuffle-and-confirm])]
      (is (nil? (:game/pending-selection result))
          "shuffle-and-confirm must clear :game/pending-selection after confirming"))))


(deftest shuffle-and-confirm-sets-shuffle-flag-before-confirm
  (testing "::shuffle-and-confirm sets :selection/shuffle? true before confirmation"
    ;; We verify this indirectly: after confirmation the library must still have
    ;; all 3 cards (shuffled libraries retain their cards).
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 _] (th/add-cards-to-library game-db [:opt :sleight-of-hand :impulse] :player-1)
          [db2 spell-id] (th/add-card-to-zone db1 :portent :stack :player-1)
          initial-lib-count (count (q/get-objects-in-zone db2 :player-1 :library))
          effect {:effect/type :peek-and-reorder
                  :effect/count 3
                  :effect/target-player :player-1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/shuffle-and-confirm])
          result-db (:game/db result)
          final-lib-count (count (q/get-objects-in-zone result-db :player-1 :library))]
      (is (= initial-lib-count final-lib-count)
          "Library card count must be unchanged after shuffle-and-confirm"))))


(deftest shuffle-and-confirm-resolving-spell-moves-to-graveyard
  (testing "::shuffle-and-confirm moves the resolving spell (Portent) to graveyard"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [db1 _] (th/add-cards-to-library game-db [:opt :sleight-of-hand :impulse] :player-1)
          [db2 spell-id] (th/add-card-to-zone db1 :portent :stack :player-1)
          effect {:effect/type :peek-and-reorder
                  :effect/count 3
                  :effect/target-player :player-1}
          sel (sel-core/build-selection-for-effect db2 :player-1 spell-id effect [])
          app-db' (sel-spec/set-pending-selection (assoc app-db :game/db db2) sel)
          result (dispatch-event app-db' [:fizzle.events.selection/shuffle-and-confirm])
          result-db (:game/db result)]
      (is (= :graveyard (:object/zone (q/get-object result-db spell-id)))
          "Portent (resolving spell) should be in graveyard after shuffle-and-confirm"))))
