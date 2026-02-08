(ns fizzle.events.game
  (:require
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.trigger-registry :as registry]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.engine.zones :as zones]
    [fizzle.events.abilities]
    [fizzle.events.selection :as selection]
    [fizzle.history.core :as history]
    [re-frame.core :as rf]))


(defn make-test-deck
  "Create a test deck as a vector of card-ids.
   Iggy Pop storm deck - mix of rituals, lands, acceleration, card filtering.
   Returns shuffled vector of 60 card-ids."
  []
  (shuffle
    (into []
          (concat
            ;; Rituals (8)
            (repeat 4 :dark-ritual)
            (repeat 4 :cabal-ritual)
            ;; Win condition (4)
            (repeat 4 :brain-freeze)
            ;; Lands (12)
            (repeat 3 :city-of-brass)
            (repeat 4 :gemstone-mine)
            (repeat 3 :polluted-delta)
            (repeat 1 :underground-river)
            (repeat 2 :cephalid-coliseum)
            (repeat 2 :island)
            (repeat 1 :swamp)
            ;; Mana acceleration (8)
            (repeat 4 :lotus-petal)
            (repeat 4 :lions-eye-diamond)
            ;; Card filtering (9)
            (repeat 2 :careful-study)
            (repeat 3 :mental-note)
            (repeat 4 :opt)
            ;; Tutors (4)
            (repeat 4 :intuition)
            ;; Flashback / Card advantage (4)
            (repeat 3 :deep-analysis)
            (repeat 1 :recoup)
            ;; Graveyard recursion (4)
            (repeat 4 :ill-gotten-gains)
            ;; Protection (1)
            (repeat 1 :orims-chant)
            ;; Enchantment interaction (1)
            (repeat 1 :ray-of-revelation)
            ;; Card selection (1)
            (repeat 1 :flash-of-insight)))))


(defn init-game-state
  "Initialize a fresh game state with:
   - Card definitions loaded
   - Player 1 with 20 life, empty mana pool
   - Player 1's 60-card shuffled library
   - 7-card opening hand drawn from library
   - Opponent with 40-card library for mill targets

   Returns app-db map with :game/db key containing Datascript db."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player (start with empty mana pool - lands generate mana now)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/max-hand-size 7}])
    ;; Transact opponent (needed for Brain Freeze mill effect)
    (d/transact! conn [{:player/id :opponent
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 0
                        :player/is-opponent true}])
    ;; Get entity IDs for references
    (let [db @conn
          player-eid (d/q '[:find ?e .
                            :in $ ?pid
                            :where [?e :player/id ?pid]]
                          db :player-1)
          opp-eid (d/q '[:find ?e .
                         :in $ ?pid
                         :where [?e :player/id ?pid]]
                       db :opponent)
          ;; Helper to look up card entity ID
          get-card-eid (fn [db card-id]
                         (d/q '[:find ?e .
                                :in $ ?cid
                                :where [?e :card/id ?cid]]
                              db card-id))
          ;; Create player's 60-card shuffled library
          deck (make-test-deck)]
      ;; Transact player library (60 cards, shuffled, with positions)
      (d/transact! conn
                   (vec (map-indexed
                          (fn [i card-id]
                            {:object/id (random-uuid)
                             :object/card (get-card-eid @conn card-id)
                             :object/zone :library
                             :object/owner player-eid
                             :object/controller player-eid
                             :object/tapped false
                             :object/position i})
                          deck)))
      ;; Create opponent library (40 cards so mill has targets)
      (let [dr-eid (get-card-eid @conn :dark-ritual)]
        (d/transact! conn (vec (for [i (range 40)]
                                 {:object/id (random-uuid) :object/card dr-eid :object/zone :library
                                  :object/owner opp-eid :object/controller opp-eid
                                  :object/tapped false :object/position i}))))
      ;; Game state
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}])
      ;; Register turn-based actions (draw, untap triggers)
      (turn-based/register-turn-based-actions!)
      ;; Draw 7-card opening hand using draw effect
      (let [db-with-lib @conn
            db-after-draw (effects/execute-effect db-with-lib :player-1
                                                  {:effect/type :draw
                                                   :effect/amount 7})]
        (merge
          {:game/db db-after-draw}
          (history/init-history))))))


