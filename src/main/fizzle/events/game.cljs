(ns fizzle.events.game
  (:require
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.resolution :as engine-resolution]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.engine.zones :as zones]
    [fizzle.events.abilities]
    [fizzle.events.selection]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.events.selection.library]
    [fizzle.events.selection.storm :as sel-storm]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.events.selection.zone-ops]
    [fizzle.history.core :as history]
    [fizzle.storage :as storage]
    [re-frame.core :as rf]))


(defn- deck-to-card-ids
  "Expand a deck main list [{:card/id :count}] into a flat shuffled vector of card-ids."
  [main-deck]
  (shuffle
    (into []
          (mapcat (fn [{:keys [card/id count]}]
                    (repeat count id))
                  main-deck))))


(defn- extract-sculpted-card-ids
  "Extract sculpted card-ids from a shuffled deck.
   Returns [sculpted-card-ids remaining-card-ids].
   For each {card-id count} in must-contain, removes count occurrences from deck.
   Takes min(requested, available) to handle edge cases defensively."
  [shuffled-deck must-contain]
  (if (empty? must-contain)
    [[] shuffled-deck]
    (reduce
      (fn [[sculpted remaining] [card-id cnt]]
        (loop [r remaining
               s sculpted
               n cnt]
          (if (or (zero? n) (empty? r))
            [s r]
            (let [idx (.indexOf r card-id)]
              (if (neg? idx)
                [s r]
                (recur (into (subvec r 0 idx) (subvec r (inc idx)))
                       (conj s card-id)
                       (dec n)))))))
      [[] (vec shuffled-deck)]
      must-contain)))


(defn- get-card-eid
  "Look up a card's entity ID by its :card/id keyword."
  [db card-id]
  (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id))


