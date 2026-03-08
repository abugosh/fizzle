(ns fizzle.subs.game-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.grants :as grants]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


(defn- sub-value
  "Get subscription value by resetting app-db and deref'ing the subscription."
  [db sub-vec]
  (reset! rf-db/app-db db)
  @(rf/subscribe sub-vec))


(defn- make-game-db
  "Create a minimal game-db with two players and a game entity.
   Returns a Datascript db value."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 3
                        :player/land-plays-left 1}
                       {:player/id :opponent
                        :player/name "Opponent"
                        :player/life 15
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 0}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 5
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid
                          :game/human-player-id :player-1}]))
    @conn))


(defn- set-winner
  "Set :game/winner and :game/loss-condition on a game-db.
   losing-player-id is :player-1 or :opponent."
  [game-db condition losing-player-id]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
        winner-eid (d/q '[:find ?e .
                          :in $ ?loser-pid
                          :where [?e :player/id ?pid]
                          [(not= ?pid ?loser-pid)]]
                        game-db losing-player-id)]
    (d/db-with game-db [[:db/add game-eid :game/loss-condition condition]
                        [:db/add game-eid :game/winner winner-eid]])))


(defn- add-library-cards
  "Add N cards to a player's library. Returns new game-db."
  [game-db player-id n]
  (let [conn (d/conn-from-db game-db)
        player-eid (q/get-player-eid game-db player-id)]
    (d/transact! conn (vec (for [i (range n)]
                             {:object/id (random-uuid)
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position i})))
    @conn))


;; === ::game-over? subscription tests ===

(deftest test-game-over-false-when-no-winner
  (testing "::game-over? returns false when no winner set"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/game-over?])]
      (is (false? result)))))


(deftest test-game-over-true-when-winner-set
  (testing "::game-over? returns true when winner exists"
    (let [game-db (set-winner (make-game-db) :life-zero :opponent)
          result (sub-value {:game/db game-db} [::subs/game-over?])]
      (is (true? result)))))


(deftest test-game-over-false-when-game-db-nil
  (testing "::game-over? returns false when game-db is nil (no NPE)"
    (let [result (sub-value {} [::subs/game-over?])]
      (is (false? result)))))


;; === ::game-result subscription tests ===

(deftest test-game-result-player-wins
  (testing "::game-result returns :win outcome when player-1 wins"
    (let [game-db (-> (make-game-db)
                      (add-library-cards :opponent 10)
                      (set-winner :life-zero :opponent))
          result (sub-value {:game/db game-db} [::subs/game-result])]
      (is (= :win (:outcome result)))
      (is (= :life-zero (:condition result)))
      (is (= 5 (:turn result)))
      (is (= 3 (:storm-count result)))
      (is (= 15 (:opponent-life result)))
      (is (= 10 (:opponent-library-size result))))))


(deftest test-game-result-player-loses
  (testing "::game-result returns :loss outcome when player-1 loses"
    (let [game-db (-> (make-game-db)
                      (set-winner :empty-library :player-1))
          result (sub-value {:game/db game-db} [::subs/game-result])]
      (is (= :loss (:outcome result)))
      (is (= :empty-library (:condition result))))))


(deftest test-game-result-stats-values
  (testing "::game-result returns correct stats values"
    (let [game-db (-> (make-game-db)
                      (add-library-cards :opponent 7)
                      (set-winner :empty-library :opponent))
          result (sub-value {:game/db game-db} [::subs/game-result])]
      (is (= 5 (:turn result)) "turn number from game state")
      (is (= 3 (:storm-count result)) "storm count from player-1")
      (is (= 15 (:opponent-life result)) "opponent life total")
      (is (= 7 (:opponent-library-size result)) "opponent library card count"))))


(deftest test-game-result-nil-when-no-winner
  (testing "::game-result returns nil when no winner"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/game-result])]
      (is (nil? result)))))


;; === ::show-game-over-modal? subscription tests ===

(deftest test-show-modal-true-when-game-over-not-dismissed
  (testing "::show-game-over-modal? returns true when game over and not dismissed"
    (let [game-db (set-winner (make-game-db) :life-zero :opponent)
          result (sub-value {:game/db game-db
                             :active-screen :game}
                            [::subs/show-game-over-modal?])]
      (is (true? result)))))