(rf/reg-event-db
  ::init-game
  (fn [_ _]
    (init-game-state)))


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
      (selection/has-exile-cards-x-cost? mode)
      (let [exile-cost (selection/get-exile-cards-x-cost mode)
            sel (selection/build-exile-cards-selection game-db player-id object-id mode exile-cost)]
        (if sel
          (-> app-db
              (assoc :game/pending-selection sel)
              (dissoc :game/selected-card))
          ;; No valid cards to exile - can't cast (shouldn't happen if can-cast-mode? passed)
          app-db))

      ;; Check for X mana cost (normal cast with X)
      (selection/has-mana-x-cost? mode)
      (let [sel (selection/build-x-mana-selection game-db player-id object-id mode)]
        (-> app-db
            (assoc :game/pending-selection sel)
            (dissoc :game/selected-card)))

      ;; Check for targeting requirements
      (seq targeting-reqs)
      (let [first-req (first targeting-reqs)
            sel (selection/build-cast-time-target-selection game-db player-id object-id mode first-req)]
        (-> app-db
            (assoc :game/pending-selection sel)
            (dissoc :game/selected-card)))

      ;; No X costs or targeting: cast immediately
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


(defn- resolve-stack-item-effects
  "Resolve a stack-item's effects using resolve-effect-target.
   Handles :storm-copies effect by creating N spell copies via triggers/create-spell-copy.
   Pure function: (db, stack-item) -> db"
  [db stack-item]
  (let [controller (:stack-item/controller stack-item)
        source-id (:stack-item/source stack-item)
        effects-list (or (:stack-item/effects stack-item) [])
        stored-targets (:stack-item/targets stack-item)]
    (reduce (fn [d effect]
              (if (= :storm-copies (:effect/type effect))
                ;; Storm: create N copies of the source spell
                (let [copy-count (:effect/count effect 0)]
                  (if (and (pos? copy-count) (queries/get-object d source-id))
                    (loop [d' d
                           remaining copy-count]
                      (if (pos? remaining)
                        (recur (triggers/create-spell-copy d' source-id controller)
                               (dec remaining))
                        d'))
                    d))
                (let [resolved (stack/resolve-effect-target effect source-id controller stored-targets)]
                  (effects/execute-effect d controller resolved source-id))))
            db
            effects-list)))


(defn resolve-top-of-stack
  "Resolve the topmost stack-item in LIFO order.
   Pure function: (db, player-id) -> db

   Returns unchanged db if stack is empty."
  [db player-id]
  (let [top-stack-item (stack/get-top-stack-item db)]
    (cond
      ;; Stack-item with object-ref (spell or storm copy)
      (and top-stack-item (:stack-item/object-ref top-stack-item))
      (let [obj-ref-raw (:stack-item/object-ref top-stack-item)
            obj-ref (if (map? obj-ref-raw) (:db/id obj-ref-raw) obj-ref-raw)
            obj (d/pull db [:object/id] obj-ref)
            object-id (:object/id obj)
            db-resolved (rules/resolve-spell db player-id object-id)]
        (stack/remove-stack-item db-resolved (:db/id top-stack-item)))

      ;; Stack-item without object-ref (activated-ability, trigger types, storm)
      top-stack-item
      (let [db-resolved (resolve-stack-item-effects db top-stack-item)]
        (stack/remove-stack-item db-resolved (:db/id top-stack-item)))

      ;; Empty stack
      :else db)))


;; === Cleanup Step (MTG Rule 514) ===
;; Cleanup is a multi-step process:
;;   1. Discard to hand size (514.1) - interactive, may require player selection
;;   2. Expire grants / "until end of turn" effects (514.2)
;; Grant expiration is NOT an auto-trigger; it's called explicitly after discard.

(defn- build-cleanup-discard-selection
  "Build pending selection state for cleanup discard-to-hand-size."
  [player-id discard-count]
  {:selection/zone :hand
   :selection/select-count discard-count
   :selection/player-id player-id
   :selection/selected #{}
   :selection/type :cleanup-discard})


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


(rf/reg-event-db
  ::resolve-top
  (fn [db _]
    (let [game-db (:game/db db)
          top-stack-item (stack/get-top-stack-item game-db)]
      (cond
        ;; Resolve stack-item with object-ref (spell/storm-copy)
        (and top-stack-item (:stack-item/object-ref top-stack-item))
        (let [obj-ref-raw (:stack-item/object-ref top-stack-item)
              obj-ref (if (map? obj-ref-raw) (:db/id obj-ref-raw) obj-ref-raw)
              obj (d/pull game-db [:object/id] obj-ref)
              object-id (:object/id obj)
              stack-item-eid (:db/id top-stack-item)
              result (selection/resolve-spell-with-selection game-db :player-1 object-id)]
          (if (:pending-selection result)
            (-> db
                (assoc :game/db (:db result))
                (assoc :game/pending-selection (:pending-selection result)))
            (maybe-continue-cleanup
              (assoc db :game/db (stack/remove-stack-item (:db result) stack-item-eid)))))

        ;; Resolve stack-item with selection handling (activated-ability)
        (and top-stack-item (= :activated-ability (:stack-item/type top-stack-item)))
        (let [stack-item-eid (:db/id top-stack-item)
              result (selection/resolve-stack-item-ability-with-selection game-db top-stack-item)]
          (if (:pending-selection result)
            (-> db
                (assoc :game/db (:db result))
                (assoc :game/pending-selection (:pending-selection result)))
            (maybe-continue-cleanup
              (assoc db :game/db (stack/remove-stack-item (:db result) stack-item-eid)))))

        ;; Resolve non-spell stack-item without selection (trigger types, storm, etc.)
        top-stack-item
        (let [stack-item-eid (:db/id top-stack-item)
              db-resolved (resolve-stack-item-effects game-db top-stack-item)]
          (maybe-continue-cleanup
            (assoc db :game/db (stack/remove-stack-item db-resolved stack-item-eid))))

        ;; Empty stack
        :else db))))


;; === Turn Structure ===

(def phases
  "MTG turn phases in order: untap → upkeep → draw → main1 → combat → main2 → end → cleanup"
  [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])


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


(defn start-turn
  "Start a new turn: increment turn counter, set phase to untap,
   reset storm count and land plays to 1, clear mana pool.
   Pure function: (db, player-id) -> db

   Returns db unchanged if the stack is non-empty.
   Note: Untap happens via :untap-step trigger when phase changes to :untap.
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
          ;; REMOVED: (untap-all-permanents player-id) - now via trigger
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
        (assoc db :game/db (start-turn game-db :player-1))))))


