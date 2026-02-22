(ns fizzle.events.setup-test
  "Tests for setup screen events - deck config, sideboard swaps, presets."
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [fizzle.bots.protocol :as bot]
    [fizzle.db.storage :as storage]
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
      (is (= (:deck/main setup/iggy-pop-decklist) (:setup/main-deck db))
          "Should load iggy-pop main deck")
      (is (= (:deck/side setup/iggy-pop-decklist) (:setup/sideboard db))
          "Should load iggy-pop sideboard")
      (is (= :setup (:active-screen db))
          "Should set active screen to setup"))))


;; === Select Deck ===

(deftest test-select-deck-loads-config
  (testing "select-deck loads the specified deck's main and side"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/select-deck-handler :iggy-pop))]
      (is (= :iggy-pop (:setup/selected-deck db)))
      (is (= (:deck/main setup/iggy-pop-decklist) (:setup/main-deck db)))
      (is (= (:deck/side setup/iggy-pop-decklist) (:setup/sideboard db))))))


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
      (is (= 1 (:count main-cot))
          "New main entry should have count 1"))))


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
                 (setup/set-bot-archetype-handler :burn)
                 (setup/save-preset-handler "Custom"))
          ;; Change bot archetype after saving
          db-changed (setup/set-bot-archetype-handler db :goldfish)
          ;; Load preset should restore bot archetype to :burn
          db-loaded (setup/load-preset-handler db-changed "Custom")]
      (is (= :burn (:setup/bot-archetype db-loaded))
          "Bot archetype should be restored from preset"))))


(deftest test-load-preset-unknown-name-noop
  (testing "load-preset with unknown name is no-op"
    (let [db (setup/init-setup-handler {})
          db-after (setup/load-preset-handler db "NonExistent")]
      (is (= (:setup/bot-archetype db) (:setup/bot-archetype db-after))
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
      (is (= :opening-hand (:active-screen db-after))
          "Should switch to opening-hand screen")
      (is (some? (:game/db db-after))
          "Should have initialized game db"))))


(deftest test-start-game-with-oversized-deck
  (testing "start-game allows decks larger than 60 cards"
    (let [db (-> (setup/init-setup-handler {})
                 ;; Move cards from side to main, making main > 60
                 (setup/move-to-main-handler :merchant-scroll)
                 (setup/move-to-main-handler :merchant-scroll))
          db-after (setup/start-game-handler db)]
      (is (= :opening-hand (:active-screen db-after))
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
      (is (= :opening-hand (:active-screen db-after))
          "Should switch to opening-hand screen")
      (is (some? (:game/db db-after))
          "Should have initialized game db"))))


(deftest test-quick-start-without-preset-noop
  (testing "quick-start is no-op when no MRU preset"
    (let [db {:setup/selected-deck :iggy-pop
              :setup/main-deck (:deck/main setup/iggy-pop-decklist)
              :setup/sideboard (:deck/side setup/iggy-pop-decklist)
              :setup/presets {}
              :setup/last-preset nil
              :active-screen :setup}
          db-after (setup/quick-start-handler db)]
      (is (= :setup (:active-screen db-after))
          "Should stay on setup screen"))))


(deftest test-quick-start-with-stale-preset-noop
  (testing "quick-start is no-op when MRU preset no longer exists"
    (let [db {:setup/selected-deck :iggy-pop
              :setup/main-deck (:deck/main setup/iggy-pop-decklist)
              :setup/sideboard (:deck/side setup/iggy-pop-decklist)
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
      (is (= (:setup/bot-archetype setup-db) (:setup/bot-archetype restored))
          "Bot archetype should match pre-game config"))))


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
      (is (= :opening-hand (:active-screen new-db))
          "Should go to opening-hand screen")
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


(deftest test-new-game-preserves-bot-archetype
  (testing "new-game uses stashed bot-archetype instead of defaulting to goldfish"
    (let [setup-db (-> (setup/init-setup-handler {})
                       (assoc :setup/bot-archetype :burn))
          game-db (setup/start-game-handler setup-db)
          ;; Verify burn was used in the original game
          _ (is (= :burn (bot/get-bot-archetype (:game/db game-db) :opponent))
                "Original game should have burn bot")
          ;; Re-deal via new-game-handler
          new-db (setup/new-game-handler game-db)]
      (is (= :burn (bot/get-bot-archetype (:game/db new-db) :opponent))
          "New game should preserve burn bot archetype from stashed config"))))


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
                           {:main-deck (:deck/main setup/iggy-pop-decklist)
                            :sideboard (:deck/side setup/iggy-pop-decklist)
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


;; === Imported Deck Storage ===

(deftest test-load-imported-decks-empty
  (testing "load-imported-decks returns {} when nothing stored"
    (is (= {} (storage/load-imported-decks))
        "Should return empty map when no imported decks in localStorage")))


(deftest test-save-and-load-imported-decks
  (testing "save-imported-decks! persists and load-imported-decks retrieves"
    (let [decks {:my-storm {:deck/id :my-storm
                            :deck/name "My Storm"
                            :deck/main [{:card/id :dark-ritual :count 4}]
                            :deck/side []
                            :deck/source :imported}}]
      (storage/save-imported-decks! decks)
      (is (= decks (storage/load-imported-decks))
          "Should round-trip imported decks through localStorage"))))


;; === Init Setup Loads Imported Decks ===

(deftest test-init-setup-loads-imported-decks
  (testing "init-setup includes imported decks from localStorage"
    (let [decks {:my-storm {:deck/id :my-storm
                            :deck/name "My Storm"
                            :deck/main [{:card/id :dark-ritual :count 4}]
                            :deck/side []
                            :deck/source :imported}}]
      (storage/save-imported-decks! decks)
      (let [db (setup/init-setup-handler {})]
        (is (= decks (:setup/imported-decks db))
            "Should load imported decks on init")))))


;; === Open/Close Import Modal ===

(deftest test-open-import-modal
  (testing "open-import-modal sets empty modal state"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/open-import-modal-handler))]
      (is (= {:name "" :text "" :errors nil :editing-deck-id nil}
             (:setup/import-modal db))
          "Should set empty import modal state"))))


(deftest test-close-import-modal
  (testing "close-import-modal clears modal state"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/open-import-modal-handler)
                 (setup/close-import-modal-handler))]
      (is (nil? (:setup/import-modal db))
          "Should clear import modal state"))))


