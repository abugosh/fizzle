(ns fizzle.bots.rules-test
  (:require
    [cljs.test :refer-macros [deftest is]]
    [datascript.core :as d]
    [fizzle.bots.definitions :as defs]
    [fizzle.bots.rules :as rules]
    [fizzle.cards.red.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.test-helpers :as th]))


;; === zone-contains ===

(deftest zone-contains-true-when-card-in-hand
  (let [db (th/create-test-db)
        [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :zone-contains :zone :hand :player :self :card-id :dark-ritual}
                 ctx)))))


(deftest zone-contains-false-when-card-not-in-hand
  (let [db (th/create-test-db)
        ctx {:db db :player-id :player-1}]
    (is (false? (rules/evaluate-condition
                  {:check :zone-contains :zone :hand :player :self :card-id :dark-ritual}
                  ctx)))))


(deftest zone-contains-resolves-opponent
  (let [db (-> (th/create-test-db)
               (th/add-opponent))
        [db _] (th/add-card-to-zone db :dark-ritual :hand :player-2)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :zone-contains :zone :hand :player :opponent :card-id :dark-ritual}
                 ctx)))))


(deftest zone-contains-checks-battlefield
  (let [db (th/create-test-db)
        [db _] (th/add-card-to-zone db :mountain :battlefield :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :zone-contains :zone :battlefield :player :self :card-id :mountain}
                 ctx)))))


;; === has-mana-for ===

(deftest has-mana-for-true-with-sufficient-mana
  (let [db (th/create-test-db {:mana {:black 1}})
        [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :has-mana-for :card-id :dark-ritual}
                 ctx)))))


(deftest has-mana-for-false-with-insufficient-mana
  (let [db (th/create-test-db {:mana {:black 0}})
        [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
        ctx {:db db :player-id :player-1}]
    (is (false? (rules/evaluate-condition
                  {:check :has-mana-for :card-id :dark-ritual}
                  ctx)))))


;; === stack-empty ===

(deftest stack-empty-true-when-no-items
  (let [db (th/create-test-db)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :stack-empty}
                 ctx)))))


(deftest stack-empty-false-when-spell-on-stack
  (let [db (th/create-test-db)
        player-eid (q/get-player-eid db :player-1)
        db (stack/create-stack-item db {:stack-item/type :spell
                                        :stack-item/controller player-eid
                                        :stack-item/description "test spell"})
        ctx {:db db :player-id :player-1}]
    (is (false? (rules/evaluate-condition
                  {:check :stack-empty}
                  ctx)))))


;; === has-untapped-source ===

(deftest has-untapped-source-true-with-untapped-mountain
  (let [db (th/create-test-db)
        [db _] (th/add-card-to-zone db :mountain :battlefield :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :has-untapped-source :color :red}
                 ctx)))))


(deftest has-untapped-source-false-when-tapped
  (let [db (th/create-test-db)
        [db obj-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
        obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
        db (d/db-with db [[:db/add obj-eid :object/tapped true]])
        ctx {:db db :player-id :player-1}]
    (is (false? (rules/evaluate-condition
                  {:check :has-untapped-source :color :red}
                  ctx)))))


(deftest has-untapped-source-false-when-wrong-color
  (let [db (th/create-test-db)
        [db _] (th/add-card-to-zone db :island :battlefield :player-1)
        ctx {:db db :player-id :player-1}]
    (is (false? (rules/evaluate-condition
                  {:check :has-untapped-source :color :red}
                  ctx)))))


;; === zone-count ===

(deftest zone-count-gte-true-when-enough
  (let [db (th/create-test-db)
        [db _] (th/add-cards-to-graveyard db (repeat 7 :dark-ritual) :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :zone-count :zone :graveyard :player :self :gte 7}
                 ctx)))))


(deftest zone-count-gte-false-when-not-enough
  (let [db (th/create-test-db)
        [db _] (th/add-cards-to-graveyard db (repeat 5 :dark-ritual) :player-1)
        ctx {:db db :player-id :player-1}]
    (is (false? (rules/evaluate-condition
                  {:check :zone-count :zone :graveyard :player :self :gte 7}
                  ctx)))))


