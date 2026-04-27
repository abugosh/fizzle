(ns fizzle.events.pre-cast-pipeline-test
  "Tests for the data-driven pre-cast pipeline (ADR-015).
   Verifies that evaluate-pre-cast-step multimethod dispatches correctly
   and the pipeline loop produces correct results."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.casting :as casting]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Pipeline structure tests
;; =====================================================

(deftest pipeline-contains-all-pre-cast-steps-test
  (testing "pipeline contains all expected step keywords"
    (let [steps (set casting/pre-cast-pipeline)]
      (is (contains? steps :exile-cards-cost))
      (is (contains? steps :return-land-cost))
      (is (contains? steps :discard-specific-cost))
      (is (contains? steps :x-mana-cost))
      (is (contains? steps :targeting))
      (is (contains? steps :mana-allocation)))))


(deftest pipeline-ordering-test
  (testing "pipeline steps are in correct dependency order"
    (let [idx (fn [step] (.indexOf casting/pre-cast-pipeline step))]
      ;; Costs before targeting
      (is (< (idx :exile-cards-cost) (idx :targeting)))
      (is (< (idx :return-land-cost) (idx :targeting)))
      (is (< (idx :discard-specific-cost) (idx :targeting)))
      (is (< (idx :x-mana-cost) (idx :targeting)))
      ;; Targeting before mana allocation
      (is (< (idx :targeting) (idx :mana-allocation))))))


;; =====================================================
;; Step skip behavior (nil return = skip)
;; =====================================================

(deftest step-skips-when-condition-not-met-test
  (testing "Steps return nil for spells that don't have their cost type"
    (let [db (th/create-test-db)
          ;; Dark Ritual: {B} instant, no X costs, no targeting, no generic
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          modes (rules/get-casting-modes db :player-1 ritual-id)
          mode (first modes)
          ctx {:game-db db :player-id :player-1
               :object-id ritual-id :mode mode :target nil}]
      ;; All cost steps should return nil for a simple {B} spell
      (is (nil? (casting/evaluate-pre-cast-step :exile-cards-cost ctx)))
      (is (nil? (casting/evaluate-pre-cast-step :return-land-cost ctx)))
      (is (nil? (casting/evaluate-pre-cast-step :discard-specific-cost ctx)))
      (is (nil? (casting/evaluate-pre-cast-step :x-mana-cost ctx)))
      (is (nil? (casting/evaluate-pre-cast-step :targeting ctx))))))


;; =====================================================
;; Integration: pipeline produces same results as before
;; =====================================================

(deftest simple-spell-casts-through-pipeline-test
  (testing "Simple spell with no pre-cast steps casts immediately"
    (let [db (th/create-test-db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          app-db {:game/db db}
          result (casting/cast-spell-handler app-db {:object-id ritual-id})]
      ;; Should cast directly — no pending selections
      (is (nil? (:game/pending-selection result)))
      (is (= :stack (:object/zone (q/get-object (:game/db result) ritual-id)))))))


(deftest targeted-spell-shows-targeting-selection-test
  (testing "Targeted spell produces targeting selection through pipeline"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add a spell on stack for Counterspell to target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Add Counterspell
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          app-db {:game/db db}
          result (casting/cast-spell-handler app-db {:object-id cs-id})]
      ;; Should show targeting selection
      (is (some? (:game/pending-selection result)))
      (let [sel (:game/pending-selection result)]
        (is (= :cast-time-targeting (:selection/domain sel)))))))


(deftest generic-mana-shows-allocation-selection-test
  (testing "Spell with generic mana produces allocation selection through pipeline"
    (let [db (th/create-test-db)
          ;; Lotus Petal has colorless cost — actually, we need a spell with generic
          ;; Use Opt — {U} instant with no generic cost
          ;; Actually need a spell with generic mana cost
          ;; Let's use Brainstorm which is {U}, no generic
          ;; Need a card with generic in cost... Lion's Eye Diamond is {0}
          ;; Let's check what cards have generic mana...
          ;; Use a spell that has colorless/generic in its cost
          ;; Counterspell is {UU} — no generic
          ;; We can test with the mode_selection_test cards which have generic costs
          ;; Actually, the existing integration tests already cover this thoroughly
          ;; Let me test with Merchant Scroll {1U} which has generic
          [db scroll-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :black 1})
          app-db {:game/db db}
          result (casting/cast-spell-handler app-db {:object-id scroll-id})]
      ;; Should show mana allocation selection (has 1 generic)
      (is (some? (:game/pending-selection result)))
      (let [sel (:game/pending-selection result)]
        (is (= :mana-allocation (:selection/domain sel)))))))