;; === Set Import Name/Text ===

(deftest test-set-import-name
  (testing "set-import-name updates modal name"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/open-import-modal-handler)
                 (setup/set-import-name-handler "My Deck"))]
      (is (= "My Deck" (get-in db [:setup/import-modal :name]))
          "Should update import modal name"))))


(deftest test-set-import-text
  (testing "set-import-text updates modal text and clears errors"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/open-import-modal-handler)
                 ;; Simulate having errors from a previous attempt
                 (assoc-in [:setup/import-modal :errors] ["Some Card"])
                 (setup/set-import-text-handler "4 Dark Ritual"))]
      (is (= "4 Dark Ritual" (get-in db [:setup/import-modal :text]))
          "Should update import modal text")
      (is (nil? (get-in db [:setup/import-modal :errors]))
          "Should clear errors when text changes"))))


;; === Confirm Import ===

(deftest test-confirm-import-success
  (testing "confirm-import with valid text saves deck and populates setup"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/open-import-modal-handler)
                 (setup/set-import-name-handler "My Storm")
                 (setup/set-import-text-handler "4 Dark Ritual\n4 Cabal Ritual"))
          db-after (setup/confirm-import-handler db)]
      (is (nil? (:setup/import-modal db-after))
          "Should close modal on success")
      (is (contains? (:setup/imported-decks db-after) :my-storm)
          "Should add deck to imported-decks")
      (is (= "My Storm"
             (get-in db-after [:setup/imported-decks :my-storm :deck/name]))
          "Should store original name")
      (is (= :imported
             (get-in db-after [:setup/imported-decks :my-storm :deck/source]))
          "Should tag as imported")
      (is (= :my-storm (:setup/selected-deck db-after))
          "Should select the imported deck")
      (is (seq (:setup/main-deck db-after))
          "Should populate main deck")
      (is (= {} (:setup/must-contain db-after))
          "Should clear must-contain"))))


(deftest test-confirm-import-error
  (testing "confirm-import with unrecognized cards sets errors and keeps modal open"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/open-import-modal-handler)
                 (setup/set-import-name-handler "Bad Deck")
                 (setup/set-import-text-handler "4 Nonexistent Card"))
          db-after (setup/confirm-import-handler db)]
      (is (seq (get-in db-after [:setup/import-modal :errors]))
          "Should have error list")
      (is (not (contains? (:setup/imported-decks db-after) :bad-deck))
          "Should not save deck on error"))))


(deftest test-confirm-import-with-sideboard
  (testing "confirm-import parses sideboard section"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/open-import-modal-handler)
                 (setup/set-import-name-handler "With Side")
                 (setup/set-import-text-handler
                   "4 Dark Ritual\n\nSideboard\n2 Merchant Scroll"))
          db-after (setup/confirm-import-handler db)]
      (is (nil? (:setup/import-modal db-after))
          "Should close modal")
      (let [deck (get-in db-after [:setup/imported-decks :with-side])]
        (is (some #(= :dark-ritual (:card/id %)) (:deck/main deck))
            "Main should have Dark Ritual")
        (is (some #(= :merchant-scroll (:card/id %)) (:deck/side deck))
            "Side should have Merchant Scroll")))))


