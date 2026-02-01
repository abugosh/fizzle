(ns fizzle.events.game
  (:require
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as state-based]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.trigger-registry :as registry]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.engine.zones :as zones]
    [re-frame.core :as rf]))


;; Forward declarations for selection-aware spell resolution
(declare resolve-spell-with-selection)
(declare resolve-activated-ability-with-selection)
(declare find-selection-effect-index)
(declare build-tutor-selection)


(defn make-test-deck
  "Create a test deck as a vector of card-ids.
   Mix of storm staples: rituals, lands, acceleration, card filtering.
   Returns shuffled vector of 60 card-ids."
  []
  (shuffle
    (into []
          (concat
            (repeat 8 :dark-ritual)
            (repeat 8 :cabal-ritual)
            (repeat 4 :brain-freeze)
            (repeat 4 :city-of-brass)
            (repeat 4 :gemstone-mine)
            (repeat 4 :polluted-delta)
            (repeat 4 :island)
            (repeat 4 :swamp)
            (repeat 4 :lotus-petal)
            (repeat 4 :lions-eye-diamond)
            (repeat 4 :careful-study)
            (repeat 4 :mental-note)
            (repeat 4 :merchant-scroll)))))


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
                        :player/land-plays-left 1}])
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
        {:game/db db-after-draw}))))


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


(rf/reg-event-db
  ::cast-spell
  (fn [db _]
    (let [game-db (:game/db db)
          selected (:game/selected-card db)]
      (if (and selected (rules/can-cast? game-db :player-1 selected))
        (-> db
            (assoc :game/db (rules/cast-spell game-db :player-1 selected))
            (dissoc :game/selected-card))
        db))))


(defn resolve-top-trigger
  "Resolve the top trigger on the stack.
   Pure function: (db) -> db

   Returns unchanged db if no triggers on stack."
  [db]
  (let [stack-triggers (queries/get-stack-items db)]
    (if (seq stack-triggers)
      (let [top-trigger (first stack-triggers)]
        (-> db
            (triggers/resolve-trigger top-trigger)
            (triggers/remove-trigger (:trigger/id top-trigger))))
      db)))


(defn resolve-top-of-stack
  "Resolve the topmost item on the stack (trigger or spell) in LIFO order.
   Pure function: (db, player-id) -> db

   Compares stack-order of triggers and position of spells to find the
   most recently added item and resolves it.

   Returns unchanged db if stack is empty."
  [db player-id]
  (let [;; Get triggers with their stack order
        stack-triggers (queries/get-stack-items db)
        top-trigger (first stack-triggers)
        trigger-order (when top-trigger (:trigger/stack-order top-trigger))

        ;; Get spells with their position (which shares counter space with trigger stack-order)
        stack-objects (->> (queries/get-objects-in-zone db player-id :stack)
                           (sort-by :object/position >))
        top-spell (first stack-objects)
        spell-order (when top-spell (:object/position top-spell))]
    (cond
      ;; Both trigger and spell - resolve whichever has higher order (LIFO)
      (and trigger-order spell-order)
      (if (> trigger-order spell-order)
        (-> db
            (triggers/resolve-trigger top-trigger)
            (triggers/remove-trigger (:trigger/id top-trigger)))
        (rules/resolve-spell db player-id (:object/id top-spell)))

      ;; Only trigger
      trigger-order
      (-> db
          (triggers/resolve-trigger top-trigger)
          (triggers/remove-trigger (:trigger/id top-trigger)))

      ;; Only spell
      spell-order
      (rules/resolve-spell db player-id (:object/id top-spell))

      ;; Empty stack
      :else db)))


