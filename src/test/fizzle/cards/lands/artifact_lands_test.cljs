(ns fizzle.cards.lands.artifact-lands-test
  "Tests for Mirrodin artifact land cycle.

   Ancient Den, Seat of the Synod, Vault of Whispers,
   Great Furnace, Tree of Tales.

   Each is an Artifact Land that taps for one color of mana.
   Oracle: {T}: Add {COLOR}."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.lands.artifact-lands :as artifact-lands]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.events.lands :as lands]
    [fizzle.test-helpers :as th]))


;; === Card definition tests ===

;; Oracle: "{T}: Add {W}."
(deftest ancient-den-card-definition-test
  (testing "Ancient Den base card data is correct"
    (let [card artifact-lands/ancient-den]
      (is (= :ancient-den (:card/id card)))
      (is (= "Ancient Den" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:artifact :land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (= "{T}: Add {W}." (:card/text card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= [{:effect/type :add-mana :effect/mana {:white 1}}]
             (:ability/effects (first (:card/abilities card))))))))


;; Oracle: "{T}: Add {U}."
(deftest seat-of-the-synod-card-definition-test
  (testing "Seat of the Synod base card data is correct"
    (let [card artifact-lands/seat-of-the-synod]
      (is (= :seat-of-the-synod (:card/id card)))
      (is (= "Seat of the Synod" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:artifact :land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (= "{T}: Add {U}." (:card/text card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= [{:effect/type :add-mana :effect/mana {:blue 1}}]
             (:ability/effects (first (:card/abilities card))))))))


;; Oracle: "{T}: Add {B}."
(deftest vault-of-whispers-card-definition-test
  (testing "Vault of Whispers base card data is correct"
    (let [card artifact-lands/vault-of-whispers]
      (is (= :vault-of-whispers (:card/id card)))
      (is (= "Vault of Whispers" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:artifact :land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (= "{T}: Add {B}." (:card/text card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= [{:effect/type :add-mana :effect/mana {:black 1}}]
             (:ability/effects (first (:card/abilities card))))))))


;; Oracle: "{T}: Add {R}."
(deftest great-furnace-card-definition-test
  (testing "Great Furnace base card data is correct"
    (let [card artifact-lands/great-furnace]
      (is (= :great-furnace (:card/id card)))
      (is (= "Great Furnace" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:artifact :land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (= "{T}: Add {R}." (:card/text card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= [{:effect/type :add-mana :effect/mana {:red 1}}]
             (:ability/effects (first (:card/abilities card))))))))


;; Oracle: "{T}: Add {G}."
(deftest tree-of-tales-card-definition-test
  (testing "Tree of Tales base card data is correct"
    (let [card artifact-lands/tree-of-tales]
      (is (= :tree-of-tales (:card/id card)))
      (is (= "Tree of Tales" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:artifact :land} (:card/types card)))
      (is (nil? (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (= "{T}: Add {G}." (:card/text card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= [{:effect/type :add-mana :effect/mana {:green 1}}]
             (:ability/effects (first (:card/abilities card))))))))


(deftest artifact-lands-cycle-completeness-test
  (testing "cards vector contains all 5 artifact lands"
    (is (= 5 (count artifact-lands/cards)))
    (is (= #{:ancient-den :seat-of-the-synod :vault-of-whispers
             :great-furnace :tree-of-tales}
           (set (map :card/id artifact-lands/cards))))))


;; === Mana ability activation tests ===

(def ^:private artifact-land-specs
  [{:card-id :ancient-den :color :white :name "Ancient Den"}
   {:card-id :seat-of-the-synod :color :blue :name "Seat of the Synod"}
   {:card-id :vault-of-whispers :color :black :name "Vault of Whispers"}
   {:card-id :great-furnace :color :red :name "Great Furnace"}
   {:card-id :tree-of-tales :color :green :name "Tree of Tales"}])


;; Oracle: "{T}: Add {COLOR}."
(deftest artifact-lands-tap-for-mana-test
  (doseq [{:keys [card-id color name]} artifact-land-specs]
    (testing (str name " taps for " (clojure.core/name color) " mana")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            initial-pool (q/get-mana-pool db' :player-1)
            _ (is (= 0 (get initial-pool color))
                  (str "Precondition: " (clojure.core/name color) " mana is 0"))
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color)]
        (is (= 1 (get (q/get-mana-pool db'' :player-1) color))
            (str (clojure.core/name color) " mana should be added to pool"))
        (is (true? (:object/tapped (q/get-object db'' obj-id)))
            (str name " should be tapped after activation"))))))


;; === Play from hand tests ===

;; Oracle: Artifact lands are played as land drops (they have the land type)
(deftest artifact-lands-play-from-hand-test
  (doseq [{:keys [card-id name]} artifact-land-specs]
    (testing (str name " can be played from hand as a land drop")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            _ (is (= :hand (th/get-object-zone db' obj-id))
                  (str "Precondition: " name " starts in hand"))
            db'' (lands/play-land db' :player-1 obj-id)]
        (is (= :battlefield (th/get-object-zone db'' obj-id))
            (str name " should be on battlefield after play"))))))


;; === Cannot-activate guard tests ===

;; Oracle: Mana ability requires tapping — must be on battlefield and untapped
(deftest artifact-lands-cannot-activate-from-hand-test
  (doseq [{:keys [card-id color name]} artifact-land-specs]
    (testing (str name " in hand cannot activate mana ability")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color)]
        (is (= 0 (get (q/get-mana-pool db'' :player-1) color))
            "Mana should NOT be added (card not on battlefield)")
        (is (= :hand (th/get-object-zone db'' obj-id))
            (str name " should remain in hand"))))))


(deftest artifact-lands-cannot-activate-from-graveyard-test
  (doseq [{:keys [card-id color name]} artifact-land-specs]
    (testing (str name " in graveyard cannot activate mana ability")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :graveyard :player-1)
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color)]
        (is (= 0 (get (q/get-mana-pool db'' :player-1) color))
            "Mana should NOT be added (card in graveyard)")))))


(deftest artifact-lands-cannot-activate-when-tapped-test
  (doseq [{:keys [card-id color name]} artifact-land-specs]
    (testing (str name " already tapped cannot activate again")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            db-tap1 (engine-mana/activate-mana-ability db' :player-1 obj-id color)
            _ (is (= 1 (get (q/get-mana-pool db-tap1 :player-1) color))
                  "Precondition: first tap adds mana")
            db-tap2 (engine-mana/activate-mana-ability db-tap1 :player-1 obj-id color)]
        (is (= 1 (get (q/get-mana-pool db-tap2 :player-1) color))
            "Second tap should NOT add more mana")))))


;; === Edge case: artifact type is preserved on battlefield ===

;; Oracle: Type line is "Artifact Land" — both types must be present on the object
(deftest artifact-lands-have-artifact-type-on-battlefield-test
  (doseq [{:keys [card-id name]} artifact-land-specs]
    (testing (str name " on battlefield has both :artifact and :land types")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            obj (q/get-object db' obj-id)
            types (set (:card/types (:object/card obj)))]
        (is (contains? types :artifact)
            (str name " should have :artifact type"))
        (is (contains? types :land)
            (str name " should have :land type"))))))
