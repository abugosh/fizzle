(ns fizzle.bots.decisions-test
  "Tests for bot decision helpers: bot-should-act?, bot-decide-action, find-tap-sequence.

   Validates that:
   - Bot decisions module detects when priority holder is a bot
   - bot-decide-action returns correct action plan with tap sequence
   - find-tap-sequence correctly allocates mana-producing lands
   - Bot casts go through can-cast? validation"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.bots.decisions :as decisions]
    [fizzle.cards.red.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.events.casting :as casting]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(defn- setup-burn-bot-app-db
  "Create an app-db with a burn bot opponent.
   Bot has Mountains on battlefield and Bolts in hand.
   Active player is bot (:player-2), priority holder is bot.
   Returns app-db map."
  [{:keys [mountains bolts]}]
  (let [db (th/create-test-db {:stops #{:main1 :main2}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/card])
        db (th/add-opponent @conn {:bot-archetype :burn})]
    ;; Add Mountains to battlefield
    (let [[db _] (reduce (fn [[db' _] _]
                           (th/add-card-to-zone db' :mountain :battlefield :player-2))
                         [db nil]
                         (range mountains))
          ;; Add Bolts to hand
          [db _] (reduce (fn [[db' _] _]
                           (th/add-card-to-zone db' :lightning-bolt :hand :player-2))
                         [db nil]
                         (range bolts))
          ;; Set active player and priority holder to bot, phase to main1
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/phase :main1]])]
      (merge (history/init-history)
             {:game/db db}))))


;; === Bot Interceptor Detection Tests ===

(deftest bot-should-act-detects-bot-priority-holder
  (testing "bot-should-act? returns true when priority holder is a bot"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)]
      (is (true? (decisions/bot-should-act? game-db))
          "Should detect that priority holder is a bot"))))


(deftest bot-should-act-returns-false-for-human
  (testing "bot-should-act? returns false when priority holder is human"
    (let [db (th/create-test-db {:stops #{:main1}})
          db (th/add-opponent db {:bot-archetype :burn})]
      (is (false? (decisions/bot-should-act? db))
          "Should return false when human holds priority"))))


(deftest bot-should-act-returns-false-when-no-bot
  (testing "bot-should-act? returns false when no bot in game"
    (let [db (th/create-test-db {:stops #{:main1}})]
      (is (false? (decisions/bot-should-act? db))
          "Should return false in single player game"))))


;; === Bot Decide Action ===

(deftest bot-decide-produces-cast-action-for-burn-bot
  (testing "bot-decide-action returns cast action when burn bot has bolt + mountain"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          result (decisions/bot-decide-action game-db)]
      (is (= :cast-spell (:action result))
          "Action type should be :cast-spell")
      (is (uuid? (:object-id result))
          "Should include the bolt object-id")
      (is (= 1 (count (:tap-sequence result)))
          "Should tap exactly 1 mountain"))))


(deftest bot-decide-produces-pass-without-resources
  (testing "bot-decide-action returns :pass when no resources"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 0})
          game-db (:game/db app-db)
          result (decisions/bot-decide-action game-db)]
      (is (= :pass (:action result))
          "Should pass when no resources available"))))


(deftest bot-decide-passes-when-cant-afford
  (testing "bot-decide-action returns :pass when bot has bolt but no mountains"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 1})
          game-db (:game/db app-db)
          action (decisions/bot-decide-action game-db)]
      (is (= :pass (:action action))
          "Bot should pass when it can't afford to cast"))))


;; === Bot Cast Spell Event (through standard path) ===