(rf/reg-event-db
  ::resolve-top
  (fn [db _]
    (let [game-db (:game/db db)
          ;; Check if top item is a spell that needs selection handling
          stack-objects (->> (queries/get-objects-in-zone game-db :player-1 :stack)
                             (sort-by :object/position >))
          top-spell (first stack-objects)
          stack-triggers (queries/get-stack-items game-db)
          top-trigger (first stack-triggers)
          trigger-order (when top-trigger (:trigger/stack-order top-trigger))
          spell-order (when top-spell (:object/position top-spell))
          ;; Determine what to resolve based on stack order
          resolve-spell? (and spell-order
                              (or (not trigger-order)
                                  (> spell-order trigger-order)))
          resolve-trigger? (and trigger-order
                                (or (not spell-order)
                                    (> trigger-order spell-order)))]
      (cond
        ;; Resolve spell with selection handling
        resolve-spell?
        (let [result (resolve-spell-with-selection game-db :player-1 (:object/id top-spell))]
          (if (:pending-selection result)
            (-> db
                (assoc :game/db (:db result))
                (assoc :game/pending-selection (:pending-selection result)))
            (assoc db :game/db (:db result))))

        ;; Resolve activated ability trigger with selection handling
        (and resolve-trigger? (= :activated-ability (:trigger/type top-trigger)))
        (let [result (resolve-activated-ability-with-selection game-db top-trigger)]
          (if (:pending-selection result)
            (-> db
                (assoc :game/db (:db result))
                (assoc :game/pending-selection (:pending-selection result)))
            (assoc db :game/db (:db result))))

        ;; Resolve other triggers normally
        resolve-trigger?
        (assoc db :game/db (resolve-top-of-stack game-db :player-1))

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

   At cleanup phase, stays at cleanup (user must call start-turn for new turn).
   Dispatches :phase-entered event to fire turn-based actions (draw, etc.)."
  [db player-id]
  (let [game-state (queries/get-game-state db)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        current-phase (:game/phase game-state)
        new-phase (next-phase current-phase)
        turn (:game/turn game-state)]
    (-> db
        (mana/empty-pool player-id)
        (d/db-with [[:db/add game-eid :game/phase new-phase]])
        ;; Dispatch phase-entered event to fire turn-based actions
        (dispatch/dispatch-event (game-events/phase-entered-event new-phase turn player-id)))))


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

   Note: Untap happens via :untap-step trigger when phase changes to :untap.
   This dispatches the :phase-entered event to fire the turn-based action."
  [db player-id]
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
        (dispatch/dispatch-event (game-events/phase-entered-event :untap new-turn player-id)))))


(rf/reg-event-db
  ::advance-phase
  (fn [db _]
    (let [game-db (:game/db db)]
      (assoc db :game/db (advance-phase game-db :player-1)))))


(rf/reg-event-db
  ::start-turn
  (fn [db _]
    (let [game-db (:game/db db)]
      (assoc db :game/db (start-turn game-db :player-1)))))


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


;; === Activate Mana Ability ===

