(ns fizzle.cards.lands.gemstone-mine-test
  "Tests for Gemstone Mine - rainbow land with mining counter depletion.
   T, Remove a mining counter: Add one mana of any color.
   Enters with 3 mining counters. Sacrifice when no counters remain
   (as part of ability resolution, NOT state-based action)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.lands.gemstone-mine :as gemstone-mine]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as th]))


;; === Card definition tests ===

(deftest gemstone-mine-card-definition-test
  (testing "Gemstone Mine card data is correct"
    (let [card gemstone-mine/card]
      (is (= :gemstone-mine (:card/id card)))
      (is (= "Gemstone Mine" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))))

  (testing "ETB effects add 3 mining counters"
    (let [etb-effects (:card/etb-effects gemstone-mine/card)]
      (is (= 1 (count etb-effects))
          "Should have exactly one ETB effect")
      (let [effect (first etb-effects)]
        (is (= :add-counters (:effect/type effect)))
        (is (= {:mining 3} (:effect/counters effect)))
        (is (= :self (:effect/target effect))))))

  (testing "Mana ability structure"
    (let [abilities (:card/abilities gemstone-mine/card)]
      (is (= 1 (count abilities))
          "Should have exactly one ability")
      (let [ability (first abilities)]
        (is (= :mana (:ability/type ability)))
        (is (= {:any 1} (:ability/produces ability))
            "Should produce 1 mana of any color")
        (is (true? (get-in ability [:ability/cost :tap]))
            "Should require tap")
        (is (= {:mining 1} (get-in ability [:ability/cost :remove-counter]))
            "Should require removing 1 mining counter"))))

  (testing "Sacrifice condition on ability"
    (let [ability (first (:card/abilities gemstone-mine/card))
          effects (:ability/effects ability)]
      (is (= 1 (count effects))
          "Ability should have one conditional effect")
      (let [effect (first effects)]
        (is (= :sacrifice (:effect/type effect)))
        (is (= :self (:effect/target effect)))
        (is (= :no-counters (get-in effect [:effect/condition :condition/type])))
        (is (= :mining (get-in effect [:effect/condition :condition/counter-type])))))))


;; === Parameterized color mana tests ===

(def ^:private mana-colors [:black :blue :white :red :green])


(deftest gemstone-mine-tap-for-any-color-test
  (doseq [color mana-colors]
    (testing (str "Gemstone Mine taps for " (name color) " mana")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
            ;; Manually add mining counters (add-card-to-zone doesn't run ETB)
            conn (d/conn-from-db db')
            obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                         db' obj-id)
            _ (d/transact! conn [[:db/add obj-eid :object/counters {:mining 3}]])
            db-with-counters @conn
            initial-pool (q/get-mana-pool db-with-counters :player-1)
            _ (is (= 0 (get initial-pool color))
                  (str "Precondition: " (name color) " mana is 0"))
            db'' (engine-mana/activate-mana-ability db-with-counters :player-1 obj-id color)]
        (is (= 1 (get (q/get-mana-pool db'' :player-1) color))
            (str (name color) " mana should be added to pool"))
        (is (= {:mining 2} (:object/counters (q/get-object db'' obj-id)))
            (str "Should have 2 mining counters after tapping for " (name color)))
        (is (= :battlefield (th/get-object-zone db'' obj-id))
            (str "Should remain on battlefield after tapping for " (name color)))))))


;; === Counter depletion tests ===

(deftest gemstone-mine-counter-depletion-test
  (testing "Activation with 3 counters leaves 2"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          conn (d/conn-from-db db')
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db' obj-id)
          _ (d/transact! conn [[:db/add obj-eid :object/counters {:mining 3}]])
          db'' (engine-mana/activate-mana-ability @conn :player-1 obj-id :black)]
      (is (= {:mining 2} (:object/counters (q/get-object db'' obj-id)))
          "Should have 2 mining counters after activation")
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Should still be on battlefield")))

  (testing "Activation with 2 counters leaves 1"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          conn (d/conn-from-db db')
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db' obj-id)
          _ (d/transact! conn [[:db/add obj-eid :object/counters {:mining 2}]])
          db'' (engine-mana/activate-mana-ability @conn :player-1 obj-id :blue)]
      (is (= {:mining 1} (:object/counters (q/get-object db'' obj-id)))
          "Should have 1 mining counter after activation")
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Should still be on battlefield")))

  (testing "Activation with 1 counter sacrifices"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          conn (d/conn-from-db db')
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db' obj-id)
          _ (d/transact! conn [[:db/add obj-eid :object/counters {:mining 1}]])
          db'' (engine-mana/activate-mana-ability @conn :player-1 obj-id :green)]
      (is (= :graveyard (th/get-object-zone db'' obj-id))
          "Should be in graveyard after last counter removed")
      (is (= 1 (:green (q/get-mana-pool db'' :player-1)))
          "Mana should still be produced on sacrifice tap"))))


(deftest gemstone-mine-still-alive-with-counters-test
  (testing "Gemstone Mine with 2+ counters remains on battlefield after activation"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          conn (d/conn-from-db db')
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db' obj-id)
          _ (d/transact! conn [[:db/add obj-eid :object/counters {:mining 2}]])
          db'' (engine-mana/activate-mana-ability @conn :player-1 obj-id :black)]
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Should remain on battlefield with counters remaining")
      (is (= {:mining 1} (:object/counters (q/get-object db'' obj-id)))
          "Should have 1 mining counter remaining"))))