(deftest test-show-modal-false-after-dismiss
  (testing "::show-game-over-modal? returns false after dismiss"
    (let [game-db (set-winner (make-game-db) :life-zero :opponent)
          result (sub-value {:game/db game-db
                             :active-screen :game
                             :game/game-over-dismissed true}
                            [::subs/show-game-over-modal?])]
      (is (false? result)))))


(deftest test-show-modal-false-on-non-game-screen
  (testing "::show-game-over-modal? returns false on setup screen"
    (let [game-db (set-winner (make-game-db) :life-zero :opponent)
          result (sub-value {:game/db game-db
                             :active-screen :setup}
                            [::subs/show-game-over-modal?])]
      (is (false? result)))))


(defn- add-zone-cards
  "Add N cards to a player's zone. Returns new game-db."
  [game-db player-id zone n]
  (let [conn (d/conn-from-db game-db)
        player-eid (q/get-player-eid game-db player-id)]
    (d/transact! conn (vec (for [i (range n)]
                             {:object/id (random-uuid)
                              :object/zone zone
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position i})))
    @conn))


;; === ::player-zone-counts subscription tests ===

(deftest test-player-zone-counts-empty-zones
  (testing "player-zone-counts returns zeros for empty zones"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/player-zone-counts])]
      (is (= 0 (:graveyard result)))
      (is (= 0 (:library result)))
      (is (= 0 (:exile result)))
      (is (false? (:threshold? result))))))


(deftest test-player-zone-counts-with-cards
  (testing "player-zone-counts returns correct counts after adding cards"
    (let [game-db (-> (make-game-db)
                      (add-zone-cards :player-1 :graveyard 5)
                      (add-zone-cards :player-1 :library 40)
                      (add-zone-cards :player-1 :exile 2))
          result (sub-value {:game/db game-db} [::subs/player-zone-counts])]
      (is (= 5 (:graveyard result)))
      (is (= 40 (:library result)))
      (is (= 2 (:exile result)))
      (is (false? (:threshold? result))))))


(deftest test-player-zone-counts-threshold-boundary
  (testing "threshold? is false at 6 graveyard cards, true at 7"
    (let [game-db-6 (add-zone-cards (make-game-db) :player-1 :graveyard 6)
          result-6 (sub-value {:game/db game-db-6} [::subs/player-zone-counts])
          game-db-7 (add-zone-cards (make-game-db) :player-1 :graveyard 7)
          result-7 (sub-value {:game/db game-db-7} [::subs/player-zone-counts])]
      (is (false? (:threshold? result-6)) "6 cards = no threshold")
      (is (true? (:threshold? result-7)) "7 cards = threshold active"))))


(deftest test-player-zone-counts-nil-when-no-game-db
  (testing "player-zone-counts returns nil when game-db is nil"
    (let [result (sub-value {} [::subs/player-zone-counts])]
      (is (nil? result)))))


;; === ::opponent-zone-counts subscription tests ===

(deftest test-opponent-zone-counts-empty-zones
  (testing "opponent-zone-counts returns zeros for empty zones"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/opponent-zone-counts])]
      (is (= 0 (:graveyard result)))
      (is (= 0 (:library result)))
      (is (= 0 (:exile result))))))


(deftest test-opponent-zone-counts-with-cards
  (testing "opponent-zone-counts returns correct counts for opponent"
    (let [game-db (-> (make-game-db)
                      (add-zone-cards :opponent :graveyard 3)
                      (add-zone-cards :opponent :library 50)
                      (add-zone-cards :opponent :exile 1))
          result (sub-value {:game/db game-db} [::subs/opponent-zone-counts])]
      (is (= 3 (:graveyard result)))
      (is (= 50 (:library result)))
      (is (= 1 (:exile result))))))


(deftest test-opponent-zone-counts-nil-when-no-game-db
  (testing "opponent-zone-counts returns nil when game-db is nil"
    (let [result (sub-value {} [::subs/opponent-zone-counts])]
      (is (nil? result)))))


