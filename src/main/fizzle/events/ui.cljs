(ns fizzle.events.ui
  "UI toggle and display events: screen switching, panel collapsing,
   graveyard sort, card selection, phase stops."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.db.storage :as storage]
    [fizzle.engine.priority :as priority]
    [re-frame.core :as rf]))


(defn set-active-screen-handler
  "Set the active screen in app-db.
   Pure function: (app-db, screen) -> app-db"
  [db screen]
  (assoc db :active-screen screen))


(rf/reg-event-db
  ::toggle-graveyard-sort
  (fn [db _]
    (let [current (get db :graveyard/sort-mode :entry)]
      (assoc db :graveyard/sort-mode (if (= current :entry) :sorted :entry)))))


(rf/reg-event-db
  ::set-active-screen
  (fn [db [_ screen]]
    (set-active-screen-handler db screen)))


(rf/reg-event-db
  ::dismiss-game-over
  (fn [db _]
    (assoc db :game/game-over-dismissed true)))


(rf/reg-event-db
  ::toggle-stack-collapsed
  (fn [db _]
    (update db :ui/stack-collapsed not)))


(rf/reg-event-db
  ::toggle-gy-collapsed
  (fn [db _]
    (update db :ui/gy-collapsed not)))


(rf/reg-event-db
  ::toggle-history-collapsed
  (fn [db _]
    (update db :ui/history-collapsed not)))


(rf/reg-event-db
  ::select-card
  (fn [db [_ object-id]]
    (let [currently-selected (:game/selected-card db)]
      (assoc db :game/selected-card
             (when (not= currently-selected object-id) object-id)))))


(rf/reg-event-db
  ::toggle-stop
  (fn [db [_ role phase]]
    (let [game-db (:game/db db)
          human-pid (queries/get-human-player-id game-db)
          player-id (if (= role :opponent)
                      (queries/get-opponent-id game-db human-pid)
                      human-pid)
          storage-key (if (= role :opponent) :opponent :player)
          player-eid (queries/get-player-eid game-db player-id)
          current-stops (or (:player/stops (d/pull game-db [:player/stops] player-eid)) #{})
          new-stops (if (contains? current-stops phase)
                      (disj current-stops phase)
                      (conj current-stops phase))
          all-stops (storage/load-stops)
          updated-stops (assoc all-stops storage-key new-stops)]
      (storage/save-stops! updated-stops)
      (assoc db :game/db (priority/set-player-stops game-db player-eid new-stops)))))
