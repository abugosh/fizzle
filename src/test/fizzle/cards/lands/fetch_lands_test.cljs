(ns fizzle.cards.lands.fetch-lands-test
  "Tests for fetch land cycle — all 10 fetch lands.
   Each has: {T}, Pay 1 life, Sacrifice this land: Search your library for
   a [Type A] or [Type B] card, put it onto the battlefield, then shuffle."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.lands.fetch-lands :as fetch-lands]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; Fetch land cycle data: [def card-id name subtype-a subtype-b]
(def fetch-land-cycle
  [[fetch-lands/flooded-strand :flooded-strand "Flooded Strand" :plains :island]
   [fetch-lands/polluted-delta :polluted-delta "Polluted Delta" :island :swamp]
   [fetch-lands/bloodstained-mire :bloodstained-mire "Bloodstained Mire" :swamp :mountain]
   [fetch-lands/wooded-foothills :wooded-foothills "Wooded Foothills" :mountain :forest]
   [fetch-lands/windswept-heath :windswept-heath "Windswept Heath" :forest :plains]
   [fetch-lands/verdant-catacombs :verdant-catacombs "Verdant Catacombs" :swamp :forest]
   [fetch-lands/scalding-tarn :scalding-tarn "Scalding Tarn" :island :mountain]
   [fetch-lands/misty-rainforest :misty-rainforest "Misty Rainforest" :forest :island]
   [fetch-lands/arid-mesa :arid-mesa "Arid Mesa" :mountain :plains]
   [fetch-lands/marsh-flats :marsh-flats "Marsh Flats" :plains :swamp]])


;; === Card definition tests (cycle-wide via doseq) ===

(deftest fetch-land-card-definitions-test
  (doseq [[card card-id card-name subtype-a subtype-b] fetch-land-cycle]
    (testing (str card-name " has correct card fields")
      (is (= card-id (:card/id card)))
      (is (= card-name (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (= 1 (count (:card/abilities card)))
          "Should have exactly 1 activated ability")
      (let [ability (first (:card/abilities card))]
        (is (= :activated (:ability/type ability)))
        ;; Cost structure
        (is (true? (get-in ability [:ability/cost :tap])))
        (is (true? (get-in ability [:ability/cost :sacrifice-self])))
        (is (= 1 (get-in ability [:ability/cost :pay-life])))
        ;; Effect structure
        (is (= 1 (count (:ability/effects ability))))
        (let [effect (first (:ability/effects ability))]
          (is (= :tutor (:effect/type effect)))
          (is (= #{subtype-a subtype-b} (get-in effect [:effect/criteria :match/subtypes])))
          (is (= :battlefield (:effect/target-zone effect)))
          (is (true? (:effect/shuffle? effect))))))))


(deftest fetch-land-cards-vector-test
  (testing "cards vector contains exactly 10 fetch lands"
    (is (= 10 (count fetch-lands/cards)))
    (is (= #{:flooded-strand :polluted-delta :bloodstained-mire :wooded-foothills
             :windswept-heath :verdant-catacombs :scalding-tarn :misty-rainforest
             :arid-mesa :marsh-flats}
           (set (map :card/id fetch-lands/cards))))))


;; === Activation cost tests (cycle-wide via doseq) ===

(deftest fetch-land-sacrifice-on-activation-test
  (doseq [[_card card-id card-name _subtype-a subtype-b] fetch-land-cycle]
    (testing (str card-name " sacrifices immediately on activation")
      (let [db (th/create-test-db)
            [db' fetch-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            ;; Add a valid target to library (use subtype-b as basic land)
            [db'' _] (th/add-cards-to-library db' [subtype-b] :player-1)
            _ (is (= :battlefield (th/get-object-zone db'' fetch-id))
                  "Precondition: fetch land on battlefield")
            result (ability-events/activate-ability db'' :player-1 fetch-id 0)
            db-after (:db result)]
        (is (= :graveyard (th/get-object-zone db-after fetch-id))
            "Fetch land should be sacrificed immediately as cost")
        (is (false? (q/stack-empty? db-after))
            "Stack-item should be on stack awaiting resolution")))))


(deftest fetch-land-life-cost-on-activation-test
  (doseq [[_card card-id card-name _subtype-a subtype-b] fetch-land-cycle]
    (testing (str card-name " pays 1 life on activation")
      (let [db (th/create-test-db)
            [db' fetch-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            [db'' _] (th/add-cards-to-library db' [subtype-b] :player-1)
            _ (is (= 20 (q/get-life-total db'' :player-1))
                  "Precondition: player has 20 life")
            result (ability-events/activate-ability db'' :player-1 fetch-id 0)
            db-after (:db result)]
        (is (= 19 (q/get-life-total db-after :player-1))
            "Player should have paid 1 life as cost")))))


(deftest fetch-land-creates-stack-item-test
  (doseq [[_card card-id card-name _subtype-a subtype-b] fetch-land-cycle]
    (testing (str card-name " creates activated-ability stack-item")
      (let [db (th/create-test-db)
            [db' fetch-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            [db'' _] (th/add-cards-to-library db' [subtype-b] :player-1)
            _ (is (true? (q/stack-empty? db''))
                  "Precondition: stack is empty")
            result (ability-events/activate-ability db'' :player-1 fetch-id 0)
            db-after (:db result)
            item (stack/get-top-stack-item db-after)]
        (is (= :activated-ability (:stack-item/type item))
            "Stack-item should have type :activated-ability")))))


;; === Resolution tests (cycle-wide via doseq) ===

(deftest fetch-land-resolves-to-tutor-selection-test
  (doseq [[_card card-id card-name subtype-a subtype-b] fetch-land-cycle]
    (testing (str card-name " resolves to tutor selection")
      (let [db (th/create-test-db)
            [db' fetch-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            ;; Add both target types to library
            [db'' [target-a-id target-b-id]] (th/add-cards-to-library db' [subtype-a subtype-b] :player-1)
            result (ability-events/activate-ability db'' :player-1 fetch-id 0)
            db-after-activate (:db result)
            resolve-result (resolution/resolve-one-item db-after-activate)]
        (is (= :tutor (get-in resolve-result [:pending-selection :selection/type]))
            "Selection should be tutor type")
        (let [candidates (:selection/candidates (:pending-selection resolve-result))]
          (is (contains? candidates target-a-id)
              (str (name subtype-a) " should be a valid candidate"))
          (is (contains? candidates target-b-id)
              (str (name subtype-b) " should be a valid candidate")))))))


;; === Selection criteria tests (cycle-wide via doseq) ===

(deftest fetch-land-criteria-finds-matching-subtypes-test
  (doseq [[_card _card-id card-name subtype-a subtype-b] fetch-land-cycle]
    (testing (str card-name " criteria finds " (name subtype-a) " and " (name subtype-b))
      (let [db (th/create-test-db)
            [db' _] (th/add-cards-to-library db [subtype-a subtype-b :dark-ritual] :player-1)
            results (q/query-library-by-criteria db' :player-1
                                                 {:match/subtypes #{subtype-a subtype-b}})]
        (is (= 2 (count results))
            "Should find exactly 2 matching cards")
        (is (= #{subtype-a subtype-b}
               (set (map #(get-in % [:object/card :card/id]) results)))
            "Should find both matching basic lands")))))


(deftest fetch-land-criteria-excludes-non-matching-test
  (doseq [[_card _card-id card-name subtype-a subtype-b] fetch-land-cycle]
    (testing (str card-name " criteria excludes non-matching cards")
      (let [db (th/create-test-db)
            [db' _] (th/add-cards-to-library db [:city-of-brass :dark-ritual] :player-1)
            results (q/query-library-by-criteria db' :player-1
                                                 {:match/subtypes #{subtype-a subtype-b}})]
        (is (empty? results)
            "Should not find cards without matching subtypes")))))


;; === Tutor to battlefield tests ===

(deftest fetch-land-tutor-moves-card-to-battlefield-test
  (testing "Completing tutor selection moves card to battlefield"
    (let [db (th/create-test-db)
          [db fetch-id] (th/add-card-to-zone db :flooded-strand :battlefield :player-1)
          [db [island-id]] (th/add-cards-to-library db [:island] :player-1)
          result (ability-events/activate-ability db :player-1 fetch-id 0)
          resolve-result (resolution/resolve-one-item (:db result))
          selection (:pending-selection resolve-result)
          {:keys [db]} (th/confirm-selection (:db resolve-result) selection #{island-id})]
      (is (= :battlefield (th/get-object-zone db island-id))
          "Selected card should move to battlefield"))))


;; === Cannot-activate guard tests ===

(deftest fetch-land-cannot-activate-from-graveyard-test
  (testing "Fetch land in graveyard cannot activate ability"
    (let [db (th/create-test-db)
          [db fetch-id] (th/add-card-to-zone db :flooded-strand :graveyard :player-1)
          _ (is (= :graveyard (th/get-object-zone db fetch-id))
                "Precondition: fetch land is in graveyard")
          initial-life (q/get-life-total db :player-1)
          result (ability-events/activate-ability db :player-1 fetch-id 0)]
      (is (= :graveyard (th/get-object-zone (:db result) fetch-id))
          "Fetch land should remain in graveyard")
      (is (= initial-life (q/get-life-total (:db result) :player-1))
          "No life paid (ability did not activate)")
      (is (true? (q/stack-empty? (:db result)))
          "Stack should be empty (ability did not activate"))))


;; === Fail-to-find tests ===

(deftest fetch-land-fail-to-find-test
  (testing "Fail-to-find when no matching targets leaves library unchanged"
    (let [db (th/create-test-db)
          [db fetch-id] (th/add-card-to-zone db :flooded-strand :battlefield :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :brain-freeze] :player-1)
          library-before (th/get-zone-count db :library :player-1)
          result (ability-events/activate-ability db :player-1 fetch-id 0)
          resolve-result (resolution/resolve-one-item (:db result))
          selection (:pending-selection resolve-result)
          {:keys [db]} (th/confirm-selection (:db resolve-result) selection #{})]
      (is (= library-before (th/get-zone-count db :library :player-1))
          "Library count should not change on fail-to-find"))))