(deftest test-zone-counts-isolation
  (testing "player and opponent counts don't cross-contaminate"
    (let [game-db (-> (make-game-db)
                      (add-zone-cards :player-1 :graveyard 10)
                      (add-zone-cards :opponent :graveyard 3))
          player (sub-value {:game/db game-db} [::subs/player-zone-counts])
          opp (sub-value {:game/db game-db} [::subs/opponent-zone-counts])]
      (is (= 10 (:graveyard player)))
      (is (= 3 (:graveyard opp))))))


;; === ::mana-allocation-state subscription tests ===

(deftest test-mana-allocation-state-nil-when-no-selection
  (testing "mana-allocation-state returns nil when no pending-selection"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/mana-allocation-state])]
      (is (nil? result)))))


(deftest test-mana-allocation-state-nil-for-other-types
  (testing "mana-allocation-state returns nil for non-allocation selection types"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db
                             :game/pending-selection {:selection/type :discard
                                                      :selection/player-id :player-1}}
                            [::subs/mana-allocation-state])]
      (is (nil? result)))))


(deftest test-mana-allocation-state-returns-selection-for-allocation
  (testing "mana-allocation-state returns selection map when type is :mana-allocation"
    (let [game-db (make-game-db)
          selection {:selection/type :mana-allocation
                     :selection/generic-remaining 3
                     :selection/generic-total 3
                     :selection/allocation {}
                     :selection/remaining-pool {:black 2 :blue 1 :red 0}
                     :selection/original-remaining-pool {:black 2 :blue 1 :red 0}
                     :selection/colored-cost {:black 1}
                     :selection/auto-confirm? true}
          result (sub-value {:game/db game-db
                             :game/pending-selection selection}
                            [::subs/mana-allocation-state])]
      (is (= :mana-allocation (:selection/type result)))
      (is (= 3 (:selection/generic-remaining result)))
      (is (= {} (:selection/allocation result)))
      (is (= {:black 2 :blue 1 :red 0} (:selection/remaining-pool result))))))


(deftest test-mana-allocation-state-with-partial-allocation
  (testing "mana-allocation-state reflects partial allocation progress"
    (let [game-db (make-game-db)
          selection {:selection/type :mana-allocation
                     :selection/generic-remaining 1
                     :selection/generic-total 3
                     :selection/allocation {:black 2}
                     :selection/remaining-pool {:black 0 :blue 1 :red 0}
                     :selection/original-remaining-pool {:black 2 :blue 1 :red 0}
                     :selection/colored-cost {:black 1}
                     :selection/auto-confirm? true}
          result (sub-value {:game/db game-db
                             :game/pending-selection selection}
                            [::subs/mana-allocation-state])]
      (is (= 1 (:selection/generic-remaining result)))
      (is (= {:black 2} (:selection/allocation result)))
      (is (= {:black 0 :blue 1 :red 0} (:selection/remaining-pool result))))))


;; === ::storm-split-source-name subscription tests ===

(deftest test-storm-split-source-name-returns-spell-name
  (testing "storm-split-source-name returns card name from source object"
    (let [game-db (make-game-db)
          conn (d/conn-from-db game-db)
          _ (d/transact! conn [{:card/id :brain-freeze
                                :card/name "Brain Freeze"}])
          card-eid (d/q '[:find ?e . :where [?e :card/id :brain-freeze]] @conn)
          player-eid (q/get-player-eid @conn :player-1)
          obj-id (random-uuid)
          _ (d/transact! conn [{:object/id obj-id
                                :object/card card-eid
                                :object/zone :stack
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false}])
          game-db' @conn
          selection {:selection/type :storm-split
                     :selection/source-object-id obj-id
                     :selection/copy-count 5}
          result (sub-value {:game/db game-db'
                             :game/pending-selection selection}
                            [::subs/storm-split-source-name])]
      (is (= "Brain Freeze" result)))))


(deftest test-storm-split-source-name-nil-when-not-storm-split
  (testing "storm-split-source-name returns nil for non-storm-split selection"
    (let [game-db (make-game-db)
          selection {:selection/type :discard
                     :selection/player-id :player-1}
          result (sub-value {:game/db game-db
                             :game/pending-selection selection}
                            [::subs/storm-split-source-name])]
      (is (nil? result)))))


(defn- add-battlefield-permanent
  "Add a permanent to a player's battlefield. Returns new game-db."
  [game-db player-id card-name cmc types]
  (let [conn (d/conn-from-db game-db)
        player-eid (q/get-player-eid game-db player-id)
        card-id (keyword (str "card-" (random-uuid)))
        _ (d/transact! conn [{:card/id card-id
                              :card/name card-name
                              :card/cmc cmc
                              :card/types types}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] @conn card-id)]
    (d/transact! conn [{:object/id (random-uuid)
                        :object/card card-eid
                        :object/zone :battlefield
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    @conn))


;; === ::battlefield subscription tests ===

(deftest test-battlefield-empty-returns-empty-groups
  (testing "battlefield returns empty groups when no permanents"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/battlefield])]
      (is (= [] (:creatures result)))
      (is (= [] (:other result)))
      (is (= [] (:lands result))))))


