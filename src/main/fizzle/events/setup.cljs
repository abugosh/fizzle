(ns fizzle.events.setup
  (:require
    [clojure.string :as str]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.events.game :as game]
    [fizzle.storage :as storage]
    [re-frame.core :as rf]))


(defn- deck-count
  "Sum the :count values in a deck vector."
  [deck]
  (reduce + 0 (map :count deck)))


(defn- update-card-count
  "Update the count of a card in a deck vector. Removes entry if count reaches 0.
   Creates a new entry if card not present."
  [deck card-id delta]
  (let [existing (some #(when (= card-id (:card/id %)) %) deck)]
    (if existing
      (let [new-count (+ (:count existing) delta)]
        (if (pos? new-count)
          (mapv (fn [entry]
                  (if (= card-id (:card/id entry))
                    (assoc entry :count new-count)
                    entry))
                deck)
          (vec (remove #(= card-id (:card/id %)) deck))))
      ;; Not present - only add if delta is positive
      (if (pos? delta)
        (conj deck {:card/id card-id :count delta})
        deck))))


;; === Handler Functions (pure, testable) ===

(defn init-setup-handler
  "Initialize setup state with defaults and presets from localStorage."
  [_db]
  (let [presets (storage/load-presets)
        last-preset (storage/load-last-preset)]
    {:setup/selected-deck :iggy-pop
     :setup/main-deck (:deck/main cards/iggy-pop-decklist)
     :setup/sideboard (:deck/side cards/iggy-pop-decklist)
     :setup/clock-turns 4
     :setup/must-contain {}
     :setup/presets presets
     :setup/last-preset last-preset
     :active-screen :setup}))


(defn select-deck-handler
  "Load the specified deck's config into setup state."
  [db deck-id]
  (case deck-id
    :iggy-pop
    (assoc db
           :setup/selected-deck :iggy-pop
           :setup/main-deck (:deck/main cards/iggy-pop-decklist)
           :setup/sideboard (:deck/side cards/iggy-pop-decklist)
           :setup/must-contain {})
    ;; Unknown deck - no-op
    db))


(defn move-to-side-handler
  "Move one copy of card-id from main to side.
   Clamps must-contain count if main deck copies drop below it."
  [db card-id]
  (let [main (:setup/main-deck db)
        has-card? (some #(when (= card-id (:card/id %)) %) main)]
    (if has-card?
      (let [side (:setup/sideboard db)
            new-main (update-card-count main card-id -1)
            new-main-count (or (:count (some #(when (= card-id (:card/id %)) %)
                                             new-main))
                               0)
            must-contain (:setup/must-contain db)
            mc-count (get must-contain card-id 0)
            new-must-contain (if (pos? mc-count)
                               (if (zero? new-main-count)
                                 (dissoc must-contain card-id)
                                 (if (> mc-count new-main-count)
                                   (assoc must-contain card-id new-main-count)
                                   must-contain))
                               must-contain)]
        (assoc db
               :setup/main-deck new-main
               :setup/sideboard (update-card-count side card-id 1)
               :setup/must-contain new-must-contain))
      db)))


(defn move-to-main-handler
  "Move one copy of card-id from side to main."
  [db card-id]
  (let [side (:setup/sideboard db)
        has-card? (some #(when (= card-id (:card/id %)) %) side)]
    (if has-card?
      (let [main (:setup/main-deck db)]
        (assoc db
               :setup/sideboard (update-card-count side card-id -1)
               :setup/main-deck (update-card-count main card-id 1)))
      db)))


(defn set-clock-turns-handler
  "Set clock turns, clamped to range 1-20."
  [db n]
  (assoc db :setup/clock-turns (max 1 (min 20 n))))


(defn save-preset-handler
  "Save current setup config as a named preset. No-op if name is blank."
  [db name]
  (if (str/blank? name)
    db
    (let [config {:main-deck (:setup/main-deck db)
                  :sideboard (:setup/sideboard db)
                  :clock-turns (:setup/clock-turns db)
                  :selected-deck (:setup/selected-deck db)
                  :must-contain (:setup/must-contain db)}
          presets (assoc (:setup/presets db) name config)]
      (storage/save-presets! presets)
      (storage/save-last-preset! name)
      (assoc db
             :setup/presets presets
             :setup/last-preset name))))


(defn load-preset-handler
  "Load a named preset into setup state. No-op if name not found."
  [db name]
  (if-let [config (get (:setup/presets db) name)]
    (assoc db
           :setup/main-deck (:main-deck config)
           :setup/sideboard (:sideboard config)
           :setup/clock-turns (:clock-turns config)
           :setup/selected-deck (:selected-deck config)
           :setup/must-contain (get config :must-contain {})
           :setup/last-preset name)
    db))


(defn delete-preset-handler
  "Remove a named preset. Clears last-preset if it was the deleted one."
  [db name]
  (let [presets (dissoc (:setup/presets db) name)
        last-preset (when (not= name (:setup/last-preset db))
                      (:setup/last-preset db))]
    (storage/save-presets! presets)
    (assoc db
           :setup/presets presets
           :setup/last-preset last-preset)))


(defn toggle-must-contain-handler
  "Toggle must-contain for a card. Cycles count: 0->1->...->max->0.
   No-op if card not in main deck or would exceed global cap of 7."
  [db card-id]
  (let [main-deck (:setup/main-deck db)
        main-entry (some #(when (= card-id (:card/id %)) %) main-deck)]
    (if-not main-entry
      db
      (let [max-copies (:count main-entry)
            must-contain (or (:setup/must-contain db) {})
            current (get must-contain card-id 0)
            total (reduce + 0 (vals must-contain))]
        (cond
          ;; Not in must-contain yet — add with count 1 if under cap
          (zero? current)
          (if (< total 7)
            (assoc db :setup/must-contain (assoc must-contain card-id 1))
            db)

          ;; At max copies or incrementing would exceed global cap — cycle to 0
          (or (>= current max-copies)
              (>= total 7))
          (assoc db :setup/must-contain (dissoc must-contain card-id))

          ;; Otherwise increment
          :else
          (assoc db :setup/must-contain (assoc must-contain card-id (inc current))))))))


(defn clear-must-contain-handler
  "Reset must-contain to empty map."
  [db]
  (assoc db :setup/must-contain {}))


(defn- stash-setup-config
  "Extract setup config from db to stash in game state."
  [db]
  {:setup/selected-deck (:setup/selected-deck db)
   :setup/main-deck (:setup/main-deck db)
   :setup/sideboard (:setup/sideboard db)
   :setup/clock-turns (:setup/clock-turns db)
   :setup/must-contain (:setup/must-contain db)
   :setup/presets (:setup/presets db)
   :setup/last-preset (:setup/last-preset db)})


(defn start-game-handler
  "Validate deck and start game. No-op if deck invalid.
   Stashes setup config in game db for restore-to-setup and new-game."
  [db]
  (let [main-deck (:setup/main-deck db)
        main-count (deck-count (or main-deck []))]
    (if (and (seq main-deck)
             (>= main-count 60))
      (let [game-db (game/init-game-state {:main-deck main-deck
                                           :clock-turns (:setup/clock-turns db)
                                           :must-contain (:setup/must-contain db)})]
        (assoc game-db :setup/stashed-config (stash-setup-config db)))
      db)))


(defn restore-setup-handler
  "Return to setup screen, restoring stashed config if available."
  [db]
  (if-let [config (:setup/stashed-config db)]
    (assoc config :active-screen :setup)
    (init-setup-handler db)))


(defn new-game-handler
  "Re-deal with same config. No-op if no stashed config."
  [db]
  (if-let [config (:setup/stashed-config db)]
    (let [game-db (game/init-game-state {:main-deck (:setup/main-deck config)
                                         :clock-turns (:setup/clock-turns config)
                                         :must-contain (get config :setup/must-contain {})})]
      (assoc game-db :setup/stashed-config config))
    db))


(defn quick-start-handler
  "Load MRU preset and start game. No-op if no preset or preset not found."
  [db]
  (let [preset-name (:setup/last-preset db)]
    (if (and preset-name (get (:setup/presets db) preset-name))
      (-> db
          (load-preset-handler preset-name)
          (start-game-handler))
      db)))


;; === Re-frame Event Registrations ===

(rf/reg-event-db
  ::init-setup
  (fn [db _]
    (init-setup-handler db)))


(rf/reg-event-db
  ::select-deck
  (fn [db [_ deck-id]]
    (select-deck-handler db deck-id)))


(rf/reg-event-db
  ::move-to-side
  (fn [db [_ card-id]]
    (move-to-side-handler db card-id)))


(rf/reg-event-db
  ::move-to-main
  (fn [db [_ card-id]]
    (move-to-main-handler db card-id)))


(rf/reg-event-db
  ::set-clock-turns
  (fn [db [_ n]]
    (set-clock-turns-handler db n)))


(rf/reg-event-db
  ::save-preset
  (fn [db [_ name]]
    (save-preset-handler db name)))


(rf/reg-event-db
  ::load-preset
  (fn [db [_ name]]
    (load-preset-handler db name)))


(rf/reg-event-db
  ::delete-preset
  (fn [db [_ name]]
    (delete-preset-handler db name)))


(rf/reg-event-db
  ::toggle-must-contain
  (fn [db [_ card-id]]
    (toggle-must-contain-handler db card-id)))


(rf/reg-event-db
  ::clear-must-contain
  (fn [db _]
    (clear-must-contain-handler db)))


(rf/reg-event-db
  ::start-game
  (fn [db _]
    (start-game-handler db)))


(rf/reg-event-db
  ::restore-setup
  (fn [db _]
    (restore-setup-handler db)))


(rf/reg-event-db
  ::new-game
  (fn [db _]
    (new-game-handler db)))


(rf/reg-event-db
  ::quick-start
  (fn [db _]
    (quick-start-handler db)))