(deftest gemstone-mine-mana-produced-on-sacrifice-tap-test
  (testing "Mana IS produced even on the sacrifice tap (last counter)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          conn (d/conn-from-db db')
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db' obj-id)
          ;; Start with 1 counter (final activation)
          _ (d/transact! conn [[:db/add obj-eid :object/counters {:mining 1}]])
          db-tap (engine-mana/activate-mana-ability @conn :player-1 obj-id :red)]
      (is (= 1 (:red (q/get-mana-pool db-tap :player-1)))
          "Red mana should be produced before sacrifice")
      (is (= :graveyard (th/get-object-zone db-tap obj-id))
          "Gemstone Mine should be in graveyard after last counter"))))


;; === Edge case tests ===

(deftest gemstone-mine-cannot-activate-from-graveyard-test
  (testing "Gemstone Mine in graveyard cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :graveyard :player-1)
          _ (is (= :graveyard (th/get-object-zone db' obj-id))
                "Precondition: Gemstone Mine is in graveyard")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :black)]
      (is (= 0 (:black (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (card in graveyard)")
      (is (= :graveyard (th/get-object-zone db'' obj-id))
          "Gemstone Mine should remain in graveyard"))))


(deftest gemstone-mine-cannot-activate-from-hand-test
  (testing "Gemstone Mine in hand cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :hand :player-1)
          _ (is (= :hand (th/get-object-zone db' obj-id))
                "Precondition: Gemstone Mine is in hand")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:green initial-pool)) "Precondition: green mana is 0")
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :green)]
      (is (= 0 (:green (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (card not on battlefield)")
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Gemstone Mine should remain in hand"))))


(deftest gemstone-mine-cannot-activate-when-tapped-test
  (testing "Gemstone Mine already tapped cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          conn (d/conn-from-db db')
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db' obj-id)
          _ (d/transact! conn [[:db/add obj-eid :object/counters {:mining 3}]])
          ;; First tap succeeds
          db-tap1 (engine-mana/activate-mana-ability @conn :player-1 obj-id :black)
          _ (is (= 1 (:black (q/get-mana-pool db-tap1 :player-1)))
                "Precondition: first tap adds mana")
          ;; Second tap (still tapped) should fail
          db-tap2 (engine-mana/activate-mana-ability db-tap1 :player-1 obj-id :blue)]
      (is (= 1 (:black (q/get-mana-pool db-tap2 :player-1)))
          "Black mana unchanged after failed second tap")
      (is (= 0 (:blue (q/get-mana-pool db-tap2 :player-1)))
          "Blue mana should NOT be added (already tapped)")
      (is (= {:mining 2} (:object/counters (q/get-object db-tap2 obj-id)))
          "Counter should not be removed by failed activation"))))


(deftest gemstone-mine-etb-counter-placement-test
  (testing "Playing Gemstone Mine from hand places 3 mining counters via ETB"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :hand :player-1)
          _ (is (= :hand (th/get-object-zone db' obj-id))
                "Precondition: Gemstone Mine starts in hand")
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Gemstone Mine should be on battlefield after playing")
      (is (= {:mining 3} (:object/counters (q/get-object db'' obj-id)))
          "Gemstone Mine should enter with 3 mining counters"))))


(deftest gemstone-mine-no-life-loss-test
  (testing "Gemstone Mine does NOT deal damage when tapped (unlike City of Brass)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          conn (d/conn-from-db db')
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db' obj-id)
          _ (d/transact! conn [[:db/add obj-eid :object/counters {:mining 3}]])
          initial-life (q/get-life-total @conn :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          db'' (engine-mana/activate-mana-ability @conn :player-1 obj-id :green)]
      (is (= 20 (q/get-life-total db'' :player-1))
          "Player should NOT lose life when tapping Gemstone Mine"))))