;; === Play Land ===

(defn- trigger-type->event-type
  "Map card trigger type to event type for registration.
   E.g., :becomes-tapped in card data becomes :permanent-tapped event."
  [trigger-type]
  (case trigger-type
    :becomes-tapped :permanent-tapped
    :land-entered :land-entered
    ;; Add more mappings as needed
    trigger-type))


(defn register-card-triggers!
  "Register all triggers from a card when it enters the battlefield.

   Arguments:
     object-id     - The permanent's object ID (becomes :trigger/source)
     controller-id - Player ID who controls this permanent
     card          - Card map with optional :card/triggers

   Side effect: Registers triggers in the trigger registry.
   Returns nil."
  [object-id controller-id card]
  (doseq [trigger (:card/triggers card)]
    (let [trigger-type (:trigger/type trigger)
          event-type (trigger-type->event-type trigger-type)
          ;; Use card's filter if specified, otherwise default to :self matching
          ;; City of Brass uses {:event/object-id :self} (fires when THIS taps)
          ;; City of Traitors uses {:exclude-self true} (fires when OTHER lands enter)
          trigger-filter (or (:trigger/filter trigger)
                             {:event/object-id :self})]
      (registry/register-trigger!
        {:trigger/id (random-uuid)
         :trigger/event-type event-type
         :trigger/source object-id
         :trigger/controller controller-id
         :trigger/filter trigger-filter
         :trigger/uses-stack? true
         :trigger/effects (:trigger/effects trigger)
         :trigger/description (:trigger/description trigger)}))))


