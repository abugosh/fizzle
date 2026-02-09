(ns fizzle.events.setup-test
  "Tests for setup screen events - deck config, sideboard swaps, presets."
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.events.setup :as setup]))


;; Mock localStorage for Node.js tests
(def ^:private mock-storage (atom {}))


(def ^:private create-mock-storage
  (fn []
    #js {:getItem (fn [key] (get @mock-storage key nil))
         :setItem (fn [key value] (swap! mock-storage assoc key value) nil)
         :removeItem (fn [key] (swap! mock-storage dissoc key) nil)
         :clear (fn [] (reset! mock-storage {}) nil)
         :length 0
         :key (fn [_] nil)}))


;; Always use our test mock storage for this test file
(set! js/localStorage (create-mock-storage))


(use-fixtures :each
  {:before (fn [] (reset! mock-storage {}))
   :after (fn [] (reset! mock-storage {}))})


;; === Init Setup ===

(deftest test-init-setup-populates-defaults
  (testing "init-setup sets default deck config and setup screen"
    (let [db (setup/init-setup-handler {})]
      (is (= :iggy-pop (:setup/selected-deck db))
          "Should default to iggy-pop deck")
      (is (= (:deck/main cards/iggy-pop-decklist) (:setup/main-deck db))
          "Should load iggy-pop main deck")
      (is (= (:deck/side cards/iggy-pop-decklist) (:setup/sideboard db))
          "Should load iggy-pop sideboard")
      (is (= 4 (:setup/clock-turns db))
          "Should default clock-turns to 4")
      (is (= :setup (:active-screen db))
          "Should set active screen to setup"))))


;; === Select Deck ===

(deftest test-select-deck-loads-config
  (testing "select-deck loads the specified deck's main and side"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/select-deck-handler :iggy-pop))]
      (is (= :iggy-pop (:setup/selected-deck db)))
      (is (= (:deck/main cards/iggy-pop-decklist) (:setup/main-deck db)))
      (is (= (:deck/side cards/iggy-pop-decklist) (:setup/sideboard db))))))


;; === Move to Side ===

(deftest test-move-to-side-transfers-one-copy
  (testing "move-to-side decrements main and increments side"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/move-to-side-handler :dark-ritual))
          main-dr (->> (:setup/main-deck db)
                       (filter #(= :dark-ritual (:card/id %)))
                       first)
          side-dr (->> (:setup/sideboard db)
                       (filter #(= :dark-ritual (:card/id %)))
                       first)]
      (is (= 3 (:count main-dr))
          "Main should have 3 Dark Rituals after moving one to side")
      (is (some? side-dr)
          "Side should have Dark Ritual entry")
      (is (= 1 (:count side-dr))
          "Side should have 1 Dark Ritual"))))


(deftest test-move-to-side-allowed-past-15
  (testing "move-to-side works even when sideboard already has 15 cards"
    (let [db (setup/init-setup-handler {})
          ;; iggy-pop decklist already has 15 side cards
          side-count (->> (:setup/sideboard db) (map :count) (reduce +))
          db-after (setup/move-to-side-handler db :dark-ritual)
          new-side-count (->> (:setup/sideboard db-after) (map :count) (reduce +))]
      (is (= 15 side-count)
          "Precondition: sideboard starts with 15")
      (is (= 16 new-side-count)
          "Sideboard should now have 16 cards"))))


(deftest test-move-to-side-removes-entry-at-zero
  (testing "move-to-side removes main entry when count reaches 0"
    (let [db (setup/init-setup-handler {})
          ;; orims-chant has 1 copy in main
          db-after (setup/move-to-side-handler db :orims-chant)
          main-oc (->> (:setup/main-deck db-after)
                       (filter #(= :orims-chant (:card/id %)))
                       first)]
      (is (nil? main-oc)
          "Entry should be removed from main when count reaches 0"))))


;; === Move to Main ===

(deftest test-move-to-main-transfers-one-copy
  (testing "move-to-main decrements side and increments main"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/move-to-main-handler :merchant-scroll))
          side-ms (->> (:setup/sideboard db)
                       (filter #(= :merchant-scroll (:card/id %)))
                       first)
          main-ms (->> (:setup/main-deck db)
                       (filter #(= :merchant-scroll (:card/id %)))
                       first)]
      (is (= 1 (:count side-ms))
          "Side should have 1 Merchant Scroll after moving one to main")
      (is (some? main-ms)
          "Main should have Merchant Scroll entry")
      (is (= 1 (:count main-ms))
          "Main should have 1 Merchant Scroll (new entry)"))))