;; === Select Deck with Imported ===

(deftest test-select-imported-deck
  (testing "select-deck loads an imported deck's cards"
    (let [imported-deck {:deck/id :my-storm
                         :deck/name "My Storm"
                         :deck/main [{:card/id :dark-ritual :count 4}
                                     {:card/id :cabal-ritual :count 4}]
                         :deck/side [{:card/id :merchant-scroll :count 2}]
                         :deck/source :imported}
          db (-> (setup/init-setup-handler {})
                 (assoc :setup/imported-decks {:my-storm imported-deck})
                 (setup/select-deck-handler :my-storm))]
      (is (= :my-storm (:setup/selected-deck db))
          "Should select imported deck")
      (is (= (:deck/main imported-deck) (:setup/main-deck db))
          "Should load imported deck main")
      (is (= (:deck/side imported-deck) (:setup/sideboard db))
          "Should load imported deck sideboard")
      (is (= {} (:setup/must-contain db))
          "Should clear must-contain"))))


;; === Delete Imported Deck ===

(deftest test-delete-imported-deck
  (testing "delete-imported-deck removes deck and falls back to iggy-pop"
    (let [imported-deck {:deck/id :my-storm
                         :deck/name "My Storm"
                         :deck/main [{:card/id :dark-ritual :count 4}]
                         :deck/side []
                         :deck/source :imported}
          db (-> (setup/init-setup-handler {})
                 (assoc :setup/imported-decks {:my-storm imported-deck})
                 (assoc :setup/selected-deck :my-storm)
                 (setup/delete-imported-deck-handler :my-storm))]
      (is (not (contains? (:setup/imported-decks db) :my-storm))
          "Should remove deck from imported-decks")
      (is (= :iggy-pop (:setup/selected-deck db))
          "Should fall back to iggy-pop when deleted deck was selected"))))


(deftest test-delete-imported-deck-other-selected
  (testing "delete-imported-deck does not change selection when different deck active"
    (let [deck-a {:deck/id :deck-a :deck/name "A"
                  :deck/main [{:card/id :dark-ritual :count 4}]
                  :deck/side [] :deck/source :imported}
          deck-b {:deck/id :deck-b :deck/name "B"
                  :deck/main [{:card/id :cabal-ritual :count 4}]
                  :deck/side [] :deck/source :imported}
          db (-> (setup/init-setup-handler {})
                 (assoc :setup/imported-decks {:deck-a deck-a :deck-b deck-b})
                 (assoc :setup/selected-deck :deck-b)
                 (setup/delete-imported-deck-handler :deck-a))]
      (is (= :deck-b (:setup/selected-deck db))
          "Should keep current selection when deleting a different deck"))))


;; === Open Edit Modal ===

(deftest test-open-edit-modal
  (testing "open-edit-modal pre-populates name and text from existing deck"
    (let [imported-deck {:deck/id :my-storm
                         :deck/name "My Storm"
                         :deck/main [{:card/id :dark-ritual :count 4}]
                         :deck/side []
                         :deck/source :imported}
          db (-> (setup/init-setup-handler {})
                 (assoc :setup/imported-decks {:my-storm imported-deck})
                 (setup/open-edit-modal-handler :my-storm))]
      (is (= "My Storm" (get-in db [:setup/import-modal :name]))
          "Should pre-fill name")
      (is (string? (get-in db [:setup/import-modal :text]))
          "Should have regenerated text")
      (is (= :my-storm (get-in db [:setup/import-modal :editing-deck-id]))
          "Should set editing-deck-id"))))


;; === Stash/Restore Preserves Imported Decks ===

(deftest test-stash-preserves-imported-decks
  (testing "stashed config includes imported-decks for restore"
    (let [decks {:my-storm {:deck/id :my-storm
                            :deck/name "My Storm"
                            :deck/main [{:card/id :dark-ritual :count 4}]
                            :deck/side []
                            :deck/source :imported}}
          db (-> (setup/init-setup-handler {})
                 (assoc :setup/imported-decks decks)
                 (setup/start-game-handler))]
      (is (= decks (:setup/imported-decks (:setup/stashed-config db)))
          "Stashed config should include imported-decks"))))


;; === Slug Generation ===

(deftest test-confirm-import-name-slugification
  (testing "confirm-import slugifies deck name to keyword"
    (let [db (-> (setup/init-setup-handler {})
                 (setup/open-import-modal-handler)
                 (setup/set-import-name-handler "My Storm Deck!")
                 (setup/set-import-text-handler "4 Dark Ritual"))
          db-after (setup/confirm-import-handler db)]
      (is (contains? (:setup/imported-decks db-after) :my-storm-deck)
          "Should slugify name: lowercase, spaces to hyphens, strip non-alphanum"))))
