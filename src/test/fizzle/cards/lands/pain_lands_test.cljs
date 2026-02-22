(ns fizzle.cards.lands.pain-lands-test
  "Tests for pain land cycle — all 10 pain lands.
   Each has: {T}: Add {C}. {T}: Add {X} or {Y}. This land deals 1 damage to you."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.lands.pain-lands :as pain-lands]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.test-helpers :as th]))


;; Pain land cycle data: [def card-id name color-a color-b]
(def pain-land-cycle
  [[pain-lands/adarkar-wastes :adarkar-wastes "Adarkar Wastes" :white :blue]
   [pain-lands/battlefield-forge :battlefield-forge "Battlefield Forge" :red :white]
   [pain-lands/brushland :brushland "Brushland" :green :white]
   [pain-lands/caves-of-koilos :caves-of-koilos "Caves of Koilos" :white :black]
   [pain-lands/karplusan-forest :karplusan-forest "Karplusan Forest" :red :green]
   [pain-lands/llanowar-wastes :llanowar-wastes "Llanowar Wastes" :black :green]
   [pain-lands/shivan-reef :shivan-reef "Shivan Reef" :blue :red]
   [pain-lands/sulfurous-springs :sulfurous-springs "Sulfurous Springs" :black :red]
   [pain-lands/underground-river :underground-river "Underground River" :blue :black]
   [pain-lands/yavimaya-coast :yavimaya-coast "Yavimaya Coast" :green :blue]])


;; === Card definition tests (cycle-wide via doseq) ===

(deftest pain-land-card-definitions-test
  (doseq [[card card-id card-name color-a color-b] pain-land-cycle]
    (testing (str card-name " has correct card fields")
      (is (= card-id (:card/id card)))
      (is (= card-name (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (= 3 (count (:card/abilities card)))
          "Should have 3 mana abilities (colorless + 2 colors)")
      ;; First ability: colorless, no damage
      (let [colorless-ability (first (:card/abilities card))]
        (is (= :mana (:ability/type colorless-ability)))
        (is (= {:colorless 1} (:ability/produces colorless-ability)))
        (is (nil? (:ability/effects colorless-ability))
            "Colorless ability should have no pain effect"))
      ;; Second ability: color-a, deals damage
      (let [color-a-ability (second (:card/abilities card))]
        (is (= :mana (:ability/type color-a-ability)))
        (is (= {color-a 1} (:ability/produces color-a-ability)))
        (is (= 1 (count (:ability/effects color-a-ability))))
        (is (= :deal-damage (:effect/type (first (:ability/effects color-a-ability))))))
      ;; Third ability: color-b, deals damage
      (let [color-b-ability (nth (:card/abilities card) 2)]
        (is (= :mana (:ability/type color-b-ability)))
        (is (= {color-b 1} (:ability/produces color-b-ability)))
        (is (= 1 (count (:ability/effects color-b-ability))))
        (is (= :deal-damage (:effect/type (first (:ability/effects color-b-ability)))))))))


(deftest pain-land-cards-vector-test
  (testing "cards vector contains exactly 10 pain lands"
    (is (= 10 (count pain-lands/cards)))
    (is (= #{:adarkar-wastes :battlefield-forge :brushland :caves-of-koilos
             :karplusan-forest :llanowar-wastes :shivan-reef :sulfurous-springs
             :underground-river :yavimaya-coast}
           (set (map :card/id pain-lands/cards))))))


;; === Mana ability integration tests (cycle-wide via doseq) ===

(deftest pain-land-colorless-no-damage-test
  (doseq [[_card card-id card-name _color-a _color-b] pain-land-cycle]
    (testing (str card-name " tapping for colorless does not deal damage")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless)]
        (is (= 1 (:colorless (q/get-mana-pool db'' :player-1)))
            "Should produce 1 colorless mana")
        (is (= 20 (q/get-life-total db'' :player-1))
            "No damage from colorless ability")
        (is (true? (:object/tapped (q/get-object db'' obj-id)))
            "Land should be tapped")))))


(deftest pain-land-color-a-deals-damage-test
  (doseq [[_card card-id card-name color-a _color-b] pain-land-cycle]
    (testing (str card-name " tapping for " (name color-a) " deals 1 damage")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color-a)]
        (is (= 1 (get (q/get-mana-pool db'' :player-1) color-a))
            (str "Should produce 1 " (name color-a) " mana"))
        (is (= 19 (q/get-life-total db'' :player-1))
            "Should take 1 damage from pain land")
        (is (true? (:object/tapped (q/get-object db'' obj-id)))
            "Land should be tapped")))))


(deftest pain-land-color-b-deals-damage-test
  (doseq [[_card card-id card-name _color-a color-b] pain-land-cycle]
    (testing (str card-name " tapping for " (name color-b) " deals 1 damage")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color-b)]
        (is (= 1 (get (q/get-mana-pool db'' :player-1) color-b))
            (str "Should produce 1 " (name color-b) " mana"))
        (is (= 19 (q/get-life-total db'' :player-1))
            "Should take 1 damage from pain land")
        (is (true? (:object/tapped (q/get-object db'' obj-id)))
            "Land should be tapped")))))


;; === Edge case: cannot activate when tapped ===

(deftest pain-land-cannot-activate-when-tapped-test
  (doseq [[_card card-id card-name _color-a color-b] pain-land-cycle]
    (testing (str card-name " cannot activate mana ability when already tapped")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db card-id :battlefield :player-1)
            ;; First activation succeeds (colorless)
            db-first (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless)
            _ (is (true? (:object/tapped (q/get-object db-first obj-id)))
                  "Precondition: land is tapped after first activation")
            ;; Second activation should fail
            db-second (engine-mana/activate-mana-ability db-first :player-1 obj-id color-b)]
        (is (= 1 (:colorless (q/get-mana-pool db-second :player-1)))
            "Colorless mana unchanged from first activation")
        (is (= 0 (get (q/get-mana-pool db-second :player-1) color-b))
            "No colored mana added from failed second activation")
        (is (= 20 (q/get-life-total db-second :player-1))
            "No damage from failed second activation")))))