(deftest test-move-to-main-adds-new-entry
  (testing "move-to-main creates new entry in main for card not already there"
    (let [;; city-of-traitors is in side but not in main for iggy-pop
          db (-> (setup/init-setup-handler {})
                 (setup/move-to-main-handler :city-of-traitors))
          main-cot (->> (:setup/main-deck db)
                        (filter #(= :city-of-traitors (:card/id %)))
                        first)]
      (is (some? main-cot)
          "Should create new entry in main deck")
      (is (= 1 (:count main-cot))
          "New main entry should have count 1"))))


;; === Set Clock Turns ===

(deftest test-set-clock-turns
  (testing "set-clock-turns updates stored value"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/set-clock-turns-handler 6))]
      (is (= 6 (:setup/clock-turns db))))))


(deftest test-set-clock-turns-clamps-to-range
  (testing "set-clock-turns clamps value to 1-20"
    (let [db (setup/init-setup-handler {})]
      (is (= 1 (:setup/clock-turns (setup/set-clock-turns-handler db 0)))
          "Should clamp 0 to 1")
      (is (= 1 (:setup/clock-turns (setup/set-clock-turns-handler db -5)))
          "Should clamp negative to 1")
      (is (= 20 (:setup/clock-turns (setup/set-clock-turns-handler db 25)))
          "Should clamp 25 to 20"))))


;; === Save Preset ===

(deftest test-save-preset-stores-config
  (testing "save-preset stores current config under given name"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/save-preset-handler "Test"))]
      (is (contains? (:setup/presets db) "Test")
          "Should have preset named Test")
      (is (= "Test" (:setup/last-preset db))
          "Should set last-preset to saved name"))))


(deftest test-save-preset-empty-name-noop
  (testing "save-preset with empty name is no-op"
    (let [db (setup/init-setup-handler {})
          db-after (setup/save-preset-handler db "")]
      (is (= (:setup/presets db) (:setup/presets db-after))
          "Presets should be unchanged"))
    (let [db (setup/init-setup-handler {})
          db-after (setup/save-preset-handler db "   ")]
      (is (= (:setup/presets db) (:setup/presets db-after))
          "Presets should be unchanged for blank name"))))


;; === Load Preset ===

(deftest test-load-preset-restores-config
  (testing "load-preset restores saved config"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/set-clock-turns-handler 8)
                 (setup/save-preset-handler "Custom"))
          ;; Change clock turns after saving
          db-changed (setup/set-clock-turns-handler db 3)
          ;; Load preset should restore clock turns to 8
          db-loaded (setup/load-preset-handler db-changed "Custom")]
      (is (= 8 (:setup/clock-turns db-loaded))
          "Clock turns should be restored from preset"))))


(deftest test-load-preset-unknown-name-noop
  (testing "load-preset with unknown name is no-op"
    (let [db (setup/init-setup-handler {})
          db-after (setup/load-preset-handler db "NonExistent")]
      (is (= (:setup/clock-turns db) (:setup/clock-turns db-after))
          "State should be unchanged for unknown preset"))))


;; === Delete Preset ===

(deftest test-delete-preset-removes
  (testing "delete-preset removes the named preset"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/save-preset-handler "ToDelete")
                 (setup/delete-preset-handler "ToDelete"))]
      (is (not (contains? (:setup/presets db) "ToDelete"))
          "Preset should be removed"))))


(deftest test-delete-preset-clears-last-if-match
  (testing "delete-preset clears last-preset if it was the deleted one"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/save-preset-handler "Active")
                 (setup/delete-preset-handler "Active"))]
      (is (nil? (:setup/last-preset db))
          "Last-preset should be cleared when deleted preset was MRU"))))


;; === Start Game ===

(deftest test-start-game-with-valid-deck
  (testing "start-game creates game when deck has 60+ cards"
    (let [db (setup/init-setup-handler {})
          db-after (setup/start-game-handler db)]
      (is (= :game (:active-screen db-after))
          "Should switch to game screen")
      (is (some? (:game/db db-after))
          "Should have initialized game db"))))


