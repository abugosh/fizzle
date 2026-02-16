(ns fizzle.events.cast-and-yield-test
  "Tests for ::cast-and-yield event handler.

   Verifies:
   - Ritual cast + auto-resolve in one action
   - Multi-mode spells show mode selector without auto-yield
   - Targeted spells show targeting selection without auto-yield
   - Generic mana cost shows mana allocation without auto-yield
   - No-op when nothing selected or can't cast
   - Storm: spell resolves, copies remain on stack"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.mana :as mana]
    [fizzle.events.game :as game]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register the history interceptor so dispatch-sync creates history entries
(interceptor/register!)


;; === Test helpers ===

(defn- create-full-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn cards/all-cards)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}
                       {:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn- add-card-to-zone
  "Add a card object to a zone. Returns [db object-id]."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (queries/get-player-eid db player-id)
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn- dispatch-cast-and-yield
  "Dispatch ::cast-and-yield through re-frame and return the resulting app-db.
   Uses dispatch-sync which processes the event and any :fx dispatches synchronously."
  [app-db]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync [::game/cast-and-yield])
  @rf-db/app-db)


;; === Tests ===

(deftest test-cast-and-yield-ritual-resolves
  (testing "Dark Ritual cast + resolve in one action: mana added, spell in graveyard"
    (let [db (create-full-db)
          [db obj-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          app-db (merge (history/init-history)
                        {:game/db db
                         :game/selected-card obj-id})
          result (dispatch-cast-and-yield app-db)]
      ;; Spell should be resolved (in graveyard)
      (is (= :graveyard (:object/zone (queries/get-object (:game/db result) obj-id)))
          "Dark Ritual should be in graveyard after cast-and-yield")
      ;; Mana should be added (paid 1B, gained 3B = 3B)
      (is (= 3 (:black (queries/get-mana-pool (:game/db result) :player-1)))
          "Should have 3 black mana after resolving Dark Ritual")
      ;; Stack should be empty (spell + storm meta-item both resolved)
      (is (empty? (queries/get-all-stack-items (:game/db result)))
          "Stack should be empty after cast-and-yield")
      ;; No pending selection
      (is (nil? (:game/pending-selection result))
          "Should not have pending selection"))))


(deftest test-cast-and-yield-multi-mode-shows-selector
  (testing "Multi-mode spell shows mode selector without auto-yield"
    (let [db (create-full-db)
          ;; Cabal Ritual has threshold mode (alternate) — use a card with multiple castable modes
          ;; Create a test card inline with multiple modes from same zone
          conn (d/conn-from-db db)
          _ (d/transact! conn [{:card/id :test-multi-mode-yield
                                :card/name "Test Multi Mode"
                                :card/mana-cost {:blue 3 :black 2}
                                :card/cmc 5
                                :card/types #{:instant}
                                :card/colors #{:blue}
                                :card/effects [{:effect/type :draw :effect/amount 2}]
                                :card/alternate-costs [{:alternate/id :alternate-cost
                                                        :alternate/zone :hand
                                                        :alternate/mana-cost {}
                                                        :alternate/additional-costs [{:cost/type :pay-life
                                                                                      :cost/amount 2}]
                                                        :alternate/on-resolve :graveyard}]}])
          db @conn
          [db obj-id] (add-card-to-zone db :test-multi-mode-yield :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 3 :black 2})
          app-db (merge (history/init-history)
                        {:game/db db
                         :game/selected-card obj-id})
          result (dispatch-cast-and-yield app-db)]
      ;; Should have pending mode selection (not auto-yielded)
      (is (some? (:game/pending-mode-selection result))
          "Should show mode selector for multi-mode spell")
      ;; Stack should NOT have the spell (mode selector is pre-cast)
      (is (nil? (:game/pending-selection result))
          "Should not have pending selection (mode selection is different)"))))