(deftest zone-count-eq-true-when-exact
  (let [db (th/create-test-db)
        [db _] (th/add-cards-to-graveyard db (repeat 3 :dark-ritual) :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :zone-count :zone :graveyard :player :self :eq 3}
                 ctx)))))


(deftest zone-count-lte-true-when-under
  (let [db (th/create-test-db)
        [db _] (th/add-cards-to-graveyard db (repeat 2 :dark-ritual) :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :zone-count :zone :graveyard :player :self :lte 5}
                 ctx)))))


;; === stack-has ===

(deftest stack-has-true-when-own-trigger-on-stack
  (let [db (th/create-test-db)
        db (stack/create-stack-item db {:stack-item/type :permanent-tapped
                                        :stack-item/controller :player-1
                                        :stack-item/description "trigger"})
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :stack-has :owner :self :type :permanent-tapped}
                 ctx)))))


(deftest stack-has-false-when-wrong-type
  (let [db (th/create-test-db)
        db (stack/create-stack-item db {:stack-item/type :spell
                                        :stack-item/controller :player-1
                                        :stack-item/description "spell"})
        ctx {:db db :player-id :player-1}]
    (is (false? (rules/evaluate-condition
                  {:check :stack-has :owner :self :type :permanent-tapped}
                  ctx)))))


(deftest stack-has-resolves-opponent
  (let [db (-> (th/create-test-db)
               (th/add-opponent))
        db (stack/create-stack-item db {:stack-item/type :spell
                                        :stack-item/controller :player-2
                                        :stack-item/description "opponent spell"})
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :stack-has :owner :opponent :type :spell}
                 ctx)))))


;; === evaluate-conditions (AND combiner) ===

(deftest evaluate-conditions-empty-returns-true
  (let [db (th/create-test-db)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-conditions [] ctx)))))


(deftest evaluate-conditions-all-true-returns-true
  (let [db (th/create-test-db)
        [db _] (th/add-card-to-zone db :mountain :battlefield :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-conditions
                 [{:check :stack-empty}
                  {:check :zone-contains :zone :battlefield :player :self :card-id :mountain}]
                 ctx)))))


(deftest evaluate-conditions-one-false-returns-false
  (let [db (th/create-test-db)
        ctx {:db db :player-id :player-1}]
    (is (false? (rules/evaluate-conditions
                  [{:check :stack-empty}
                   {:check :zone-contains :zone :hand :player :self :card-id :dark-ritual}]
                  ctx)))))


;; === match-priority-rule ===

(defn- setup-burn-context
  "Create context with burn bot: bolt in hand, untapped mountain, opponent present."
  []
  (let [db (th/create-test-db)
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/card])
        db (th/add-opponent @conn)
        [db _] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
        [db _] (th/add-card-to-zone db :mountain :battlefield :player-2)]
    {:db db :player-id :player-2}))


(deftest match-priority-rule-burn-matches-when-conditions-met
  (let [ctx (setup-burn-context)
        rules (:bot/priority-rules defs/burn-spec)
        result (rules/match-priority-rule rules ctx)]
    (is (map? result) "Should return an action map, not :pass")
    (is (= :cast-spell (:action result)))))


(deftest match-priority-rule-burn-passes-without-bolt
  (let [db (-> (th/create-test-db)
               (th/add-opponent))
        [db _] (th/add-card-to-zone db :mountain :battlefield :player-2)
        ctx {:db db :player-id :player-2}
        rules (:bot/priority-rules defs/burn-spec)]
    (is (= :pass (rules/match-priority-rule rules ctx)))))


(deftest match-priority-rule-burn-passes-without-mountain
  (let [db (th/create-test-db)
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/card])
        db (th/add-opponent @conn)
        [db _] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
        ctx {:db db :player-id :player-2}
        rules (:bot/priority-rules defs/burn-spec)]
    (is (= :pass (rules/match-priority-rule rules ctx)))))


(deftest match-priority-rule-empty-rules-returns-pass
  (let [db (th/create-test-db)
        ctx {:db db :player-id :player-1}]
    (is (= :pass (rules/match-priority-rule [] ctx)))
    (is (= :pass (rules/match-priority-rule nil ctx)))))