(deftest test-start-game-with-oversized-deck
  (testing "start-game allows decks larger than 60 cards"
    (let [db (-> (setup/init-setup-handler {})
                 ;; Move cards from side to main, making main > 60
                 (setup/move-to-main-handler :merchant-scroll)
                 (setup/move-to-main-handler :merchant-scroll))
          db-after (setup/start-game-handler db)]
      (is (= :game (:active-screen db-after))
          "Should allow oversized deck"))))


(deftest test-start-game-invalid-deck-noop
  (testing "start-game is no-op when main deck has fewer than 60 cards"
    (let [db (-> (setup/init-setup-handler {})
                 ;; Set a small main deck (under 60)
                 (assoc :setup/main-deck [{:card/id :dark-ritual :count 4}]))]
      (let [db-after (setup/start-game-handler db)]
        (is (= :setup (:active-screen db-after))
            "Should stay on setup screen")
        (is (nil? (:game/db db-after))
            "Should not have game db")))))


;; === Quick Start ===

(deftest test-quick-start-with-saved-preset
  (testing "quick-start loads MRU preset and starts game"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/save-preset-handler "My Preset"))
          db-after (setup/quick-start-handler db)]
      (is (= :game (:active-screen db-after))
          "Should switch to game screen")
      (is (some? (:game/db db-after))
          "Should have initialized game db"))))


(deftest test-quick-start-without-preset-noop
  (testing "quick-start is no-op when no MRU preset"
    (let [db {:setup/selected-deck :iggy-pop
              :setup/main-deck (:deck/main cards/iggy-pop-decklist)
              :setup/sideboard (:deck/side cards/iggy-pop-decklist)
              :setup/clock-turns 4
              :setup/presets {}
              :setup/last-preset nil
              :active-screen :setup}
          db-after (setup/quick-start-handler db)]
      (is (= :setup (:active-screen db-after))
          "Should stay on setup screen"))))


(deftest test-quick-start-with-stale-preset-noop
  (testing "quick-start is no-op when MRU preset no longer exists"
    (let [db {:setup/selected-deck :iggy-pop
              :setup/main-deck (:deck/main cards/iggy-pop-decklist)
              :setup/sideboard (:deck/side cards/iggy-pop-decklist)
              :setup/clock-turns 4
              :setup/presets {}
              :setup/last-preset "Deleted Preset"
              :active-screen :setup}
          db-after (setup/quick-start-handler db)]
      (is (= :setup (:active-screen db-after))
          "Should stay on setup screen"))))


;; === Restore Setup ===

(deftest test-restore-setup-from-game
  (testing "restore-setup returns to setup with stashed config preserved"
    (let [setup-db (-> (setup/init-setup-handler {})
                       ;; Swap a card so sideboard differs from default
                       (setup/move-to-main-handler :merchant-scroll)
                       (setup/move-to-side-handler :dark-ritual))
          game-db (setup/start-game-handler setup-db)
          restored (setup/restore-setup-handler game-db)]
      (is (= :setup (:active-screen restored))
          "Should be on setup screen")
      (is (= (:setup/main-deck setup-db) (:setup/main-deck restored))
          "Main deck should match pre-game config")
      (is (= (:setup/sideboard setup-db) (:setup/sideboard restored))
          "Sideboard should match pre-game config")
      (is (= (:setup/clock-turns setup-db) (:setup/clock-turns restored))
          "Clock turns should match pre-game config"))))


(deftest test-restore-setup-without-stash-falls-back
  (testing "restore-setup falls back to init-setup when no stashed config"
    (let [db {:active-screen :game}
          restored (setup/restore-setup-handler db)]
      (is (= :setup (:active-screen restored))
          "Should be on setup screen")
      (is (= :iggy-pop (:setup/selected-deck restored))
          "Should have default deck"))))


;; === New Game ===