(defn- get-player-eid
  "Look up a player's entity ID by :player/id."
  [db player-id]
  (d/q '[:find ?e . :in $ ?pid :where [?e :player/id ?pid]] db player-id))


(def ^:private empty-mana-pool
  {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0})


(defn- player-tx
  "Return transaction data for a player entity."
  [player-id name life land-plays opts]
  [(merge {:player/id player-id
           :player/name name
           :player/life life
           :player/mana-pool empty-mana-pool
           :player/storm-count 0
           :player/land-plays-left land-plays}
          opts)])


(defn- objects-tx
  "Return transaction data for game objects in a zone.
   For :hand, uses provided UUIDs. For :library, generates UUIDs and sets position."
  [db card-ids zone owner-eid uuids]
  (vec (map-indexed
         (fn [i [uuid card-id]]
           {:object/id uuid
            :object/card (get-card-eid db card-id)
            :object/zone zone
            :object/owner owner-eid
            :object/controller owner-eid
            :object/tapped false
            :object/position (if (= zone :library) i 0)})
         (map vector uuids card-ids))))


(defn- opponent-library-tx
  "Return transaction data for opponent's library (40 placeholder cards)."
  [db opp-eid]
  (let [dr-eid (get-card-eid db :dark-ritual)]
    (vec (for [i (range 40)]
           {:object/id (random-uuid) :object/card dr-eid :object/zone :library
            :object/owner opp-eid :object/controller opp-eid
            :object/tapped false :object/position i}))))


(defn- opponent-battlefield-tx
  "Return transaction data for opponent's starting battlefield (4 basic lands: 2 Island, 2 Swamp)."
  [db opp-eid]
  (let [island-eid (get-card-eid db :island)
        swamp-eid (get-card-eid db :swamp)]
    [{:object/id (random-uuid) :object/card island-eid :object/zone :battlefield
      :object/owner opp-eid :object/controller opp-eid :object/tapped false}
     {:object/id (random-uuid) :object/card island-eid :object/zone :battlefield
      :object/owner opp-eid :object/controller opp-eid :object/tapped false}
     {:object/id (random-uuid) :object/card swamp-eid :object/zone :battlefield
      :object/owner opp-eid :object/controller opp-eid :object/tapped false}
     {:object/id (random-uuid) :object/card swamp-eid :object/zone :battlefield
      :object/owner opp-eid :object/controller opp-eid :object/tapped false}]))


(defn init-game-state
  "Initialize a fresh game state from config.
   Config keys:
     :main-deck     - vec of {:card/id :count} maps (required)
     :clock-turns   - integer turns until opponent wins (default 4)
     :must-contain  - map of {card-id count} for sculpted opening hand (default {})

   Returns app-db map with :game/db, :active-screen :opening-hand,
   and opening-hand state keys."
  [{:keys [main-deck must-contain] :or {must-contain {}}}]
  (card-spec/validate-cards! cards/all-cards)
  (let [conn (d/create-conn schema)
        _ (d/transact! conn cards/all-cards)
        _ (d/transact! conn (player-tx :player-1 "Player" 20 1 {:player/max-hand-size 7}))
        _ (d/transact! conn (player-tx :opponent "Opponent" 20 0
                                       {:player/is-opponent true
                                        :player/bot-archetype :goldfish}))
        db @conn
        player-eid (get-player-eid db :player-1)
        opp-eid (get-player-eid db :opponent)
        stops (storage/load-stops)
        shuffled-deck (deck-to-card-ids main-deck)
        [sculpted-ids remaining] (extract-sculpted-card-ids shuffled-deck must-contain)
        draw-count (- 7 (count sculpted-ids))
        hand-ids (concat sculpted-ids (take draw-count remaining))
        library-ids (drop draw-count remaining)
        hand-uuids (repeatedly (count hand-ids) random-uuid)
        sculpted-id-set (set (take (count sculpted-ids) hand-uuids))]
    (d/transact! conn (objects-tx @conn hand-ids :hand player-eid hand-uuids))
    (d/transact! conn (objects-tx @conn library-ids :library player-eid
                                  (repeatedly (count library-ids) random-uuid)))
    (d/transact! conn (opponent-library-tx @conn opp-eid))
    (d/transact! conn (opponent-battlefield-tx @conn opp-eid))
    (d/transact! conn [{:game/id :game-1 :game/turn 1 :game/phase :main1
                        :game/active-player player-eid :game/priority player-eid}])
    (d/transact! conn (turn-based/create-turn-based-triggers-tx player-eid))
    (d/transact! conn [[:db/add player-eid :player/stops (:player stops)]
                       [:db/add opp-eid :player/stops (:opponent stops)]])
    (merge {:game/db @conn :active-screen :opening-hand
            :opening-hand/mulligan-count 0 :opening-hand/sculpted-ids sculpted-id-set
            :opening-hand/must-contain (or must-contain {})
            :opening-hand/phase :viewing :game/game-over-dismissed false
            :ui/stack-collapsed false
            :ui/gy-collapsed false
            :ui/history-collapsed false}
           (history/init-history))))


(rf/reg-event-db
  ::init-game
  (fn [_ [_ config]]
    (init-game-state (or config {:main-deck (:deck/main cards/iggy-pop-decklist)
                                 :clock-turns 4}))))


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


(defn- initiate-cast-with-mode
  "Start casting a spell with a specific mode.
   Checks for X costs and targeting before actually casting.
   Returns updated app-db."
  [app-db player-id object-id mode]
  (let [game-db (:game/db app-db)
        obj (queries/get-object game-db object-id)
        card (:object/card obj)
        targeting-reqs (targeting/get-targeting-requirements card)]
    (cond
      ;; Check for exile-cards X cost (flashback with exile)
      (sel-costs/has-exile-cards-x-cost? mode)
      (let [exile-cost (sel-costs/get-exile-cards-x-cost mode)
            sel (sel-costs/build-exile-cards-selection game-db player-id object-id mode exile-cost)]
        (if sel
          (-> app-db
              (assoc :game/pending-selection sel)
              (dissoc :game/selected-card))
          ;; No valid cards to exile - can't cast (shouldn't happen if can-cast-mode? passed)
          app-db))

      ;; Check for X mana cost (normal cast with X)
      (sel-costs/has-mana-x-cost? mode)
      (let [sel (sel-costs/build-x-mana-selection game-db player-id object-id mode)]
        (-> app-db
            (assoc :game/pending-selection sel)
            (dissoc :game/selected-card)))

      ;; Check for targeting requirements
      (seq targeting-reqs)
      (let [first-req (first targeting-reqs)
            sel (sel-targeting/build-cast-time-target-selection game-db player-id object-id mode first-req)]
        (-> app-db
            (assoc :game/pending-selection sel)
            (dissoc :game/selected-card)))

      ;; Check for generic mana cost requiring manual allocation
      (sel-costs/has-generic-mana-cost? (:mode/mana-cost mode))
      (let [sel (sel-costs/build-mana-allocation-selection game-db player-id object-id mode (:mode/mana-cost mode))]
        (if sel
          (-> app-db
              (assoc :game/pending-selection sel)
              (dissoc :game/selected-card))
          ;; nil means 0 generic (defensive fallback)
          (-> app-db
              (assoc :game/db (rules/cast-spell-mode game-db player-id object-id mode))
              (dissoc :game/selected-card))))

      ;; No X costs, targeting, or generic: cast immediately
      :else
      (-> app-db
          (assoc :game/db (rules/cast-spell-mode game-db player-id object-id mode))
          (dissoc :game/selected-card)))))


(defn cast-spell-handler
  "Handle cast-spell event: check casting modes and either auto-cast,
   show mode selector, or initiate casting with X cost/targeting checks.
   Pure function: (app-db) -> app-db"
  [app-db]
  (let [game-db (:game/db app-db)
        selected (:game/selected-card app-db)]
    (if (and selected (rules/can-cast? game-db :player-1 selected))
      (let [modes (rules/get-casting-modes game-db :player-1 selected)
            castable-modes (filterv #(rules/can-cast-mode? game-db :player-1 selected %) modes)]
        (cond
          ;; No castable modes - shouldn't happen if can-cast? passed
          (empty? castable-modes)
          app-db

          ;; Multiple modes: show selector first (X costs/targeting checked after mode selection)
          (> (count castable-modes) 1)
          (assoc app-db :game/pending-mode-selection
                 {:object-id selected
                  :modes castable-modes})

          ;; Single mode: check for X costs, targeting, then cast
          :else
          (initiate-cast-with-mode app-db :player-1 selected (first castable-modes))))
      app-db)))


(rf/reg-event-db
  ::cast-spell
  (fn [db _]
    (cast-spell-handler db)))


(defn- get-source-id
  "Get the source object-id for a stack-item (for selection building)."
  [game-db stack-item]
  (or (when-let [obj-ref-raw (:stack-item/object-ref stack-item)]
        (let [obj-ref (if (map? obj-ref-raw) (:db/id obj-ref-raw) obj-ref-raw)
              obj (d/pull game-db [:object/id] obj-ref)]
          (:object/id obj)))
      (:stack-item/source stack-item)))


(defn- build-selection-from-result
  "Build pending-selection from a multimethod result that has :needs-selection.
   Handles stored-player for targeted abilities (Cephalid Coliseum) and
   adjusts selection fields for abilities vs spells."
  [game-db player-id stack-item result]
  (let [stack-item-eid (:db/id stack-item)
        source-id (get-source-id game-db stack-item)
        stored-player (:stored-player result)
        sel-effect (:needs-selection result)
        use-stored-player (and stored-player
                               (= :player (:effect/selection sel-effect)))
        sel-player-id (if use-stored-player stored-player player-id)
        sel (sel-core/build-selection-for-effect
              (:db result) sel-player-id source-id
              sel-effect
              (vec (:remaining-effects result)))
        sel (if (= :activated-ability (:stack-item/type stack-item))
              (-> sel
                  (dissoc :selection/spell-id)
                  (assoc :selection/stack-item-eid stack-item-eid
                         :selection/source-type :stack-item))
              sel)]
    {:db (:db result) :pending-selection sel}))


(defn resolve-one-item
  "Resolve the topmost stack-item. Returns {:db} or {:db :pending-selection}."
  [game-db player-id]
  (let [top (stack/get-top-stack-item game-db)]
    (if-not top
      {:db game-db}
      (let [result (engine-resolution/resolve-stack-item game-db player-id top)]
        (cond
          (:needs-storm-split result)
          (if-let [sel (sel-storm/build-storm-split-selection game-db player-id top)]
            {:db game-db :pending-selection sel}
            {:db (stack/remove-stack-item game-db (:db/id top))})

          (:needs-selection result)
          (build-selection-from-result game-db player-id top result)

          :else
          {:db (stack/remove-stack-item (:db result) (:db/id top))})))))


;; === Cleanup Step (MTG Rule 514) ===
;; Cleanup is a multi-step process:
;;   1. Discard to hand size (514.1) - interactive, may require player selection
;;   2. Expire grants / "until end of turn" effects (514.2)
;; Grant expiration is NOT an auto-trigger; it's called explicitly after discard.

(defn- build-cleanup-discard-selection
  "Build pending selection state for cleanup discard-to-hand-size.
   Uses unified :discard type with :selection/cleanup? flag."
  [player-id discard-count]
  {:selection/zone :hand
   :selection/card-source :hand
   :selection/select-count discard-count
   :selection/player-id player-id
   :selection/selected #{}
   :selection/type :discard
   :selection/cleanup? true
   :selection/validation :exact
   :selection/auto-confirm? false})


(defn begin-cleanup
  "Begin the cleanup step on a db already at :cleanup phase.
   Checks hand size against max and either:
   - Returns {:db db'} if no discard needed (grants already expired)
   - Returns {:db db' :pending-selection selection} if discard needed

   Caller is responsible for advancing phase to :cleanup first.
   Grant expiration is only done if no discard is needed;
   otherwise it happens after discard confirmation.

   Pure function: (db, player-id) -> {:db db, :pending-selection ...}"
  [db player-id]
  (let [hand (queries/get-hand db player-id)
        hand-count (count hand)
        max-hand-size (queries/get-max-hand-size db player-id)
        discard-count (- hand-count max-hand-size)]
    (if (pos? discard-count)
      ;; Need to discard - don't expire grants yet (Rule 514.1 before 514.2)
      {:db db
       :pending-selection (build-cleanup-discard-selection player-id discard-count)}
      ;; No discard needed - expire grants immediately (Rule 514.2)
      (let [game-state (queries/get-game-state db)
            current-turn (:game/turn game-state)]
        {:db (grants/expire-grants db current-turn :cleanup)}))))


(defn complete-cleanup-discard
  "Complete the cleanup discard step: move selected cards to graveyard,
   then expire grants.
   Pure function: (db, player-id, selected-ids) -> {:db db}"
  [db _player-id selected-ids]
  (let [;; Move selected cards to graveyard
        db-after-discard (reduce (fn [d obj-id]
                                   (zones/move-to-zone d obj-id :graveyard))
                                 db
                                 selected-ids)
        ;; Now expire grants (Rule 514.2 - after discard)
        game-state (queries/get-game-state db-after-discard)
        current-turn (:game/turn game-state)]
    {:db (grants/expire-grants db-after-discard current-turn :cleanup)}))


(defn maybe-continue-cleanup
  "After stack resolution during cleanup, check if cleanup should restart.
   If stack is now empty, phase is :cleanup, and no pending selection,
   re-runs begin-cleanup to recheck hand size and re-expire grants.
   Pure function: (app-db) -> app-db"
  [app-db]
  (let [game-db (:game/db app-db)]
    (if (and (= :cleanup (:game/phase (queries/get-game-state game-db)))
             (queries/stack-empty? game-db)
             (not (:game/pending-selection app-db)))
      (let [result (begin-cleanup game-db :player-1)]
        (if (:pending-selection result)
          (-> app-db
              (assoc :game/db (:db result))
              (assoc :game/pending-selection (:pending-selection result)))
          (assoc app-db :game/db (:db result))))
      app-db)))


(defn- apply-history-entries
  "Apply a sequence of history entries to app-db.
   First entry auto-forks if not at history tip; remaining entries append normally."
  [app-db entries]
  (if (empty? entries)
    app-db
    (let [first-entry (first entries)
          db-with-first (if (or (= -1 (:history/position app-db))
                                (history/at-tip? app-db))
                          (history/append-entry app-db first-entry)
                          (history/auto-fork app-db first-entry))]
      (reduce history/append-entry db-with-first (rest entries)))))


(rf/reg-event-db
  ::resolve-top
  (fn [db _]
    (let [result (resolve-one-item (:game/db db) :player-1)]
      (if (:pending-selection result)
        (-> db
            (assoc :game/db (:db result))
            (assoc :game/pending-selection (:pending-selection result)))
        (maybe-continue-cleanup
          (assoc db :game/db (:db result)))))))


(rf/reg-event-fx
  ::resolve-all
  (fn [{:keys [db]} [_ initial-ids]]
    (let [game-db (:game/db db)
          initial-ids (or initial-ids
                          (set (map :db/id (queries/get-all-stack-items game-db))))]
      (if (empty? initial-ids)
        {:db db}
        (let [top (stack/get-top-stack-item game-db)]
          (if (and top (contains? initial-ids (:db/id top)))
            (let [result (resolve-one-item game-db :player-1)]
              (if (:pending-selection result)
                {:db (-> db
                         (assoc :game/db (:db result))
                         (assoc :game/pending-selection (:pending-selection result)))}
                {:db (assoc db :game/db (:db result))
                 :fx [[:dispatch [::resolve-all initial-ids]]]}))
            ;; Stack empty or new item on top — done, check cleanup
            {:db (maybe-continue-cleanup (assoc db :game/db game-db))}))))))


;; === Turn Structure ===

(def phases
  "MTG turn phases in order. Delegates to engine/rules."
  rules/phases)


(defn next-phase
  "Get the next phase in the turn sequence.
   Returns the same phase if at cleanup (requires explicit start-turn for new turn)."
  [current-phase]
  (let [idx (.indexOf phases current-phase)]
    (if (or (neg? idx) (= idx (dec (count phases))))
      current-phase  ; Stay at cleanup or unknown phase
      (nth phases (inc idx)))))


(defn advance-phase
  "Advance to the next phase, clear mana pool, and dispatch phase-entered event.
   Pure function: (db, player-id) -> db

   Returns db unchanged if the stack is non-empty.
   At cleanup phase, stays at cleanup (user must call start-turn for new turn).
   Dispatches :phase-entered event to fire turn-based actions (draw, etc.)."
  [db player-id]
  (if-not (queries/stack-empty? db)
    db
    (let [game-state (queries/get-game-state db)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          current-phase (:game/phase game-state)
          new-phase (next-phase current-phase)
          turn (:game/turn game-state)]
      (-> db
          (mana/empty-pool player-id)
          (d/db-with [[:db/add game-eid :game/phase new-phase]])
          ;; Dispatch phase-entered event to fire turn-based actions
          (dispatch/dispatch-event (game-events/phase-entered-event new-phase turn player-id))))))


(defn untap-all-permanents
  "Untap all permanents controlled by a player on the battlefield.
   Pure function: (db, player-id) -> db"
  [db player-id]
  (let [player-eid (queries/get-player-eid db player-id)
        ;; Find all tapped permanents controlled by player on battlefield
        tapped-permanents (d/q '[:find ?e
                                 :in $ ?controller
                                 :where [?e :object/controller ?controller]
                                 [?e :object/zone :battlefield]
                                 [?e :object/tapped true]]
                               db player-eid)]
    (if (seq tapped-permanents)
      (d/db-with db (mapv (fn [[eid]] [:db/add eid :object/tapped false])
                          tapped-permanents))
      db)))


(defn opponent-draw
  "Draw a card for the opponent (goldfish turn).
   Uses the draw effect which naturally triggers loss condition on empty library.
   No-op if no opponent exists.
   Pure function: (db, player-id) -> db"
  [db player-id]
  (if-let [opponent-id (queries/get-opponent-id db player-id)]
    (effects/execute-effect db opponent-id
                            {:effect/type :draw :effect/amount 1})
    db))


(defn start-turn
  "Start a new turn: increment turn counter, set phase to untap,
   reset storm count and land plays to 1, clear mana pool.
   Pure function: (db, player-id) -> db

   Returns db unchanged if the stack is non-empty.
   Note: Opponent draw is handled by the ::start-turn event handler
   (not here) so it can create a separate history entry.
   Untap happens via :untap-step trigger when phase changes to :untap.
   This dispatches the :phase-entered event to fire the turn-based action."
  [db player-id]
  (if-not (queries/stack-empty? db)
    db
    (let [game-state (queries/get-game-state db)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          player-eid (queries/get-player-eid db player-id)
          current-turn (or (:game/turn game-state) 0)
          new-turn (inc current-turn)]
      (-> db
          (mana/empty-pool player-id)
          (d/db-with [[:db/add game-eid :game/turn new-turn]
                      [:db/add game-eid :game/phase :untap]
                      [:db/add player-eid :player/storm-count 0]
                      [:db/add player-eid :player/land-plays-left 1]])
          ;; Dispatch untap phase event to fire turn-based actions
          (dispatch/dispatch-event (game-events/phase-entered-event :untap new-turn player-id))))))


(rf/reg-event-db
  ::advance-phase
  (fn [db _]
    (let [game-db (:game/db db)]
      ;; Block advance if pending selection exists (e.g., cleanup discard in progress)
      (if (:game/pending-selection db)
        db
        (let [game-state (queries/get-game-state game-db)
              current-phase (:game/phase game-state)
              new-phase (next-phase current-phase)]
          (if (= new-phase :cleanup)
            ;; Special cleanup handling: advance phase first, then begin cleanup
            (let [advanced-db (advance-phase game-db :player-1)
                  result (begin-cleanup advanced-db :player-1)]
              (if (:pending-selection result)
                (-> db
                    (assoc :game/db (:db result))
                    (assoc :game/pending-selection (:pending-selection result)))
                (assoc db :game/db (:db result))))
            ;; Normal phase advancement
            (assoc db :game/db (advance-phase game-db :player-1))))))))


(rf/reg-event-db
  ::start-turn
  (fn [db _]
    (let [game-db (:game/db db)]
      ;; Block start-turn if pending selection exists (e.g., cleanup discard)
      (if (:game/pending-selection db)
        db
        (let [;; Step 1: Opponent draw (before player's turn)
              db-after-draw (opponent-draw game-db :player-1)
              drew? (not (identical? game-db db-after-draw))
              ;; Step 2: Turn mechanics (on post-draw state)
              db-after-turn (start-turn db-after-draw :player-1)
              ;; Step 3: Build history entries
              game-state (queries/get-game-state db-after-turn)
              turn (:game/turn game-state)
              entries (cond-> []
                        drew? (conj (history/make-entry db-after-draw ::start-turn
                                                        "Opponent draws" turn))
                        true (conj (history/make-entry db-after-turn ::start-turn
                                                       (str "Start Turn " turn)
                                                       turn)))]
          ;; Step 4: Apply history entries and set game-db
          (-> (apply-history-entries db entries)
              (assoc :game/db db-after-turn)))))))


;; === Priority System ===

(defn- advance-with-stops
  "Advance phases until a stop is hit or a turn boundary is crossed.
   Handles cleanup (begin-cleanup, opponent-draw, start-turn) automatically.
   After crossing a turn boundary, continues advancing until the first stop.
   If no stops are set, stops at the turn boundary (untap of new turn).
   When ignore-stops? is true (F6 mode), skips player phase stop checks.
   Returns {:app-db app-db'} with game-db at the stopped phase.
   Pure function: (app-db, ignore-stops?) -> {:app-db app-db'}"
  [app-db ignore-stops?]
  (let [game-db (:game/db app-db)
        player-eid (queries/get-player-eid game-db :player-1)]
    (loop [gdb game-db]
      (let [game-state (queries/get-game-state gdb)
            current-phase (:game/phase game-state)
            nxt (next-phase current-phase)]
        (if (= nxt :cleanup)
          ;; Advancing to cleanup: advance phase, begin cleanup, then cross turn boundary
          (let [advanced-db (advance-phase gdb :player-1)
                cleanup-result (begin-cleanup advanced-db :player-1)]
            (if (:pending-selection cleanup-result)
              ;; Cleanup needs discard — pause with pending-selection
              {:app-db (-> app-db
                           (assoc :game/db (:db cleanup-result))
                           (assoc :game/pending-selection (:pending-selection cleanup-result)))}
              ;; No discard needed — cross turn boundary, then stop
              (let [db-after-cleanup (:db cleanup-result)
                    db-after-draw (opponent-draw db-after-cleanup :player-1)
                    db-after-turn (start-turn db-after-draw :player-1)
                    new-player-eid (queries/get-player-eid db-after-turn :player-1)
                    has-stops? (seq (:player/stops (d/pull db-after-turn [:player/stops] new-player-eid)))]
                (if (and has-stops? (not ignore-stops?))
                  ;; Has stops and not F6 — continue advancing to first stop in new turn
                  (recur db-after-turn)
                  ;; No stops or F6 — stop at turn boundary
                  {:app-db (assoc app-db :game/db db-after-turn)}))))
          ;; Normal phase advance
          (let [advanced-db (advance-phase gdb :player-1)]
            ;; Check if new phase triggers anything on the stack
            (if (not (queries/stack-empty? advanced-db))
              ;; Stack triggered — stop and give priority
              {:app-db (assoc app-db :game/db advanced-db)}
              ;; Check stop (skipped in F6 mode)
              (if (and (not ignore-stops?)
                       (priority/check-stop advanced-db player-eid nxt))
                ;; Stop hit — pause here
                {:app-db (assoc app-db :game/db advanced-db)}
                ;; No stop or F6 — continue advancing
                (recur advanced-db)))))))))


(defn yield-impl
  "Core priority passing logic. Pure function on app-db.
   Returns map with:
     :app-db          — updated app-db (always present)
     :continue-yield? — true if ::yield should re-dispatch (more stack items)

   Auto-mode behavior:
     :resolving — Both players auto-pass each cycle. Resolves stack items.
                  Breaks on: stack empty, selection needed.
                  Clears auto-mode when stack empties.
     :f6        — Both players auto-pass. Advances phases ignoring player stops.
                  Stops at turn boundary. Clears auto-mode when done.

   Normal flow:
   1. Add current priority holder to passed set
   2. If opponent is bot, auto-pass for bot
   3. If both passed:
      a. Reset passes
      b. Stack non-empty: resolve one item
      c. Stack empty: advance phases with stops
   4. If not both passed: transfer priority"
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        holder-eid (priority/get-priority-holder-eid game-db)
        ;; Step 1: current player passes
        gdb (priority/yield-priority game-db holder-eid)
        ;; Step 2: auto-pass opponent (bot, auto-mode, or no opponent)
        opponent-player-id (queries/get-opponent-id gdb :player-1)
        gdb (if opponent-player-id
              (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
                (if auto-mode
                  ;; In auto-mode, opponent always auto-passes
                  (priority/yield-priority gdb opp-eid)
                  ;; Normal mode: check if opponent is a bot
                  (let [archetype (bot/get-bot-archetype gdb opponent-player-id)]
                    (if (and archetype
                             (= :pass (bot/bot-priority-decision archetype {})))
                      (priority/yield-priority gdb opp-eid)
                      gdb))))
              gdb)
        ;; No opponent means single-player: one pass suffices
        all-passed (or (not opponent-player-id)
                       (priority/both-passed? gdb))]
    (if all-passed
      ;; Step 3: Both passed
      (let [gdb (priority/reset-passes gdb)]
        (if (not (queries/stack-empty? gdb))
          ;; 3b: Stack non-empty — resolve one item
          (let [result (resolve-one-item gdb :player-1)]
            (if (:pending-selection result)
              ;; Selection needed — clear auto-mode, return selection
              {:app-db (-> app-db
                           (assoc :game/db (priority/clear-auto-mode (:db result)))
                           (assoc :game/pending-selection (:pending-selection result)))}
              ;; Resolved one item — check if stack has more
              (let [resolved-db (:db result)]
                (if (not (queries/stack-empty? resolved-db))
                  ;; More items on stack — signal to continue
                  {:app-db (assoc app-db :game/db resolved-db)
                   :continue-yield? true}
                  ;; Stack now empty — clear auto-mode if :resolving
                  (let [resolved-db (if (= :resolving auto-mode)
                                      (priority/clear-auto-mode resolved-db)
                                      resolved-db)]
                    {:app-db (maybe-continue-cleanup
                               (assoc app-db :game/db resolved-db))})))))
          ;; 3c: Stack empty — advance phases with stops
          (let [f6? (= :f6 auto-mode)
                result (advance-with-stops (assoc app-db :game/db gdb) f6?)]
            ;; Clear auto-mode after F6 advances
            (if f6?
              (update result :app-db
                      (fn [adb] (update adb :game/db priority/clear-auto-mode)))
              result))))
      ;; Step 4: Not both passed — transfer priority
      {:app-db (assoc app-db :game/db (priority/transfer-priority gdb holder-eid))})))


(rf/reg-event-fx
  ::yield
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (let [result (yield-impl db)]
        (if (:continue-yield? result)
          {:db (:app-db result)
           :fx [[:dispatch [::yield]]]}
          {:db (:app-db result)})))))


(defn- yield-loop
  "Repeatedly call yield-impl until it stops returning :continue-yield?.
   Returns the final app-db. Safety limit of 100 iterations."
  [app-db]
  (loop [adb app-db
         n 100]
    (if (zero? n)
      adb
      (let [result (yield-impl adb)]
        (if (:continue-yield? result)
          (recur (:app-db result) (dec n))
          (:app-db result))))))


(rf/reg-event-db
  ::yield-all
  (fn [db _]
    (if (:game/pending-selection db)
      db
      (let [game-db (:game/db db)
            mode (if (queries/stack-empty? game-db) :f6 :resolving)
            app-db (assoc db :game/db (priority/set-auto-mode game-db mode))]
        (yield-loop app-db)))))


(rf/reg-event-fx
  ::cast-and-yield
  (fn [{:keys [db]} _]
    (let [new-db (cast-spell-handler db)]
      (if (or (:game/pending-selection new-db)
              (:game/pending-mode-selection new-db)
              (not (seq (queries/get-all-stack-items (:game/db new-db)))))
        {:db new-db}
        ;; Cast succeeded, stack has items — set :resolving and yield
        (let [app-db (update new-db :game/db priority/set-auto-mode :resolving)
              result (yield-impl app-db)]
          (if (:continue-yield? result)
            {:db (:app-db result)
             :fx [[:dispatch [::yield]]]}
            {:db (:app-db result)}))))))


;; === Play Land ===

(def land-card?
  "Check if an object's card has :land in its types. Delegates to engine/rules."
  rules/land-card?)


(def can-play-land?
  "Check if a player can play a land from their hand. Delegates to engine/rules."
  rules/can-play-land?)


(defn play-land
  "Play a land from hand to battlefield.
   Pure function: (db, player-id, object-id) -> db

   Validates via rules/can-play-land?, then:
   1. Moves land to battlefield and decrements land plays
   2. Registers card triggers
   3. Fires ETB effects from :card/etb-effects
   4. Dispatches :land-entered event for triggers like City of Traitors

   Returns unchanged db if validation fails."
  [db player-id object-id]
  (if-not (rules/can-play-land? db player-id object-id)
    db
    (let [player-eid (queries/get-player-eid db player-id)
          land-plays (d/q '[:find ?plays .
                            :in $ ?pid
                            :where [?e :player/id ?pid]
                            [?e :player/land-plays-left ?plays]]
                          db player-id)
          db-after-move (-> db
                            (zones/move-to-zone object-id :battlefield)
                            (d/db-with [[:db/add player-eid :player/land-plays-left (dec land-plays)]]))
          obj-after (queries/get-object db-after-move object-id)
          card (:object/card obj-after)
          etb-effects (:card/etb-effects card)
          db-after-triggers (if (seq (:card/triggers card))
                              (let [obj-eid (d/q '[:find ?e .
                                                   :in $ ?oid
                                                   :where [?e :object/id ?oid]]
                                                 db-after-move object-id)
                                    tx (trigger-db/create-triggers-for-card-tx
                                         db-after-move obj-eid player-eid (:card/triggers card))]
                                (d/db-with db-after-move tx))
                              db-after-move)
          db-after-etb (if (seq etb-effects)
                         (reduce (fn [db' effect]
                                   (let [resolved-effect (if (= :self (:effect/target effect))
                                                           (assoc effect :effect/target object-id)
                                                           effect)]
                                     (effects/execute-effect db' player-id resolved-effect)))
                                 db-after-triggers
                                 etb-effects)
                         db-after-triggers)]
      (dispatch/dispatch-event db-after-etb (game-events/land-entered-event object-id player-id)))))


(rf/reg-event-db
  ::play-land
  (fn [db [_ object-id]]
    (let [game-db (:game/db db)]
      (assoc db :game/db (play-land game-db :player-1 object-id)))))


;; === Tap Permanent ===

(defn tap-permanent
  "Tap a permanent on the battlefield.
   Pure function: (db, object-id) -> db

   Sets :object/tapped to true for the given object.
   Returns unchanged db if object doesn't exist."
  [db object-id]
  (let [obj (queries/get-object db object-id)]
    (if obj
      (let [obj-eid (d/q '[:find ?e .
                           :in $ ?oid
                           :where [?e :object/id ?oid]]
                         db object-id)]
        (d/db-with db [[:db/add obj-eid :object/tapped true]]))
      db)))


(rf/reg-event-db
  ::tap-permanent
  (fn [db [_ object-id]]
    (let [game-db (:game/db db)]
      (assoc db :game/db (tap-permanent game-db object-id)))))


;; === Mode Selection System ===
;; For spells with multiple valid casting modes from the same zone

(defn select-casting-mode-handler
  "Handle select-casting-mode event: cast spell with the chosen mode.
   Clears pending mode selection and initiates cast with X cost/targeting checks.
   Pure function: (app-db, mode) -> app-db"
  [app-db mode]
  (let [pending (:game/pending-mode-selection app-db)
        object-id (:object-id pending)]
    (if (and pending object-id mode)
      ;; Use initiate-cast-with-mode to handle X costs, targeting, and casting
      (-> (initiate-cast-with-mode app-db :player-1 object-id mode)
          (dissoc :game/pending-mode-selection))
      app-db)))


(rf/reg-event-db
  ::select-casting-mode
  (fn [db [_ mode]]
    (select-casting-mode-handler db mode)))


(defn cancel-mode-selection-handler
  "Handle cancel-mode-selection event: clear pending mode selection state.
   Pure function: (app-db) -> app-db"
  [app-db]
  (dissoc app-db :game/pending-mode-selection))


(rf/reg-event-db
  ::cancel-mode-selection
  (fn [db _]
    (cancel-mode-selection-handler db)))