(defn land-card?
  "Check if an object's card has :land in its types.
   Returns false if object or card not found."
  [db object-id]
  (let [obj (queries/get-object db object-id)]
    (when obj
      ;; get-object pulls card data nested under :object/card
      (let [card (:object/card obj)
            types (:card/types card)]
        ;; types may be a set or vector depending on how it was stored
        (contains? (set types) :land)))))


(defn can-play-land?
  "Check if a player can play a land from their hand.
   Requires:
   - Object is a land card
   - Object is in player's hand
   - Player has land plays remaining
   - Current phase is main1 or main2
   Returns true or false."
  [db player-id object-id]
  (let [game-state (queries/get-game-state db)
        phase (:game/phase game-state)
        player-eid (queries/get-player-eid db player-id)
        land-plays (d/q '[:find ?plays .
                          :in $ ?pid
                          :where [?e :player/id ?pid]
                          [?e :player/land-plays-left ?plays]]
                        db player-id)
        obj (queries/get-object db object-id)
        owner-eid (:db/id (:object/owner obj))]
    (boolean
      (and player-eid
           obj
           (pos? (or land-plays 0))
           (= (:object/zone obj) :hand)
           (= owner-eid player-eid)
           (land-card? db object-id)
           (#{:main1 :main2} phase)))))


(defn play-land
  "Play a land from hand to battlefield.
   Pure function: (db, player-id, object-id) -> db

   Validation:
   - Player must have land-plays-left > 0
   - Object must be in player's hand
   - Card must be a land type
   - Phase must be :main1 or :main2

   After moving to battlefield:
   1. Registers card triggers
   2. Fires ETB effects from :card/etb-effects
   3. Dispatches :land-entered event for triggers like City of Traitors

   Returns unchanged db if any validation fails."
  [db player-id object-id]
  (let [game-state (queries/get-game-state db)
        phase (:game/phase game-state)
        player-eid (queries/get-player-eid db player-id)
        land-plays (d/q '[:find ?plays .
                          :in $ ?pid
                          :where [?e :player/id ?pid]
                          [?e :player/land-plays-left ?plays]]
                        db player-id)
        obj (queries/get-object db object-id)
        ;; get-object pulls refs as {:db/id N} maps, extract the eid
        owner-eid (:db/id (:object/owner obj))]
    (if (and player-eid
             obj
             (pos? (or land-plays 0))
             (= (:object/zone obj) :hand)
             (= owner-eid player-eid)
             (land-card? db object-id)
             (#{:main1 :main2} phase))
      (let [db-after-move (-> db
                              (zones/move-to-zone object-id :battlefield)
                              (d/db-with [[:db/add player-eid :player/land-plays-left (dec land-plays)]]))
            ;; Get card data after move (object still has same card ref)
            obj-after (queries/get-object db-after-move object-id)
            card (:object/card obj-after)
            etb-effects (:card/etb-effects card)
            ;; Register card triggers (e.g., City of Brass :becomes-tapped, City of Traitors :land-entered)
            _ (when (seq (:card/triggers card))
                (register-card-triggers! object-id player-id card))
            ;; Fire ETB effects if any
            db-after-etb (if (seq etb-effects)
                           (reduce (fn [db' effect]
                                     ;; Resolve :self target to actual object-id
                                     (let [resolved-effect (if (= :self (:effect/target effect))
                                                             (assoc effect :effect/target object-id)
                                                             effect)]
                                       (effects/execute-effect db' player-id resolved-effect)))
                                   db-after-move
                                   etb-effects)
                           db-after-move)]
        ;; Dispatch :land-entered event (triggers City of Traitors sacrifice when another land enters)
        (dispatch/dispatch-event db-after-etb (game-events/land-entered-event object-id player-id)))
      db)))


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