(defn activate-mana-ability
  "Activate a mana ability on a land.
   Pure function: (db, player-id, object-id, mana-color) -> db

   Arguments:
     db - Datascript database value
     player-id - The player activating the ability
     object-id - The land to tap
     mana-color - The color of mana to produce (:white :blue :black :red :green)

   Flow:
     1. Find mana ability from card data
     2. Check can-activate? via abilities module
     3. Pay costs via abilities module (includes tap, remove counters)
     4. Add chosen mana to pool
     5. Fire matching triggers (e.g., City of Brass :becomes-tapped)
     6. Check state-based actions (e.g., Gemstone Mine sacrifice)

   Returns unchanged db if any step fails."
  [db player-id object-id mana-color]
  (let [obj (queries/get-object db object-id)
        player-eid (queries/get-player-eid db player-id)]
    ;; Basic validation
    (if (and obj
             player-eid
             (= (:object/zone obj) :battlefield)
             (= (:db/id (:object/controller obj)) player-eid))
      ;; Find mana ability from card data
      (let [card (:object/card obj)
            card-abilities (:card/abilities card)
            mana-ability (first (filter #(= (:ability/type %) :mana) card-abilities))]
        (if (and mana-ability
                 (abilities/can-activate? db object-id mana-ability))
          ;; Execute ability
          (let [;; Step 1: Pay costs (tap, remove counters, etc.)
                db-after-costs (abilities/pay-all-costs db object-id (:ability/cost mana-ability))]
            (if db-after-costs
              (let [;; Step 2a: Handle :ability/produces (direct mana production)
                    ;; Resolve {:any N} to chosen color
                    produces (:ability/produces mana-ability)
                    db-after-produces (if produces
                                        (let [resolved-mana (if-let [any-count (:any produces)]
                                                              {mana-color any-count}
                                                              produces)]
                                          (effects/execute-effect db-after-costs player-id
                                                                  {:effect/type :add-mana
                                                                   :effect/mana resolved-mana}))
                                        db-after-costs)
                    ;; Step 2b: Execute ability effects (mana and other effects)
                    ;; Resolve :self targets to object-id before execution
                    ;; Resolve {:any N} mana effects to chosen color
                    db-after-effects (reduce (fn [db' effect]
                                               (let [;; Resolve :self target
                                                     resolved-effect (if (= :self (:effect/target effect))
                                                                       (assoc effect :effect/target object-id)
                                                                       effect)
                                                     ;; Resolve {:any N} mana to chosen color
                                                     resolved-effect (if (= :add-mana (:effect/type resolved-effect))
                                                                       (let [mana (:effect/mana resolved-effect)]
                                                                         (if-let [any-count (:any mana)]
                                                                           (assoc resolved-effect :effect/mana {mana-color any-count})
                                                                           resolved-effect))
                                                                       resolved-effect)]
                                                 (effects/execute-effect db' player-id resolved-effect)))
                                             db-after-produces
                                             (:ability/effects mana-ability []))
                    ;; Step 3: Dispatch permanent-tapped event to trigger registered triggers
                    ;; (replaces old fire-matching-triggers scanning approach)
                    db-after-triggers (dispatch/dispatch-event db-after-effects
                                                               (game-events/permanent-tapped-event object-id player-id))
                    ;; Step 4: Check state-based actions
                    db-after-sbas (state-based/check-and-execute-sbas db-after-triggers)]
                db-after-sbas)
              db))
          db))
      db)))


(rf/reg-event-db
  ::activate-mana-ability
  (fn [db [_ object-id mana-color]]
    (let [game-db (:game/db db)]
      (assoc db :game/db (activate-mana-ability game-db :player-1 object-id mana-color)))))


;; === Activate Non-Mana Ability ===

(defn activate-ability
  "Activate a non-mana ability on a permanent (e.g., fetchlands).
   Pure function: (db, player-id, object-id, ability-index) -> {:db db :pending-selection nil}

   Arguments:
     db - Datascript database value
     player-id - The player activating the ability
     object-id - The permanent to activate
     ability-index - Index of the ability in :card/abilities

   Flow:
     1. Find ability from card data
     2. Check can-activate? via abilities module
     3. Pay costs (tap, sacrifice, pay-life, etc.) - immediate
     4. Add ability trigger to stack - effects resolve later

   The ability goes on the stack and effects execute on resolution.
   This allows opponents to respond (e.g., counter with Stifle).
   Costs are paid on activation, not resolution.

   Selection effects (like tutor) are handled on resolution via
   resolve-activated-ability-with-selection.

   Returns {:db db :pending-selection nil}"
  [db player-id object-id ability-index]
  (let [obj (queries/get-object db object-id)
        player-eid (queries/get-player-eid db player-id)]
    (if (and obj
             player-eid
             (= (:object/zone obj) :battlefield)
             (= (:db/id (:object/controller obj)) player-eid))
      (let [card (:object/card obj)
            card-abilities (:card/abilities card)
            ability (nth card-abilities ability-index nil)]
        (if (and ability
                 (= :activated (:ability/type ability))
                 (abilities/can-activate? db object-id ability))
          ;; Pay costs (tap, sacrifice, pay-life) - these happen immediately on activation
          (let [db-after-costs (abilities/pay-all-costs db object-id (:ability/cost ability))]
            (if db-after-costs
              ;; Create ability trigger and add to stack (effects resolve later)
              ;; Note: UI adds card name from source, so description should just be the ability text
              (let [effects-list (:ability/effects ability [])
                    ability-trigger (triggers/create-trigger
                                      :activated-ability
                                      object-id
                                      player-id
                                      {:effects effects-list
                                       :description (:ability/description ability)})
                    db-with-trigger (triggers/add-trigger-to-stack db-after-costs ability-trigger)]
                {:db db-with-trigger
                 :pending-selection nil})
              {:db db :pending-selection nil}))
          {:db db :pending-selection nil}))
      {:db db :pending-selection nil})))


(rf/reg-event-db
  ::activate-ability
  (fn [db [_ object-id ability-index]]
    (let [game-db (:game/db db)
          result (activate-ability game-db :player-1 object-id ability-index)]
      (cond-> (assoc db :game/db (:db result))
        (:pending-selection result) (assoc :game/pending-selection (:pending-selection result))))))


;; === Selection System ===
;; For effects that require player selection (e.g., Careful Study discard)
;;
;; Selection state lives in app-db at :game/pending-selection
;; Structure:
;;   {:selection/zone :hand           ;; Zone to select from
;;    :selection/count 2              ;; Exact number to select
;;    :selection/player-id :player-1  ;; Who is selecting
;;    :selection/selected #{}         ;; Set of object-ids currently selected
;;    :selection/spell-id <uuid>      ;; Spell waiting for selection to resolve
;;    :selection/remaining-effects [] ;; Effects to execute after selection confirmed
;;    :selection/effect-type :discard} ;; What happens to selected cards


(defn has-selection-effect?
  "Check if any effect in the list requires player selection."
  [effects]
  (some #(or (= :player (:effect/selection %))
             (= :tutor (:effect/type %)))
        effects))


(defn find-selection-effect-index
  "Find the index of the first effect that requires player selection.
   This includes :discard with :selection :player and :tutor effects.
   Returns nil if no selection effect found."
  [effects]
  (first (keep-indexed (fn [i effect]
                         (when (or (= :player (:effect/selection effect))
                                   (= :tutor (:effect/type effect)))
                           i))
                       effects)))


(defn- build-tutor-selection
  "Build pending selection state for a tutor effect.
   Pre-filters library to find valid candidates."
  [db player-id object-id tutor-effect effects-after]
  (let [criteria (:effect/criteria tutor-effect)
        target-zone (or (:effect/target-zone tutor-effect) :hand)
        matching-objs (queries/query-library-by-criteria db player-id criteria)
        candidate-ids (set (map :object/id matching-objs))]
    {:selection/zone :library
     :selection/count 1
     :selection/player-id player-id
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/effect-type :tutor
     :selection/target-zone target-zone
     :selection/allow-fail-to-find true  ; Always allow fail-to-find (anti-pattern: NO auto-select)
     :selection/candidates candidate-ids
     :selection/shuffle? (get tutor-effect :effect/shuffle? true)
     :selection/enters-tapped (:effect/enters-tapped tutor-effect)}))


(defn- build-discard-selection
  "Build pending selection state for a discard effect."
  [player-id object-id discard-effect effects-after]
  {:selection/zone :hand
   :selection/count (:effect/count discard-effect)
   :selection/player-id player-id
   :selection/selected #{}
   :selection/spell-id object-id
   :selection/remaining-effects effects-after
   :selection/effect-type :discard})


(defn resolve-spell-with-selection
  "Resolve a spell, pausing if a selection effect is encountered.

   Returns a map with:
     :db - The updated game-db
     :pending-selection - Selection state if paused for selection, nil otherwise

   When a selection effect is encountered:
   - Effects before the selection are executed
   - The selection effect creates pending selection state
   - Effects after the selection are stored for later execution

   Handles two types of selection:
   - :discard with :selection :player - player chooses cards to discard
   - :tutor - player searches library for matching card (with fail-to-find)"
  [db player-id object-id]
  (let [obj (queries/get-object db object-id)]
    (if (not= :stack (:object/zone obj))
      {:db db :pending-selection nil}  ; No-op if spell not on stack
      (let [card (:object/card obj)
            effects-list (or (rules/get-active-effects db player-id card) [])
            selection-idx (find-selection-effect-index effects-list)]
        (if (nil? selection-idx)
          ;; No selection effect - resolve normally
          {:db (rules/resolve-spell db player-id object-id)
           :pending-selection nil}
          ;; Has selection effect - execute effects before it, then pause
          (let [effects-before (subvec (vec effects-list) 0 selection-idx)
                selection-effect (nth effects-list selection-idx)
                effects-after (subvec (vec effects-list) (inc selection-idx))
                effect-type (:effect/type selection-effect)
                ;; Execute effects before selection
                db-after-before (reduce (fn [d effect]
                                          (effects/execute-effect d player-id effect))
                                        db
                                        effects-before)
                ;; Build selection state based on effect type
                pending-selection (case effect-type
                                    :tutor (build-tutor-selection db-after-before player-id object-id
                                                                  selection-effect effects-after)
                                    :discard (build-discard-selection player-id object-id
                                                                      selection-effect effects-after)
                                    ;; Default for unknown selection types
                                    {:selection/zone :hand
                                     :selection/count (:effect/count selection-effect)
                                     :selection/player-id player-id
                                     :selection/selected #{}
                                     :selection/spell-id object-id
                                     :selection/remaining-effects effects-after
                                     :selection/effect-type effect-type})]
            {:db db-after-before
             :pending-selection pending-selection}))))))


(defn- build-tutor-selection-for-trigger
  "Build pending selection state for a tutor effect from an activated ability trigger.
   Uses :selection/trigger-id instead of :selection/spell-id for cleanup."
  [db player-id trigger-id tutor-effect effects-after]
  (let [criteria (:effect/criteria tutor-effect)
        target-zone (or (:effect/target-zone tutor-effect) :hand)
        matching-objs (queries/query-library-by-criteria db player-id criteria)
        candidate-ids (set (map :object/id matching-objs))]
    {:selection/zone :library
     :selection/count 1
     :selection/player-id player-id
     :selection/selected #{}
     :selection/trigger-id trigger-id  ; Use trigger-id, not spell-id
     :selection/source-type :trigger   ; Indicates this is from a trigger, not a spell
     :selection/remaining-effects effects-after
     :selection/effect-type :tutor
     :selection/target-zone target-zone
     :selection/allow-fail-to-find true
     :selection/candidates candidate-ids
     :selection/shuffle? (get tutor-effect :effect/shuffle? true)
     :selection/enters-tapped (:effect/enters-tapped tutor-effect)}))


(defn resolve-activated-ability-with-selection
  "Resolve an activated ability trigger, pausing if a selection effect is encountered.

   Returns a map with:
     :db - The updated game-db
     :pending-selection - Selection state if paused for selection, nil otherwise

   When a selection effect (like tutor) is encountered:
   - Effects before the selection are executed
   - The selection effect creates pending selection state
   - Effects after the selection are stored for later execution
   - The trigger is NOT removed from stack until selection is confirmed

   For abilities without selection effects, use resolve-trigger :activated-ability instead."
  [db trigger]
  (let [controller (:trigger/controller trigger)
        source-id (:trigger/source trigger)
        trigger-id (:trigger/id trigger)
        effects-list (get-in trigger [:trigger/data :effects] [])
        selection-idx (find-selection-effect-index effects-list)]
    (if (nil? selection-idx)
      ;; No selection effect - resolve normally and remove trigger
      {:db (-> db
               (triggers/resolve-trigger trigger)
               (triggers/remove-trigger trigger-id))
       :pending-selection nil}
      ;; Has selection effect - pause for selection
      (let [effects-before (subvec (vec effects-list) 0 selection-idx)
            selection-effect (nth effects-list selection-idx)
            effects-after (subvec (vec effects-list) (inc selection-idx))
            ;; Execute effects before selection, resolving :self target
            db-after-before (reduce (fn [d effect]
                                      (let [resolved-effect (cond
                                                              (= :self (:effect/target effect))
                                                              (assoc effect :effect/target source-id)

                                                              (= :controller (:effect/target effect))
                                                              (assoc effect :effect/target controller)

                                                              :else effect)]
                                        (effects/execute-effect d controller resolved-effect)))
                                    db
                                    effects-before)
            ;; Build selection state for tutor
            pending-selection (when (= :tutor (:effect/type selection-effect))
                                (build-tutor-selection-for-trigger db-after-before controller trigger-id
                                                                   selection-effect effects-after))]
        {:db db-after-before
         :pending-selection pending-selection}))))


(rf/reg-event-db
  ::set-pending-selection
  (fn [db [_ selection-state]]
    (assoc db :game/pending-selection selection-state)))


(rf/reg-event-db
  ::toggle-selection
  (fn [db [_ object-id]]
    (let [selected (get-in db [:game/pending-selection :selection/selected] #{})
          max-count (get-in db [:game/pending-selection :selection/count] 0)
          effect-type (get-in db [:game/pending-selection :selection/effect-type])
          currently-selected? (contains? selected object-id)
          new-selected (cond
                         ;; Deselecting current selection
                         currently-selected?
                         (disj selected object-id)

                         ;; For tutors (max 1): replace current selection
                         (and (= effect-type :tutor) (= max-count 1))
                         #{object-id}

                         ;; Under limit: add to selection
                         (< (count selected) max-count)
                         (conj selected object-id)

                         ;; At limit: don't add
                         :else selected)]
      (assoc-in db [:game/pending-selection :selection/selected] new-selected))))


(rf/reg-event-db
  ::confirm-selection
  (fn [db _]
    (let [selection (:game/pending-selection db)
          selected (:selection/selected selection)
          count-required (:selection/count selection)
          spell-id (:selection/spell-id selection)
          remaining-effects (:selection/remaining-effects selection)
          effect-type (:selection/effect-type selection)
          player-id (:selection/player-id selection)]
      (if (= (count selected) count-required)
        ;; Valid selection - execute the effect on selected cards
        (let [game-db (:game/db db)
              ;; Move selected cards based on effect type
              db-after-effect (case effect-type
                                :discard (reduce (fn [gdb obj-id]
                                                   (zones/move-to-zone gdb obj-id :graveyard))
                                                 game-db
                                                 selected)
                                ;; Default: no-op
                                game-db)
              ;; Execute remaining effects
              db-after-remaining (reduce (fn [d effect]
                                           (effects/execute-effect d player-id effect))
                                         db-after-effect
                                         (or remaining-effects []))
              ;; Move spell to graveyard (it was waiting on stack)
              db-final (zones/move-to-zone db-after-remaining spell-id :graveyard)]
          (-> db
              (assoc :game/db db-final)
              (dissoc :game/pending-selection)))
        ;; Invalid count - do nothing
        db))))


(rf/reg-event-db
  ::cancel-selection
  (fn [db _]
    ;; Cancel clears selection but keeps the pending state
    ;; Player must still make a valid selection
    (assoc-in db [:game/pending-selection :selection/selected] #{})))


;; === Tutor Selection ===

(defn execute-tutor-selection
  "Execute a tutor selection - move selected card to target zone and shuffle.
   Pure function: (db, selection) -> db

   Arguments:
     db - Datascript game database (not app-db)
     selection - Selection state map with:
       :selection/selected - Set of object-ids (0 or 1 for tutor)
       :selection/target-zone - Zone to move card to (:hand for Merchant Scroll, :battlefield for fetchlands)
       :selection/player-id - Who is tutoring
       :selection/shuffle? - Whether to shuffle after (default true)
       :selection/enters-tapped - If true and target-zone is :battlefield, enters tapped

   Handles:
     - Empty selection (fail-to-find): Just shuffles library
     - Card selected: Moves to target zone, sets tapped if needed, then shuffles

   CRITICAL: Move card BEFORE shuffle (anti-pattern: NO shuffling first)"
  [db selection]
  (let [selected (:selection/selected selection)
        target-zone (:selection/target-zone selection)
        player-id (:selection/player-id selection)
        should-shuffle? (get selection :selection/shuffle? true)
        enters-tapped? (and (= target-zone :battlefield)
                            (:selection/enters-tapped selection))]
    (if (empty? selected)
      ;; Fail-to-find: just shuffle
      (if should-shuffle?
        (zones/shuffle-library db player-id)
        db)
      ;; Card found: move to target zone, set tapped if needed, then shuffle
      (let [card-id (first selected)
            db-after-move (zones/move-to-zone db card-id target-zone)
            ;; If entering battlefield tapped, set tapped state
            db-after-tapped (if enters-tapped?
                              (let [obj-eid (d/q '[:find ?e .
                                                   :in $ ?oid
                                                   :where [?e :object/id ?oid]]
                                                 db-after-move card-id)]
                                (d/db-with db-after-move [[:db/add obj-eid :object/tapped true]]))
                              db-after-move)]
        (if should-shuffle?
          (zones/shuffle-library db-after-tapped player-id)
          db-after-tapped)))))


(rf/reg-event-db
  ::confirm-tutor-selection
  (fn [db _]
    (let [selection (:game/pending-selection db)
          selected (:selection/selected selection)
          candidates (:selection/candidates selection)
          spell-id (:selection/spell-id selection)
          trigger-id (:selection/trigger-id selection)
          source-type (:selection/source-type selection)
          remaining-effects (:selection/remaining-effects selection)
          player-id (:selection/player-id selection)
          game-db (:game/db db)]
      ;; Validate selection: must be empty (fail-to-find) or a valid candidate
      (if (or (empty? selected)
              (and (= 1 (count selected))
                   (contains? candidates (first selected))))
        ;; Valid tutor selection
        (let [db-after-tutor (execute-tutor-selection game-db selection)
              ;; Execute remaining effects
              db-after-remaining (reduce (fn [d effect]
                                           (effects/execute-effect d player-id effect))
                                         db-after-tutor
                                         (or remaining-effects []))
              ;; Clean up source: move spell to graveyard OR remove trigger from stack
              db-final (if (= source-type :trigger)
                         ;; Trigger-based selection (activated ability): remove trigger
                         (triggers/remove-trigger db-after-remaining trigger-id)
                         ;; Spell-based selection: move spell to graveyard
                         (zones/move-to-zone db-after-remaining spell-id :graveyard))]
          (-> db
              (assoc :game/db db-final)
              (dissoc :game/pending-selection)))
        ;; Invalid selection - do nothing
        db))))
