(ns fizzle.events.cast-spell-opts-test
  "Tests for parameterized ::cast-spell event.

   Validates that cast-spell-handler accepts an optional opts map
   with :player-id, :object-id, and :target so bots can reuse
   the same cast path as humans."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.events.game :as game]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


;; === Setup Helpers ===

(defn- setup-human-with-bolt
  "Create app-db with human player having a bolt in hand and red mana.
   Returns {:app-db app-db :bolt-id obj-id}."
  []
  (let [db (th/create-test-db {:mana {:red 1}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/lightning-bolt])
        db (th/add-opponent @conn)
        [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
        app-db (merge (history/init-history)
                      {:game/db db :game/selected-card bolt-id})]
    {:app-db app-db :bolt-id bolt-id}))


(defn- setup-bot-with-bolt
  "Create app-db with bot player-2 having a bolt in hand and red mana.
   Priority is with player-2 (bot). Returns {:app-db app-db :bolt-id obj-id}."
  []
  (let [db (th/create-test-db {:mana {:red 1}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/lightning-bolt])
        db (th/add-opponent @conn {:bot-archetype :burn})
        ;; Add mana to player-2
        db (mana/add-mana db :player-2 {:red 1})
        ;; Add bolt to player-2's hand
        [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
        app-db (merge (history/init-history)
                      {:game/db db})]
    {:app-db app-db :bolt-id bolt-id}))


(defn- setup-human-with-ritual
  "Create app-db with human player having a Dark Ritual in hand and black mana.
   Returns {:app-db app-db :ritual-id obj-id}."
  []
  (let [db (th/create-test-db {:mana {:black 1}})
        [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
        app-db (merge (history/init-history)
                      {:game/db db :game/selected-card ritual-id})]
    {:app-db app-db :ritual-id ritual-id}))


;; === Test: No opts = existing behavior ===

(deftest cast-spell-handler-no-opts-uses-human-player-and-selected-card
  (testing "cast-spell-handler with no opts uses human-pid and selected-card"
    (let [{:keys [app-db]} (setup-human-with-ritual)
          result (game/cast-spell-handler app-db)]
      ;; Dark Ritual should be on the stack
      (is (not (q/stack-empty? (:game/db result)))
          "Stack should have the ritual on it")
      (let [stack-items (q/get-all-stack-items (:game/db result))
            spell-items (filter #(= :spell (:stack-item/type %)) stack-items)]
        (is (= 1 (count spell-items))
            "There should be exactly 1 spell stack-item")))))


;; === Test: Explicit player-id ===

(deftest cast-spell-handler-with-explicit-player-id
  (testing "cast-spell-handler uses provided player-id instead of human"
    (let [{:keys [app-db]} (setup-bot-with-bolt)
          db (:game/db app-db)
          ;; Add a Dark Ritual to player-2's hand with mana
          db (mana/add-mana db :player-2 {:black 1})
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          app-db (assoc app-db :game/db db)
          result (game/cast-spell-handler app-db {:player-id :player-2
                                                  :object-id ritual-id})]
      (is (not (q/stack-empty? (:game/db result)))
          "Stack should have the ritual from player-2")
      (let [stack-items (q/get-all-stack-items (:game/db result))
            spell-items (filter #(= :spell (:stack-item/type %)) stack-items)]
        (is (= 1 (count spell-items))
            "There should be exactly 1 spell stack-item")))))


;; === Test: Explicit object-id ===

(deftest cast-spell-handler-with-explicit-object-id
  (testing "cast-spell-handler uses provided object-id instead of selected-card"
    (let [{:keys [app-db ritual-id]} (setup-human-with-ritual)
          ;; Clear selected-card to prove it's not used
          app-db (dissoc app-db :game/selected-card)
          result (game/cast-spell-handler app-db {:object-id ritual-id})]
      (is (not (q/stack-empty? (:game/db result)))
          "Stack should have the ritual using explicit object-id")
      (let [stack-items (q/get-all-stack-items (:game/db result))
            spell-items (filter #(= :spell (:stack-item/type %)) stack-items)]
        (is (= 1 (count spell-items))
            "There should be exactly 1 spell stack-item")))))


;; === Test: Targeted spell with explicit target ===

(deftest cast-spell-handler-with-target-on-targeted-spell
  (testing "cast-spell-handler with target skips targeting selection and stores target"
    (let [{:keys [app-db]} (setup-human-with-bolt)
          result (game/cast-spell-handler app-db {:target :player-2})]
      ;; Should NOT have pending-selection (target was pre-determined)
      (is (nil? (:game/pending-selection result))
          "Should not show targeting selection when target is provided")
      ;; Bolt should be on stack with target stored
      (is (not (q/stack-empty? (:game/db result)))
          "Stack should have the bolt")
      (let [stack-items (q/get-all-stack-items (:game/db result))
            spell-items (filter #(= :spell (:stack-item/type %)) stack-items)]
        (is (= 1 (count spell-items))
            "There should be exactly 1 spell stack-item")
        (is (= {:target :player-2}
               (:stack-item/targets (first spell-items)))
            "Stack-item should have stored target :player-2")))))


;; === Test: Targeted spell without target shows selection ===

(deftest cast-spell-handler-targeted-spell-without-target-shows-selection
  (testing "cast-spell-handler on targeted spell without target shows selection UI"
    (let [{:keys [app-db]} (setup-human-with-bolt)
          result (game/cast-spell-handler app-db)]
      ;; Should have pending-selection for targeting
      (is (some? (:game/pending-selection result))
          "Should show targeting selection when no target provided")
      (is (= :cast-time-targeting
             (:selection/type (:game/pending-selection result)))
          "Selection type should be :cast-time-targeting"))))


;; === Test: Bot player-id + object-id + target (full bot path) ===

(deftest cast-spell-handler-full-bot-cast-with-target
  (testing "cast-spell-handler with all opts handles targeted bot cast"
    (let [{:keys [app-db bolt-id]} (setup-bot-with-bolt)
          result (game/cast-spell-handler app-db {:player-id :player-2
                                                  :object-id bolt-id
                                                  :target :player-1})]
      ;; Should NOT have pending-selection
      (is (nil? (:game/pending-selection result))
          "Should not show targeting selection for bot cast with target")
      ;; Bolt should be on stack
      (is (not (q/stack-empty? (:game/db result)))
          "Stack should have the bolt")
      (let [stack-items (q/get-all-stack-items (:game/db result))
            spell-items (filter #(= :spell (:stack-item/type %)) stack-items)]
        (is (= 1 (count spell-items))
            "There should be exactly 1 spell stack-item")
        (is (= {:target :player-1}
               (:stack-item/targets (first spell-items)))
            "Stack-item should have stored target :player-1")))))