(deftest match-priority-rule-skips-interactive-rules
  (let [ctx (setup-burn-context)
        interactive-rule {:rule/mode :interactive
                          :rule/conditions [{:check :stack-empty}]
                          :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}
        rules [interactive-rule]]
    (is (= :pass (rules/match-priority-rule rules ctx)))))


(deftest match-priority-rule-first-match-wins
  (let [ctx (setup-burn-context)
        rule-a {:rule/mode :auto
                :rule/conditions [{:check :stack-empty}]
                :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}
        rule-b {:rule/mode :auto
                :rule/conditions [{:check :stack-empty}]
                :rule/action {:action :activate-ability :card-id :mountain}}
        result (rules/match-priority-rule [rule-a rule-b] ctx)]
    (is (= :cast-spell (:action result)))))


;; === resolve-action ===

(deftest resolve-action-resolves-card-id-and-opponent
  (let [ctx (setup-burn-context)
        template {:action :cast-spell :card-id :lightning-bolt :target :opponent}
        result (rules/resolve-action template ctx)]
    (is (map? result))
    (is (= :cast-spell (:action result)))
    (is (uuid? (:object-id result)) "Should resolve :card-id to a UUID object-id")
    (is (= :player-1 (:target result)) "Should resolve :opponent to player-1")))


(deftest resolve-action-resolves-target-self
  (let [ctx (setup-burn-context)
        template {:action :cast-spell :card-id :lightning-bolt :target :self}
        result (rules/resolve-action template ctx)]
    (is (= :player-2 (:target result)))))


(deftest resolve-action-returns-pass-when-card-not-in-hand
  (let [db (-> (th/create-test-db)
               (th/add-opponent))
        ctx {:db db :player-id :player-2}
        template {:action :cast-spell :card-id :lightning-bolt :target :opponent}]
    (is (= :pass (rules/resolve-action template ctx)))))


;; === get-phase-action ===

(deftest get-phase-action-main1-returns-play-land
  (is (= {:action :play-land}
         (rules/get-phase-action defs/goldfish-spec :main1))))


(deftest get-phase-action-unlisted-phase-returns-pass
  (doseq [phase [:untap :upkeep :draw :combat :main2 :end :cleanup]]
    (is (= {:action :pass}
           (rules/get-phase-action defs/goldfish-spec phase))
        (str "Should return :pass for " (name phase)))))


;; === Spec expressiveness: burn-hate (rule ordering) ===
;; Uses dark-ritual as hate piece stand-in (costs {B}), lightning-bolt as bolt.
;; Rule 1 (hate piece) is prioritized over Rule 2 (bolt).

(def ^:private burn-hate-spec
  {:bot/name "Burn Hate"
   :bot/deck [{:card/id :mountain :count 20} {:card/id :lightning-bolt :count 40}]
   :bot/phase-actions {:main1 :play-land}
   :bot/priority-rules
   [{:rule/mode :auto
     :rule/conditions [{:check :zone-contains :zone :hand :player :self :card-id :dark-ritual}
                       {:check :has-mana-for :card-id :dark-ritual}]
     :rule/action {:action :cast-spell :card-id :dark-ritual :target :opponent}}
    {:rule/mode :auto
     :rule/conditions [{:check :zone-contains :zone :hand :player :self :card-id :lightning-bolt}
                       {:check :has-untapped-source :color :red}
                       {:check :stack-empty}]
     :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}]})


(deftest burn-hate-rule-ordering-pillar-over-bolt
  (let [db (th/create-test-db {:mana {:black 1}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/card])
        db (th/add-opponent @conn)
        [db _] (th/add-card-to-zone db :dark-ritual :hand :player-2)
        [db _] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
        [db _] (th/add-card-to-zone db :mountain :battlefield :player-2)
        ;; Give player-2 black mana so has-mana-for dark-ritual passes
        p2-eid (q/get-player-eid db :player-2)
        db (d/db-with db [[:db/add p2-eid :player/mana-pool
                           {:white 0 :blue 0 :black 1 :red 0 :green 0 :colorless 0}]])
        ctx {:db db :player-id :player-2}
        result (rules/match-priority-rule (:bot/priority-rules burn-hate-spec) ctx)]
    (is (map? result))
    (is (= :cast-spell (:action result)))
    ;; The resolved object-id should be the dark-ritual, not the bolt
    (let [obj (q/get-object db (:object-id result))]
      (is (= :dark-ritual (get-in obj [:object/card :card/id]))
          "First rule (hate piece) should match before bolt rule"))))