(deftest test-new-game-redeals-same-config
  (testing "new-game creates fresh game with same deck config"
    (let [setup-db (setup/init-setup-handler {})
          game-db (setup/start-game-handler setup-db)
          new-db (setup/new-game-handler game-db)]
      (is (= :game (:active-screen new-db))
          "Should stay on game screen")
      (is (some? (:game/db new-db))
          "Should have game db")
      (is (some? (:setup/stashed-config new-db))
          "Should preserve stashed config for further restarts"))))


(deftest test-new-game-without-stash-noop
  (testing "new-game is no-op when no stashed config"
    (let [db {:active-screen :game}
          db-after (setup/new-game-handler db)]
      (is (= db db-after)
          "Should return db unchanged"))))


;; === Toggle Must-Contain ===

(deftest test-toggle-must-contain-adds-card
  (testing "toggle-must-contain adds card with count 1 when not in must-contain"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :dark-ritual))]
      (is (= 1 (get-in db [:setup/must-contain :dark-ritual]))
          "Should add dark-ritual with count 1"))))


(deftest test-toggle-must-contain-increments
  (testing "toggle-must-contain increments count when card already in must-contain"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual))]
      (is (= 2 (get-in db [:setup/must-contain :dark-ritual]))
          "Should increment to 2"))))


(deftest test-toggle-must-contain-cycles-to-zero
  (testing "toggle-must-contain removes card when count reaches max copies"
    (let [db (-> (setup/init-setup-handler {})
                 ;; dark-ritual has 4 copies in main
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 ;; now at 4 = max, next click should cycle to 0
                 (setup/toggle-must-contain-handler :dark-ritual))]
      (is (nil? (get-in db [:setup/must-contain :dark-ritual]))
          "Should remove card from must-contain after reaching max"))))


(deftest test-toggle-must-contain-noop-for-missing-card
  (testing "toggle-must-contain is no-op for card not in main deck"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :nonexistent-card))]
      (is (= {} (:setup/must-contain db))
          "Must-contain should remain empty for card not in deck"))))


(deftest test-toggle-must-contain-global-cap-at-7
  (testing "toggle-must-contain caps total must-contain at 7"
    (let [db (-> (setup/init-setup-handler {})
                 ;; Add 4 dark-ritual + 3 cabal-ritual = 7 total
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :cabal-ritual)
                 (setup/toggle-must-contain-handler :cabal-ritual)
                 (setup/toggle-must-contain-handler :cabal-ritual)
                 ;; Total is 7, adding another should be no-op
                 (setup/toggle-must-contain-handler :brain-freeze))]
      (is (nil? (get-in db [:setup/must-contain :brain-freeze]))
          "Should not add brain-freeze when total is already 7"))))


(deftest test-toggle-must-contain-increment-respects-global-cap
  (testing "toggle-must-contain prevents increment when it would exceed 7"
    (let [db (-> (setup/init-setup-handler {})
                 ;; Add 4 dark-ritual + 3 cabal-ritual = 7 total
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :cabal-ritual)
                 (setup/toggle-must-contain-handler :cabal-ritual)
                 (setup/toggle-must-contain-handler :cabal-ritual)
                 ;; Total is 7, incrementing cabal-ritual would make 8
                 ;; Should cycle to 0 instead
                 (setup/toggle-must-contain-handler :cabal-ritual))]
      (is (nil? (get-in db [:setup/must-contain :cabal-ritual]))
          "Should cycle to 0 when incrementing would exceed cap"))))


(deftest test-toggle-must-contain-single-copy-card
  (testing "toggle-must-contain cycles 0->1->0 for card with 1 copy"
    (let [db (-> (setup/init-setup-handler {})
                 ;; orims-chant has 1 copy in main
                 (setup/toggle-must-contain-handler :orims-chant))]
      (is (= 1 (get-in db [:setup/must-contain :orims-chant]))
          "First click adds with count 1"))
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :orims-chant)
                 (setup/toggle-must-contain-handler :orims-chant))]
      (is (nil? (get-in db [:setup/must-contain :orims-chant]))
          "Second click removes (cycles back to 0)"))))


;; === Clear Must-Contain ===

(deftest test-clear-must-contain
  (testing "clear-must-contain resets to empty map"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :cabal-ritual)
                 (setup/clear-must-contain-handler))]
      (is (= {} (:setup/must-contain db))
          "Must-contain should be empty after clear"))))


;; === Init Includes Must-Contain ===

