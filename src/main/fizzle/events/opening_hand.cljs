(ns fizzle.events.opening-hand
  "Opening hand events: mulligan, keep, bottom-card selection.
   Operates on live game state between setup and gameplay phases."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.engine.zones :as zones]
    [re-frame.core :as rf]))


(defn mulligan-handler
  "Return all hand cards to library, shuffle, re-deal 7 with sculpted cards guaranteed.
   Pure function: (app-db) -> app-db"
  [app-db]
  (let [game-db (:game/db app-db)
        must-contain (:opening-hand/must-contain app-db)
        mulligan-count (:opening-hand/mulligan-count app-db)
        ;; Move all hand cards back to library
        human-pid (queries/get-human-player-id game-db)
        hand-objs (queries/get-objects-in-zone game-db human-pid :hand)
        db-after-return (reduce (fn [db obj]
                                  (zone-change-dispatch/move-to-zone-db db (:object/id obj) :library))
                                game-db
                                hand-objs)
        ;; Shuffle library
        db-shuffled (zones/shuffle-library db-after-return human-pid)
        ;; Get all library objects grouped by card-id
        lib-objs (sort-by :object/position (queries/get-objects-in-zone db-shuffled human-pid :library))
        ;; Extract sculpted cards from library
        [sculpted-objs remaining-lib] (if (empty? must-contain)
                                        [[] lib-objs]
                                        (reduce
                                          (fn [[sculpted remaining] [card-id cnt]]
                                            (let [by-card (group-by #(get-in % [:object/card :card/id]) remaining)
                                                  available (get by-card card-id [])
                                                  take-n (min cnt (count available))
                                                  taken (take take-n available)
                                                  taken-ids (set (map :object/id taken))
                                                  rest-objs (remove #(contains? taken-ids (:object/id %)) remaining)]
                                              [(into sculpted taken) (vec rest-objs)]))
                                          [[] lib-objs]
                                          must-contain))
        ;; Take random cards for remaining hand slots
        random-draw-count (- 7 (count sculpted-objs))
        random-objs (take random-draw-count remaining-lib)
        hand-objs-new (concat sculpted-objs random-objs)
        ;; Move selected objects from library to hand
        db-with-hand (reduce (fn [db obj]
                               (zone-change-dispatch/move-to-zone-db db (:object/id obj) :hand))
                             db-shuffled
                             hand-objs-new)
        ;; Track sculpted UUIDs
        sculpted-id-set (set (map :object/id sculpted-objs))]
    (assoc app-db
           :game/db db-with-hand
           :opening-hand/mulligan-count (inc mulligan-count)
           :opening-hand/sculpted-ids sculpted-id-set
           :opening-hand/phase :viewing)))


(defn- clear-opening-hand-keys
  "Remove all opening-hand state keys from app-db."
  [app-db]
  (assoc app-db
         :opening-hand/mulligan-count nil
         :opening-hand/sculpted-ids nil
         :opening-hand/phase nil
         :opening-hand/must-contain nil
         :opening-hand/bottom-selection nil))


(defn keep-handler
  "Keep the current hand. Transitions to game (no mulligans) or bottoming phase (with mulligans).
   Pure function: (app-db) -> app-db"
  [app-db]
  (let [mulligan-count (:opening-hand/mulligan-count app-db)]
    (if (zero? mulligan-count)
      (-> app-db
          (assoc :active-screen :game)
          clear-opening-hand-keys)
      (assoc app-db
             :opening-hand/phase :bottoming
             :opening-hand/bottom-selection #{}))))


(defn toggle-bottom-selection-handler
  "Toggle an object-id in/out of the bottom-selection set.
   No-op if not in :bottoming phase.
   Pure function: (app-db, object-id) -> app-db"
  [app-db object-id]
  (if (not= :bottoming (:opening-hand/phase app-db))
    app-db
    (let [selection (:opening-hand/bottom-selection app-db)]
      (assoc app-db :opening-hand/bottom-selection
             (if (contains? selection object-id)
               (disj selection object-id)
               (conj selection object-id))))))


(defn confirm-bottom-handler
  "Move selected bottom cards from hand to library, transition to :game.
   No-op if selected count != mulligan-count.
   Pure function: (app-db) -> app-db"
  [app-db]
  (let [selection (:opening-hand/bottom-selection app-db)
        mulligan-count (:opening-hand/mulligan-count app-db)]
    (if (not= (count selection) mulligan-count)
      app-db
      (let [game-db (:game/db app-db)
            human-pid (queries/get-human-player-id game-db)
            db-after (reduce (fn [db obj-id]
                               (zones/move-to-bottom-of-library db obj-id human-pid))
                             game-db
                             selection)]
        (-> app-db
            (assoc :game/db db-after
                   :active-screen :game)
            clear-opening-hand-keys)))))


;; === Re-frame event registrations ===

(rf/reg-event-db
  ::mulligan
  (fn [db _]
    (mulligan-handler db)))


(rf/reg-event-db
  ::keep
  (fn [db _]
    (keep-handler db)))


(rf/reg-event-db
  ::toggle-bottom
  (fn [db [_ object-id]]
    (toggle-bottom-selection-handler db object-id)))


(rf/reg-event-db
  ::confirm-bottom
  (fn [db _]
    (confirm-bottom-handler db)))