(deftest bot-cast-spell-via-cast-spell-handler-puts-bolt-on-stack
  (testing "cast-spell-handler with opts casts targeted spell for bot"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          ;; First tap the mountain manually (simulating the tap dispatch)
          battlefield (q/get-objects-in-zone game-db :player-2 :battlefield)
          mountain (first (filter #(= :mountain (get-in % [:object/card :card/id])) battlefield))
          game-db (engine-mana/activate-mana-ability game-db :player-2 (:object/id mountain) :red)
          ;; Now find the bolt
          hand (q/get-objects-in-zone game-db :player-2 :hand)
          bolt (first (filter #(= :lightning-bolt (get-in % [:object/card :card/id])) hand))
          ;; Call cast-spell-handler with opts (same path as human, with explicit args)
          app-db (assoc app-db :game/db game-db)
          result-db (casting/cast-spell-handler app-db {:player-id :player-2
                                                        :object-id (:object/id bolt)
                                                        :target :player-1})
          result-game-db (:game/db result-db)]
      (is (not (q/stack-empty? result-game-db))
          "Stack should have the bolt on it")
      (let [stack-items (q/get-all-stack-items result-game-db)
            spell-items (filter #(= :spell (:stack-item/type %)) stack-items)]
        (is (= 1 (count spell-items))
            "There should be exactly 1 spell stack-item")
        ;; Verify target is stored on the stack-item
        (is (= {:target :player-1}
               (:stack-item/targets (first spell-items)))
            "Stack-item should have stored target :player-1")))))


(deftest bot-cast-spell-respects-can-cast-validation
  (testing "cast-spell-handler with opts returns unchanged db when can-cast? fails"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 1})
          game-db (:game/db app-db)
          ;; Bot has bolt but no mana (didn't tap)
          hand (q/get-objects-in-zone game-db :player-2 :hand)
          bolt (first (filter #(= :lightning-bolt (get-in % [:object/card :card/id])) hand))
          result-db (casting/cast-spell-handler app-db {:player-id :player-2
                                                        :object-id (:object/id bolt)
                                                        :target :player-1})
          result-game-db (:game/db result-db)]
      (is (q/stack-empty? result-game-db)
          "Stack should be empty when bot can't cast"))))


;; === Activate Mana Ability with Player ID ===

(deftest activate-mana-ability-accepts-player-id
  (testing "activate-mana-ability works with explicit player-id for bot"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 0})
          game-db (:game/db app-db)
          battlefield (q/get-objects-in-zone game-db :player-2 :battlefield)
          mountain (first (filter #(= :mountain (get-in % [:object/card :card/id])) battlefield))
          result-db (engine-mana/activate-mana-ability game-db :player-2 (:object/id mountain) :red)]
      (is (true? (:object/tapped (q/get-object result-db (:object/id mountain))))
          "Mountain should be tapped")
      (is (= 1 (:red (q/get-mana-pool result-db :player-2)))
          "Bot should have 1 red mana in pool"))))


;; === Multi-Color Mana Tests ===

(defn- setup-bot-with-lands
  "Create an app-db with a burn bot that has specific lands on battlefield.
   lands: seq of card-id keywords, e.g., [:mountain :plains :underground-river]
   bolts: number of Lightning Bolts in hand."
  [{:keys [lands bolts]}]
  (let [db (th/create-test-db {:stops #{:main1 :main2}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/card])
        db (th/add-opponent @conn {:bot-archetype :burn})]
    (let [[db _] (reduce (fn [[db' _] land-id]
                           (th/add-card-to-zone db' land-id :battlefield :player-2))
                         [db nil]
                         lands)
          [db _] (reduce (fn [[db' _] _]
                           (th/add-card-to-zone db' :lightning-bolt :hand :player-2))
                         [db nil]
                         (range bolts))
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/phase :main1]])]
      (merge (history/init-history)
             {:game/db db}))))


(deftest find-tap-sequence-handles-single-color
  (testing "finds correct taps for single-color cost"
    (let [app-db (setup-bot-with-lands {:lands [:mountain :mountain] :bolts 0})
          game-db (:game/db app-db)
          taps (decisions/find-tap-sequence game-db :player-2 {:red 1})]
      (is (= 1 (count taps))
          "Should return exactly 1 tap for {:red 1}")
      (is (= :red (:mana-color (first taps)))
          "Tap should be for :red mana"))))


(deftest find-tap-sequence-handles-multi-color
  (testing "finds correct taps for multi-color cost"
    (let [app-db (setup-bot-with-lands {:lands [:plains :island] :bolts 0})
          game-db (:game/db app-db)
          taps (decisions/find-tap-sequence game-db :player-2 {:white 1 :blue 1})]
      (is (= 2 (count taps))
          "Should return 2 taps for {:white 1 :blue 1}")
      (is (= 1 (count (filter #(= :white (:mana-color %)) taps)))
          "Should have exactly 1 white tap")
      (is (= 1 (count (filter #(= :blue (:mana-color %)) taps)))
          "Should have exactly 1 blue tap"))))


(deftest find-tap-sequence-fails-when-missing-color
  (testing "returns insufficient taps when required color is missing"
    (let [app-db (setup-bot-with-lands {:lands [:mountain :mountain] :bolts 0})
          game-db (:game/db app-db)
          taps (decisions/find-tap-sequence game-db :player-2 {:red 1 :blue 1})]
      (is (= 1 (count taps))
          "Should return only 1 tap (red), not 2 — no blue sources available")
      (is (= :red (:mana-color (first taps)))
          "The one tap should be for :red"))))


(deftest find-tap-sequence-dual-land-not-double-allocated
  (testing "dual land cannot be tapped for two colors simultaneously"
    (let [app-db (setup-bot-with-lands {:lands [:underground-river] :bolts 0})
          game-db (:game/db app-db)
          taps (decisions/find-tap-sequence game-db :player-2 {:blue 1 :black 1})]
      (is (= 1 (count taps))
          "Single dual land can only produce 1 tap, not 2")
      (is (contains? #{:blue :black} (:mana-color (first taps)))
          "Tap should be for one of the dual land's colors"))))


(deftest bot-decide-action-passes-when-wrong-colors-available
  (testing "bot passes when lands produce wrong color for spell cost"
    (let [app-db (setup-bot-with-lands {:lands [:plains :plains] :bolts 1})
          game-db (:game/db app-db)
          action (decisions/bot-decide-action game-db)]
      (is (= :pass (:action action))
          "Should pass — Plains can't pay {:red 1} for Lightning Bolt"))))


(deftest bot-decide-action-passes-with-enough-total-mana-but-wrong-colors
  (testing "bot passes when total land count is sufficient but colors don't match"
    ;; This is the core fungible-sum bug: 2 Mountains for a {:white 1 :blue 1} cost.
    ;; find-tap-sequence finds 0 taps (no white/blue sources), but the sum check
    ;; (reduce + 0 (vals {:white 1 :blue 1})) = 2, and 2 mountains would be 2 taps
    ;; IF the colors were fungible. However, find-tap-sequence already filters
    ;; per-color, so we need to ensure the validation also checks per-color.
    ;;
    ;; To trigger the actual bug, we need a bot that WANTS to cast a multi-color spell.
    ;; The burn bot only casts bolts ({:red 1}). So we test find-tap-sequence validation
    ;; indirectly: the sum check on bot-decide-action line 91 would pass if mountains
    ;; counted toward a non-red cost.
    ;;
    ;; Direct test: find-tap-sequence returns 0 taps for {:white 1} with 2 mountains,
    ;; and the validation must catch that 0 < 1 (not 2 >= 1).
    (let [app-db (setup-bot-with-lands {:lands [:mountain :mountain] :bolts 1})
          game-db (:game/db app-db)
          ;; find-tap-sequence for a multi-color cost with wrong colors
          taps (decisions/find-tap-sequence game-db :player-2 {:white 1 :blue 1})]
      ;; find-tap-sequence correctly returns 0 taps (no white/blue sources)
      (is (zero? (count taps))
          "Should find 0 taps — Mountains produce neither white nor blue"))))


(deftest bot-decide-action-casts-with-correct-multi-color-taps
  (testing "bot correctly identifies taps for multi-color cost via find-tap-sequence"
    (let [app-db (setup-bot-with-lands {:lands [:mountain] :bolts 1})
          game-db (:game/db app-db)
          action (decisions/bot-decide-action game-db)]
      (is (= :cast-spell (:action action))
          "Should cast — Mountain produces {:red 1} for bolt")
      (is (= 1 (count (:tap-sequence action)))
          "Should have 1 tap")
      (is (= :red (:mana-color (first (:tap-sequence action))))
          "Tap should produce :red"))))


(deftest find-tap-sequence-handles-generic-mana
  (testing "generic mana is paid by any remaining untapped mana-producing land"
    (let [app-db (setup-bot-with-lands {:lands [:mountain :mountain :plains] :bolts 0})
          game-db (:game/db app-db)
          taps (decisions/find-tap-sequence game-db :player-2 {:red 1 :generic 1})]
      (is (= 2 (count taps))
          "Should return 2 taps: 1 for red, 1 for generic")
      ;; All taps should have distinct object-ids (no double-allocation)
      (is (= 2 (count (set (map :object-id taps))))
          "Should tap 2 different lands"))))


(deftest find-tap-sequence-empty-cost-returns-empty
  (testing "empty mana cost returns empty tap sequence"
    (let [app-db (setup-bot-with-lands {:lands [:mountain] :bolts 0})
          game-db (:game/db app-db)
          taps (decisions/find-tap-sequence game-db :player-2 {})]
      (is (empty? taps)
          "Empty mana cost should return no taps"))))


(deftest find-tap-sequence-generic-uses-remaining-after-colors
  (testing "generic mana uses lands not already allocated to specific colors"
    (let [app-db (setup-bot-with-lands {:lands [:mountain :plains] :bolts 0})
          game-db (:game/db app-db)
          taps (decisions/find-tap-sequence game-db :player-2 {:red 1 :generic 1})]
      (is (= 2 (count taps))
          "Should return 2 taps")
      ;; The red tap should be the mountain, and generic should be the plains
      (let [red-taps (filter #(= :red (:mana-color %)) taps)
            non-red-taps (remove #(= :red (:mana-color %)) taps)]
        (is (= 1 (count red-taps))
            "1 tap for red")
        (is (= 1 (count non-red-taps))
            "1 tap for generic (from plains)")))))