(deftest test-init-setup-includes-must-contain
  (testing "init-setup includes empty must-contain map"
    (let [db (setup/init-setup-handler {})]
      (is (= {} (:setup/must-contain db))
          "Should initialize with empty must-contain map"))))


;; === Preset Save/Load with Must-Contain ===

(deftest test-save-preset-includes-must-contain
  (testing "save-preset includes must-contain in saved config"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/save-preset-handler "With Must-Contain"))]
      (is (= {:dark-ritual 2}
             (:must-contain (get (:setup/presets db) "With Must-Contain")))
          "Saved preset should contain must-contain map"))))


(deftest test-load-preset-restores-must-contain
  (testing "load-preset restores must-contain from preset"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/save-preset-handler "Has MC")
                 ;; Clear must-contain
                 (setup/clear-must-contain-handler))
          ;; Verify cleared
          _ (is (= {} (:setup/must-contain db)))
          ;; Load preset should restore
          db-loaded (setup/load-preset-handler db "Has MC")]
      (is (= {:dark-ritual 2} (:setup/must-contain db-loaded))
          "Must-contain should be restored from preset"))))


(deftest test-load-preset-backward-compat
  (testing "load-preset defaults must-contain to {} for old presets"
    (let [db (-> (setup/init-setup-handler {})
                 ;; Manually inject an old-style preset without must-contain key
                 (assoc-in [:setup/presets "Old Preset"]
                           {:main-deck (:deck/main cards/iggy-pop-decklist)
                            :sideboard (:deck/side cards/iggy-pop-decklist)
                            :clock-turns 4
                            :selected-deck :iggy-pop}))
          db-loaded (setup/load-preset-handler db "Old Preset")]
      (is (= {} (:setup/must-contain db-loaded))
          "Must-contain should default to {} for old presets"))))


;; === Move to Side Clamps Must-Contain ===

(deftest test-move-to-side-clamps-must-contain
  (testing "move-to-side clamps must-contain when main count drops below it"
    (let [db (-> (setup/init-setup-handler {})
                 ;; orims-chant has 1 copy in main, set must-contain to 1
                 (setup/toggle-must-contain-handler :orims-chant)
                 ;; Move it to side — main count drops to 0
                 (setup/move-to-side-handler :orims-chant))]
      (is (nil? (get-in db [:setup/must-contain :orims-chant]))
          "Must-contain should be removed when card fully moved to side"))))


(deftest test-move-to-side-clamps-must-contain-partial
  (testing "move-to-side clamps must-contain to remaining main count"
    (let [db (-> (setup/init-setup-handler {})
                 ;; dark-ritual has 4 in main, set must-contain to 3
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 ;; Move 2 to side — main drops to 2
                 (setup/move-to-side-handler :dark-ritual)
                 (setup/move-to-side-handler :dark-ritual))]
      (is (= 2 (get-in db [:setup/must-contain :dark-ritual]))
          "Must-contain should clamp to 2 (remaining main count)"))))


(deftest test-move-to-side-no-clamp-needed
  (testing "move-to-side does not affect must-contain when count still valid"
    (let [db (-> (setup/init-setup-handler {})
                 ;; dark-ritual has 4 in main, set must-contain to 1
                 (setup/toggle-must-contain-handler :dark-ritual)
                 ;; Move 1 to side — main drops to 3, still >= must-contain 1
                 (setup/move-to-side-handler :dark-ritual))]
      (is (= 1 (get-in db [:setup/must-contain :dark-ritual]))
          "Must-contain should stay at 1 when main still has 3"))))


;; === Select Deck Resets Must-Contain ===

(deftest test-select-deck-resets-must-contain
  (testing "select-deck resets must-contain to empty map"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/select-deck-handler :iggy-pop))]
      (is (= {} (:setup/must-contain db))
          "Must-contain should reset on deck change"))))


;; === Stash/Restore Preserves Must-Contain ===

(deftest test-stash-preserves-must-contain
  (testing "stashed config includes must-contain for restore-setup"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/toggle-must-contain-handler :dark-ritual)
                 (setup/start-game-handler))]
      (is (= {:dark-ritual 2}
             (:setup/must-contain (:setup/stashed-config db)))
          "Stashed config should include must-contain"))))