(deftest test-battlefield-three-tier-grouping
  (testing "battlefield separates creatures, other, and lands"
    (let [game-db (-> (make-game-db)
                      (add-battlefield-permanent :player-1 "Llanowar Elves" 1 #{:creature})
                      (add-battlefield-permanent :player-1 "Chrome Mox" 0 #{:artifact})
                      (add-battlefield-permanent :player-1 "Swamp" 0 #{:land}))
          result (sub-value {:game/db game-db} [::subs/battlefield])]
      (is (= 1 (count (:creatures result))))
      (is (= 1 (count (:other result))))
      (is (= 1 (count (:lands result))))
      (is (= "Llanowar Elves" (get-in (first (:creatures result)) [:object/card :card/name])))
      (is (= "Chrome Mox" (get-in (first (:other result)) [:object/card :card/name])))
      (is (= "Swamp" (get-in (first (:lands result)) [:object/card :card/name]))))))


(deftest test-battlefield-artifact-creature
  (testing "battlefield puts artifact-creature in :creatures group"
    (let [game-db (add-battlefield-permanent (make-game-db) :player-1 "Steel Golem" 3 #{:artifact :creature})
          result (sub-value {:game/db game-db} [::subs/battlefield])]
      (is (= 1 (count (:creatures result))))
      (is (= [] (:other result)))
      (is (= [] (:lands result))))))


(deftest test-battlefield-sorted-by-cmc
  (testing "battlefield groups are sorted by CMC then name"
    (let [game-db (-> (make-game-db)
                      (add-battlefield-permanent :player-1 "Tarmogoyf" 2 #{:creature})
                      (add-battlefield-permanent :player-1 "Birds of Paradise" 1 #{:creature})
                      (add-battlefield-permanent :player-1 "Dark Confidant" 2 #{:creature}))
          result (sub-value {:game/db game-db} [::subs/battlefield])
          names (mapv #(get-in % [:object/card :card/name]) (:creatures result))]
      (is (= ["Birds of Paradise" "Dark Confidant" "Tarmogoyf"] names)))))


(deftest test-battlefield-nil-when-no-game-db
  (testing "battlefield returns nil when game-db is nil"
    (let [result (sub-value {} [::subs/battlefield])]
      (is (nil? result)))))


;; === ::opponent-battlefield subscription tests ===

(deftest test-opponent-battlefield-empty-returns-empty-groups
  (testing "opponent-battlefield returns empty groups when no permanents"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/opponent-battlefield])]
      (is (= [] (:creatures result)))
      (is (= [] (:other result)))
      (is (= [] (:lands result))))))


(deftest test-opponent-battlefield-three-tier-grouping
  (testing "opponent-battlefield separates creatures, other, and lands for opponent"
    (let [game-db (-> (make-game-db)
                      (add-battlefield-permanent :opponent "Tarmogoyf" 2 #{:creature})
                      (add-battlefield-permanent :opponent "Chalice of the Void" 0 #{:artifact})
                      (add-battlefield-permanent :opponent "Island" 0 #{:land}))
          result (sub-value {:game/db game-db} [::subs/opponent-battlefield])]
      (is (= 1 (count (:creatures result))))
      (is (= 1 (count (:other result))))
      (is (= 1 (count (:lands result))))
      (is (= "Tarmogoyf" (get-in (first (:creatures result)) [:object/card :card/name])))
      (is (= "Chalice of the Void" (get-in (first (:other result)) [:object/card :card/name])))
      (is (= "Island" (get-in (first (:lands result)) [:object/card :card/name]))))))


(deftest test-battlefield-isolation
  (testing "player and opponent battlefields don't cross-contaminate"
    (let [game-db (-> (make-game-db)
                      (add-battlefield-permanent :player-1 "Swamp" 0 #{:land})
                      (add-battlefield-permanent :opponent "Island" 0 #{:land}))
          player (sub-value {:game/db game-db} [::subs/battlefield])
          opp (sub-value {:game/db game-db} [::subs/opponent-battlefield])]
      (is (= 1 (count (:lands player))))
      (is (= 1 (count (:lands opp))))
      (is (= "Swamp" (get-in (first (:lands player)) [:object/card :card/name])))
      (is (= "Island" (get-in (first (:lands opp)) [:object/card :card/name]))))))


(deftest test-opponent-battlefield-nil-when-no-game-db
  (testing "opponent-battlefield returns nil when game-db is nil"
    (let [result (sub-value {} [::subs/opponent-battlefield])]
      (is (nil? result)))))


;; === human-player-id tests ===

;; === ::human-player-id subscription tests ===

(deftest test-human-player-id-subscription
  (testing "::human-player-id returns correct human player-id from game-db"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/human-player-id])]
      (is (= :player-1 result)))))


(deftest test-human-player-id-nil-when-no-game-db
  (testing "::human-player-id returns nil when game-db is nil"
    (let [result (sub-value {} [::subs/human-player-id])]
      (is (nil? result)))))


;; === compute-creature-display tests ===

(defn- add-battlefield-creature
  "Add a creature to a player's battlefield with P/T. Returns [game-db obj-id]."
  [game-db player-id card-name power toughness]
  (let [conn (d/conn-from-db game-db)
        player-eid (q/get-player-eid game-db player-id)
        card-id (keyword (str "card-" (random-uuid)))
        _ (d/transact! conn [{:card/id card-id
                              :card/name card-name
                              :card/cmc 1
                              :card/types #{:creature}}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] @conn card-id)
        obj-id (random-uuid)
        _ (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :battlefield
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/power power
                              :object/toughness toughness}])]
    [@conn obj-id]))


(deftest test-compute-creature-display-base-pt
  (testing "compute-creature-display returns base P/T with no modifications"
    (let [[game-db obj-id] (add-battlefield-creature (make-game-db) :player-1 "Bear" 2 2)
          obj (q/get-object game-db obj-id)
          result (subs/compute-creature-display game-db obj)]
      (is (= 2 (:effective-power result)))
      (is (= 2 (:effective-toughness result)))
      (is (= 2 (:base-power result)))
      (is (= 2 (:base-toughness result)))
      (is (nil? (:power-mod result)))
      (is (nil? (:toughness-mod result)))
      (is (= 0 (:damage-marked result)))
      (is (false? (:attacking result)))
      (is (false? (:blocking result)))
      (is (false? (:summoning-sick result))))))


(deftest test-compute-creature-display-buffed-by-counters
  (testing "compute-creature-display returns :buffed mod when +1/+1 counter present"
    (let [[game-db obj-id] (add-battlefield-creature (make-game-db) :player-1 "Bear" 2 2)
          obj-eid (q/get-object-eid game-db obj-id)
          game-db (d/db-with game-db [[:db/add obj-eid :object/counters {:+1/+1 1}]])
          obj (q/get-object game-db obj-id)
          result (subs/compute-creature-display game-db obj)]
      (is (= 3 (:effective-power result)))
      (is (= 3 (:effective-toughness result)))
      (is (= 2 (:base-power result)))
      (is (= 2 (:base-toughness result)))
      (is (= :buffed (:power-mod result)))
      (is (= :buffed (:toughness-mod result))))))


(deftest test-compute-creature-display-debuffed-by-counters
  (testing "compute-creature-display returns :debuffed mod when -1/-1 counter present"
    (let [[game-db obj-id] (add-battlefield-creature (make-game-db) :player-1 "Bear" 2 2)
          obj-eid (q/get-object-eid game-db obj-id)
          game-db (d/db-with game-db [[:db/add obj-eid :object/counters {:-1/-1 1}]])
          obj (q/get-object game-db obj-id)
          result (subs/compute-creature-display game-db obj)]
      (is (= 1 (:effective-power result)))
      (is (= 1 (:effective-toughness result)))
      (is (= :debuffed (:power-mod result)))
      (is (= :debuffed (:toughness-mod result))))))


(deftest test-compute-creature-display-mixed-mods-cancel
  (testing "compute-creature-display returns nil mod when +1/+1 and -1/-1 counters cancel"
    (let [[game-db obj-id] (add-battlefield-creature (make-game-db) :player-1 "Bear" 2 2)
          obj-eid (q/get-object-eid game-db obj-id)
          game-db (d/db-with game-db [[:db/add obj-eid :object/counters {:+1/+1 1 :-1/-1 1}]])
          obj (q/get-object game-db obj-id)
          result (subs/compute-creature-display game-db obj)]
      (is (= 2 (:effective-power result)))
      (is (= 2 (:effective-toughness result)))
      (is (nil? (:power-mod result)))
      (is (nil? (:toughness-mod result))))))


(deftest test-compute-creature-display-split-mods
  (testing "compute-creature-display colors power and toughness mods independently"
    (let [[game-db obj-id] (add-battlefield-creature (make-game-db) :player-1 "Bear" 2 2)
          ;; Grant +2/+0 to buff only power
          game-db (grants/add-grant game-db obj-id
                                    {:grant/id (random-uuid)
                                     :grant/type :pt-modifier
                                     :grant/source obj-id
                                     :grant/data {:grant/power 2 :grant/toughness 0}})
          obj (q/get-object game-db obj-id)
          result (subs/compute-creature-display game-db obj)]
      (is (= 4 (:effective-power result)))
      (is (= 2 (:effective-toughness result)))
      (is (= :buffed (:power-mod result)))
      (is (nil? (:toughness-mod result))))))


(deftest test-compute-creature-display-non-creature-returns-nil
  (testing "compute-creature-display returns nil for non-creature objects"
    (let [game-db (make-game-db)
          conn (d/conn-from-db game-db)
          player-eid (q/get-player-eid game-db :player-1)
          card-id (keyword (str "card-" (random-uuid)))
          _ (d/transact! conn [{:card/id card-id
                                :card/name "Swamp"
                                :card/cmc 0
                                :card/types #{:land}}])
          card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] @conn card-id)
          obj-id (random-uuid)
          _ (d/transact! conn [{:object/id obj-id
                                :object/card card-eid
                                :object/zone :battlefield
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false}])
          game-db @conn
          obj (q/get-object game-db obj-id)
          result (subs/compute-creature-display game-db obj)]
      (is (nil? result)))))


(deftest test-compute-creature-display-with-damage
  (testing "compute-creature-display includes damage-marked when > 0"
    (let [[game-db obj-id] (add-battlefield-creature (make-game-db) :player-1 "Bear" 2 2)
          obj-eid (q/get-object-eid game-db obj-id)
          game-db (d/db-with game-db [[:db/add obj-eid :object/damage-marked 1]])
          obj (q/get-object game-db obj-id)
          result (subs/compute-creature-display game-db obj)]
      (is (= 1 (:damage-marked result))))))


;; === battlefield subscription includes creature-display data ===

(deftest test-battlefield-creatures-have-display-data
  (testing "battlefield subscription enriches creatures with :creature/display"
    (let [[game-db _obj-id] (add-battlefield-creature (make-game-db) :player-1 "Bear" 2 2)
          result (sub-value {:game/db game-db} [::subs/battlefield])
          creature (first (:creatures result))]
      (is (some? (:creature/display creature)) "creature should have :creature/display data")
      (is (= 2 (:effective-power (:creature/display creature))))
      (is (= 2 (:effective-toughness (:creature/display creature)))))))


(deftest test-battlefield-non-creatures-no-display-data
  (testing "battlefield subscription does not add :creature/display to non-creatures"
    (let [game-db (add-battlefield-permanent (make-game-db) :player-1 "Swamp" 0 #{:land})
          result (sub-value {:game/db game-db} [::subs/battlefield])
          land (first (:lands result))]
      (is (nil? (:creature/display land)) "non-creature should not have :creature/display"))))