(deftest burn-hate-fallback-to-bolt-when-no-pillar
  (let [db (th/create-test-db)
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/card])
        db (th/add-opponent @conn)
        [db _] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
        [db _] (th/add-card-to-zone db :mountain :battlefield :player-2)
        ctx {:db db :player-id :player-2}
        result (rules/match-priority-rule (:bot/priority-rules burn-hate-spec) ctx)]
    (is (map? result))
    (is (= :cast-spell (:action result)))
    (let [obj (q/get-object db (:object-id result))]
      (is (= :lightning-bolt (get-in obj [:object/card :card/id]))
          "Should fall back to bolt rule when hate piece not in hand"))))


;; === Spec expressiveness: counterspell (interactive mode) ===

(def ^:private counterspell-spec
  {:bot/name "Counterspell"
   :bot/deck [{:card/id :island :count 60}]
   :bot/phase-actions {}
   :bot/priority-rules
   [{:rule/mode :interactive
     :rule/tag :counter
     :rule/conditions [{:check :stack-has :owner :opponent :type :spell}
                       {:check :zone-contains :zone :hand :player :self :card-id :island}
                       {:check :has-mana-for :card-id :island}]
     :rule/action {:action :cast-spell :card-id :island :target :top-of-stack}}]})


(deftest counterspell-interactive-rule-skipped-by-matcher
  (let [db (-> (th/create-test-db)
               (th/add-opponent))
        opp-eid (q/get-player-eid db :player-2)
        db (stack/create-stack-item db {:stack-item/type :spell
                                        :stack-item/controller opp-eid
                                        :stack-item/description "opponent spell"})
        [db _] (th/add-card-to-zone db :island :hand :player-1)
        ctx {:db db :player-id :player-1}
        result (rules/match-priority-rule (:bot/priority-rules counterspell-spec) ctx)]
    (is (= :pass result)
        "Interactive rules should be skipped by match-priority-rule")))


;; === Extended condition tests via spec scenarios ===

(deftest stack-has-finds-opponent-spell-in-spec-scenario
  (let [db (-> (th/create-test-db)
               (th/add-opponent))
        db (stack/create-stack-item db {:stack-item/type :spell
                                        :stack-item/controller :player-2
                                        :stack-item/description "opponent spell"})
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :stack-has :owner :opponent :type :spell}
                 ctx)))
    (is (false? (rules/evaluate-condition
                  {:check :stack-has :owner :opponent :type :spell}
                  {:db (th/create-test-db) :player-id :player-1}))
        "Should return false when stack is empty")))


(deftest zone-count-threshold-graveyard-scenario
  (let [db (th/create-test-db)
        [db _] (th/add-cards-to-graveyard db (repeat 7 :dark-ritual) :player-1)
        ctx {:db db :player-id :player-1}]
    (is (true? (rules/evaluate-condition
                 {:check :zone-count :zone :graveyard :player :self :gte 7}
                 ctx))
        "Threshold met: 7 cards in graveyard >= 7")
    (is (false? (rules/evaluate-condition
                  {:check :zone-count :zone :graveyard :player :self :gte 7}
                  {:db (th/create-test-db) :player-id :player-1}))
        "Threshold not met: 0 cards in graveyard < 7")))


;; === choose-attackers ===

(deftest choose-attackers-all-strategy-selects-all-eligible
  (let [eligible [:obj-1 :obj-2 :obj-3]]
    (is (= (set eligible)
           (set (rules/choose-attackers {:bot/attack-strategy :all} eligible)))
        "Strategy :all should select all eligible attackers")))


(deftest choose-attackers-nil-strategy-selects-none
  (let [eligible [:obj-1 :obj-2]]
    (is (empty? (rules/choose-attackers {} eligible))
        "No attack strategy should return empty (don't attack)")
    (is (empty? (rules/choose-attackers {:bot/attack-strategy nil} eligible))
        "Nil attack strategy should return empty")))


(deftest choose-attackers-empty-eligible-returns-empty
  (is (empty? (rules/choose-attackers {:bot/attack-strategy :all} []))
      "No eligible attackers returns empty even with :all strategy"))