(deftest test-cast-and-yield-targeted-spell-shows-targeting
  (testing "Targeted spell shows targeting selection without auto-yield"
    (let [db (create-full-db)
          ;; Brain Freeze has targeting requirements (:any-player)
          [db obj-id] (add-card-to-zone db :brain-freeze :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          app-db (merge (history/init-history)
                        {:game/db db
                         :game/selected-card obj-id})
          result (dispatch-cast-and-yield app-db)]
      ;; Should have pending selection (targeting)
      (is (some? (:game/pending-selection result))
          "Should show targeting selection for targeted spell")
      ;; Stack should be empty (spell not cast yet, targeting is pre-cast)
      (is (empty? (queries/get-all-stack-items (:game/db result)))
          "Spell should not be on stack yet (targeting is pre-cast)"))))


(deftest test-cast-and-yield-nothing-selected-noop
  (testing "No-op when no card selected"
    (let [db (create-full-db)
          app-db (merge (history/init-history)
                        {:game/db db})
          result (dispatch-cast-and-yield app-db)]
      ;; DB should be unchanged
      (is (= db (:game/db result))
          "Game DB should be unchanged when nothing selected")
      (is (nil? (:game/pending-selection result))
          "Should not have pending selection"))))


(deftest test-cast-and-yield-cannot-cast-noop
  (testing "No-op when selected spell can't be cast (insufficient mana)"
    (let [db (create-full-db)
          [db obj-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          ;; No mana added — can't cast
          app-db (merge (history/init-history)
                        {:game/db db
                         :game/selected-card obj-id})
          result (dispatch-cast-and-yield app-db)]
      ;; DB should be unchanged
      (is (= db (:game/db result))
          "Game DB should be unchanged when can't cast")
      ;; Stack should be empty
      (is (empty? (queries/get-all-stack-items (:game/db result)))
          "Stack should be empty"))))


(deftest test-cast-and-yield-storm-spell
  (testing "Storm spell: resolve-top resolves storm meta-item, copies + spell remain on stack"
    (let [db (create-full-db)
          conn (d/conn-from-db db)
          ;; Create a non-targeted storm card for testing
          _ (d/transact! conn [{:card/id :test-storm-ritual
                                :card/name "Test Storm Ritual"
                                :card/mana-cost {:black 1}
                                :card/cmc 1
                                :card/types #{:instant}
                                :card/colors #{:black}
                                :card/keywords #{:storm}
                                :card/effects [{:effect/type :add-mana
                                                :effect/mana {:black 2}}]}])
          ;; Set storm count to 2 directly (simulates 2 prior casts)
          player-eid (queries/get-player-eid @conn :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/storm-count 2]])
          ;; Add the storm card to hand with enough mana
          card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]]
                        @conn :test-storm-ritual)
          storm-obj-id (random-uuid)
          _ (d/transact! conn [{:object/id storm-obj-id
                                :object/card card-eid
                                :object/zone :hand
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false}])
          db (mana/add-mana @conn :player-1 {:black 1})
          app-db (merge (history/init-history)
                        {:game/db db
                         :game/selected-card storm-obj-id})
          result (dispatch-cast-and-yield app-db)]
      ;; Play & Yield = Play + one Yield. The top of stack after cast is the storm
      ;; meta-item, so resolve-top resolves it (creating copies). The spell itself
      ;; remains on the stack along with the copies.
      (is (= :stack (:object/zone (queries/get-object (:game/db result) storm-obj-id)))
          "Spell should still be on stack (storm meta-item resolved, not the spell)")
      ;; Storm copies + spell stack-item should be on the stack
      (let [stack-items (queries/get-all-stack-items (:game/db result))
            copy-items (filter #(= :storm-copy (:stack-item/type %)) stack-items)]
        (is (= 2 (count copy-items))
            "Should have 2 storm copies on stack")
        ;; Total items: spell + 2 copies = 3
        (is (= 3 (count stack-items))
            "Should have 3 total stack items (spell + 2 copies)")))))


(deftest test-cast-and-yield-generic-mana-shows-allocation
  (testing "Spell with generic mana cost shows mana allocation selection"
    (let [db (create-full-db)
          ;; Merchant Scroll costs {1}{U} — has generic mana cost
          [db obj-id] (add-card-to-zone db :merchant-scroll :hand :player-1)
          ;; Provide multiple colors so allocation is needed
          db (mana/add-mana db :player-1 {:blue 1 :black 1})
          app-db (merge (history/init-history)
                        {:game/db db
                         :game/selected-card obj-id})
          result (dispatch-cast-and-yield app-db)]
      ;; Should have pending selection (mana allocation or targeting)
      (is (some? (:game/pending-selection result))
          "Should show selection for spell with generic/targeting requirements")
      ;; Spell should NOT be resolved
      (is (empty? (queries/get-all-stack-items (:game/db result)))
          "Spell should not be on stack yet (selection is pre-cast)"))))
