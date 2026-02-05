(ns fizzle.events.game
  (:require
    [clojure.set :as set]
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
    [fizzle.engine.targeting :as targeting]
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
(declare build-cast-time-target-selection)
(declare confirm-cast-time-target)


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
        (let [modes (rules/get-casting-modes game-db :player-1 selected)
              castable-modes (filterv #(rules/can-cast-mode? game-db :player-1 selected %) modes)
              ;; Check for targeting requirements
              obj (queries/get-object game-db selected)
              card (:object/card obj)
              targeting-reqs (targeting/get-targeting-requirements card)]
          (cond
            ;; No castable modes - shouldn't happen if can-cast? passed
            (empty? castable-modes)
            db

            ;; Multiple modes: show selector first (targeting checked after mode selection)
            (> (count castable-modes) 1)
            (assoc db :game/pending-mode-selection
                   {:object-id selected
                    :modes castable-modes})

            ;; Single mode with targeting: prompt for targets
            (and (= 1 (count castable-modes)) (seq targeting-reqs))
            (let [mode (first castable-modes)
                  first-req (first targeting-reqs)
                  selection (build-cast-time-target-selection game-db :player-1 selected mode first-req)]
              (-> db
                  (assoc :game/pending-selection selection)
                  (dissoc :game/selected-card)))

            ;; Single mode without targeting: cast immediately
            :else
            (-> db
                (assoc :game/db (rules/cast-spell-mode game-db :player-1 selected (first castable-modes)))
                (dissoc :game/selected-card))))
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
            all-mana-abilities (filter #(= :mana (:ability/type %)) card-abilities)
            ;; Find mana ability that produces the requested color
            ;; Handles multiple patterns:
            ;; 1. :ability/produces {:blue 1} - direct match
            ;; 2. :ability/produces {:any 1} - any color (Gemstone Mine, Lotus Petal)
            ;; 3. :ability/effects with :add-mana {:any N} - City of Brass, LED
            ;; If mana-color is nil or no match found, fall back to first mana ability
            matching-ability (when mana-color
                               (first (filter
                                        (fn [ability]
                                          (let [produces (:ability/produces ability)
                                                effects (:ability/effects ability)
                                                ;; Check if any effect adds {:any N} mana
                                                has-any-mana-effect? (some #(and (= :add-mana (:effect/type %))
                                                                                 (contains? (:effect/mana %) :any))
                                                                           effects)]
                                            (or
                                              ;; Direct produces match
                                              (and produces (contains? produces mana-color))
                                              ;; Produces any color
                                              (and produces (contains? produces :any))
                                              ;; Effect adds any color
                                              has-any-mana-effect?)))
                                        all-mana-abilities)))
            ;; Fall back to first mana ability if no match or nil color
            mana-ability (or matching-ability (first all-mana-abilities))]
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
                    ;; Resolve :controller targets to player-id before execution
                    ;; Resolve {:any N} mana effects to chosen color
                    db-after-effects (reduce (fn [db' effect]
                                               (let [;; Resolve :self target (for objects)
                                                     resolved-effect (cond
                                                                       (= :self (:effect/target effect))
                                                                       (assoc effect :effect/target object-id)

                                                                       (= :controller (:effect/target effect))
                                                                       (assoc effect :effect/target player-id)

                                                                       :else effect)
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

(defn- build-ability-target-selection
  "Build pending selection state for ability targeting.
   Used when an ability has :ability/targeting requirements.

   Arguments:
     db - Datascript database
     player-id - Activating player
     object-id - Permanent being activated
     ability-index - Index of ability in :card/abilities
     target-req - First targeting requirement (player target for now)

   Returns selection state map."
  [db player-id object-id ability-index target-req]
  (let [valid-targets (targeting/find-valid-targets db player-id target-req)]
    {:selection/type :ability-targeting
     :selection/player-id player-id
     :selection/object-id object-id
     :selection/ability-index ability-index
     :selection/target-requirement target-req
     :selection/valid-targets valid-targets
     :selection/selected-target nil}))


(defn confirm-ability-target
  "Complete ability activation after target selection.

   1. Pays costs (tap, sacrifice, pay-life)
   2. Creates ability trigger with stored targets
   3. Adds trigger to stack

   Arguments:
     db - Datascript database
     selection - Ability target selection state with :selection/selected-target set

   Returns {:db db :pending-selection nil}"
  [db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/object-id selection)
        ability-index (:selection/ability-index selection)
        target-req (:selection/target-requirement selection)
        target-id (:selection/selected-target selection)
        target-ref (:target/id target-req)]
    (if target-id
      (let [obj (queries/get-object db object-id)
            card (:object/card obj)
            ability (nth (:card/abilities card) ability-index)
            ;; Pay costs now
            db-after-costs (abilities/pay-all-costs db object-id (:ability/cost ability))]
        (if db-after-costs
          ;; Create trigger with stored targets
          (let [effects-list (:ability/effects ability [])
                ability-trigger (triggers/create-trigger
                                  :activated-ability
                                  object-id
                                  player-id
                                  {:effects effects-list
                                   :description (:ability/description ability)
                                   :targets {target-ref target-id}})
                db-with-trigger (triggers/add-trigger-to-stack db-after-costs ability-trigger)]
            {:db db-with-trigger
             :pending-selection nil})
          {:db db :pending-selection nil}))
      ;; No target selected - return unchanged (activation cancelled)
      {:db db :pending-selection nil})))


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
     3. If ability has targeting: pause for target selection (costs not paid yet)
     4. If no targeting: Pay costs and add to stack immediately

   The ability goes on the stack and effects execute on resolution.
   This allows opponents to respond (e.g., counter with Stifle).
   Costs are paid on activation (or after target selection), not resolution.

   Selection effects (like tutor) are handled on resolution via
   resolve-activated-ability-with-selection.

   Returns {:db db :pending-selection selection-state-or-nil}"
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
          ;; Check if ability has targeting requirements
          (let [targeting-reqs (targeting/get-targeting-requirements ability)]
            (if (seq targeting-reqs)
              ;; Has targeting - pause for target selection (don't pay costs yet)
              (let [first-req (first targeting-reqs)
                    selection (build-ability-target-selection db player-id object-id ability-index first-req)]
                {:db db
                 :pending-selection selection})
              ;; No targeting - pay costs and add to stack immediately
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
                  {:db db :pending-selection nil}))))
          {:db db :pending-selection nil}))
      {:db db :pending-selection nil})))


(rf/reg-event-db
  ::activate-ability
  (fn [db [_ object-id ability-index]]
    (let [game-db (:game/db db)
          result (activate-ability game-db :player-1 object-id ability-index)]
      (cond-> (assoc db :game/db (:db result))
        (:pending-selection result) (assoc :game/pending-selection (:pending-selection result))))))


(rf/reg-event-db
  ::confirm-ability-target
  (fn [db [_ target-id]]
    (let [selection (get db :game/pending-selection)
          selection-with-target (assoc selection :selection/selected-target target-id)
          game-db (:game/db db)
          result (confirm-ability-target game-db selection-with-target)]
      (-> db
          (assoc :game/db (:db result))
          (dissoc :game/pending-selection)))))


(rf/reg-event-db
  ::confirm-cast-time-target
  (fn [db [_ target-id]]
    (let [selection (get db :game/pending-selection)
          selection-with-target (assoc selection :selection/selected-target target-id)
          game-db (:game/db db)
          new-game-db (confirm-cast-time-target game-db selection-with-target)]
      (-> db
          (assoc :game/db new-game-db)
          (dissoc :game/pending-selection)))))


(rf/reg-event-db
  ::select-cast-time-object-target
  (fn [db [_ object-id]]
    (let [selection (:game/pending-selection db)
          valid-targets (set (:selection/valid-targets selection))]
      (if (contains? valid-targets object-id)
        ;; Toggle selection: if already selected, deselect; otherwise select
        (let [currently-selected (:selection/selected-target selection)
              new-selection (if (= currently-selected object-id)
                              nil
                              object-id)]
          (assoc-in db [:game/pending-selection :selection/selected-target] new-selection))
        ;; Invalid target - ignore
        db))))


(rf/reg-event-db
  ::select-ability-object-target
  (fn [db [_ object-id]]
    (let [selection (:game/pending-selection db)
          valid-targets (set (:selection/valid-targets selection))]
      (if (contains? valid-targets object-id)
        ;; Toggle selection: if already selected, deselect; otherwise select
        (let [currently-selected (:selection/selected-target selection)
              new-selection (if (= currently-selected object-id)
                              nil
                              object-id)]
          (assoc-in db [:game/pending-selection :selection/selected-target] new-selection))
        ;; Invalid target - ignore
        db))))


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
             (= :tutor (:effect/type %))
             (and (= :scry (:effect/type %))
                  (pos? (or (:effect/amount %) 0))))
        effects))


(defn find-selection-effect-index
  "Find the index of the first effect that requires player selection.
   This includes:
   - :discard with :selection :player - player chooses cards to discard
   - :tutor - player searches library for matching card
   - :scry with amount > 0 - player arranges top N cards
   - :effect/target :any-player - player chooses a target player
   Returns nil if no selection effect found."
  [effects]
  (first (keep-indexed (fn [i effect]
                         (when (or (= :player (:effect/selection effect))
                                   (= :tutor (:effect/type effect))
                                   (and (= :scry (:effect/type effect))
                                        (pos? (or (:effect/amount effect) 0)))
                                   (= :any-player (:effect/target effect)))
                           i))
                       effects)))


(defn build-tutor-selection
  "Build pending selection state for a tutor effect.
   Pre-filters library to find valid candidates.

   Supports multi-select via :effect/select-count (default 1 for backwards compat).
   Actual selection count = min(select-count, candidate-count).
   When select-count > 1, :selection/exact? is true (must select exactly that many)."
  [db player-id object-id tutor-effect effects-after]
  (let [criteria (:effect/criteria tutor-effect)
        target-zone (or (:effect/target-zone tutor-effect) :hand)
        matching-objs (queries/query-library-by-criteria db player-id criteria)
        candidate-ids (set (map :object/id matching-objs))
        ;; Multi-select support: default to 1 for backwards compat
        effect-select-count (max 1 (or (:effect/select-count tutor-effect) 1))
        ;; Actual count is min of requested and available candidates
        actual-select-count (min effect-select-count (count candidate-ids))]
    {:selection/zone :library
     :selection/count actual-select-count  ; Legacy key for UI compatibility
     :selection/select-count actual-select-count  ; New key for multi-select
     :selection/exact? true  ; Must select exactly this many (or fail-to-find)
     :selection/player-id player-id
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/effect-type :tutor
     :selection/target-zone target-zone
     :selection/allow-fail-to-find true  ; Always allow fail-to-find (anti-pattern: NO auto-select)
     :selection/candidates candidate-ids
     :selection/shuffle? (get tutor-effect :effect/shuffle? true)
     :selection/enters-tapped (:effect/enters-tapped tutor-effect)
     :selection/pile-choice (:effect/pile-choice tutor-effect)}))


(defn build-pile-choice-selection
  "Build pending selection state for pile choice (Intuition-style).
   After multi-select tutor, player chooses which cards go to hand vs graveyard.

   Arguments:
     card-ids - Set of object-ids selected from tutor
     pile-choice - Config map with :hand (count for hand) and :graveyard :rest
     player-id - Player making the choice
     spell-id - The spell that triggered this selection
     remaining-effects - Effects to execute after pile choice

   Returns selection state with:
     :selection/type :pile-choice
     :selection/candidates - Set of card IDs to distribute
     :selection/hand-count - How many go to hand
     :selection/selected - Pre-selected cards (auto-select if only 1 card)
     :selection/allow-random - true (Random button available)"
  [card-ids pile-choice player-id spell-id remaining-effects]
  (let [hand-count (:hand pile-choice)
        candidates (set card-ids)
        ;; Auto-select if only 1 card (per epic open question: still show UI)
        auto-selected (if (= 1 (count candidates))
                        candidates
                        #{})]
    {:selection/type :pile-choice
     :selection/candidates candidates
     :selection/hand-count hand-count
     :selection/selected auto-selected
     :selection/player-id player-id
     :selection/spell-id spell-id
     :selection/remaining-effects remaining-effects
     :selection/allow-random true}))


(defn select-random-pile-choice
  "Randomly select hand-count cards from pile-choice candidates.
   Pure function: (app-db) -> app-db

   Updates :game/pending-selection with random selection.
   Does NOT confirm - user can review and change before confirming."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        candidates (vec (:selection/candidates selection))
        hand-count (:selection/hand-count selection)
        random-selected (set (take hand-count (shuffle candidates)))]
    (assoc-in app-db [:game/pending-selection :selection/selected] random-selected)))


(defn build-scry-selection
  "Build pending selection state for a scry effect.
   Reveals top N cards from library for player to arrange.
   Returns nil if library is empty or amount <= 0 (no selection needed)."
  [db player-id object-id scry-effect effects-after]
  (let [amount (or (:effect/amount scry-effect) 0)]
    (when (pos? amount)
      (let [library-cards (queries/get-top-n-library db player-id amount)]
        (when (seq library-cards)
          {:selection/type :scry
           :selection/player-id player-id
           :selection/cards (vec library-cards)
           :selection/top-pile []
           :selection/bottom-pile []
           :selection/spell-id object-id
           :selection/remaining-effects (vec effects-after)})))))


(defn build-peek-selection
  "Build pending selection state for a peek-and-select effect.
   Reveals top N cards from library for player to select some for hand.
   Returns nil if library is empty or count <= 0 (no selection needed).

   Arguments:
     db - Datascript game database
     player-id - Player performing the peek
     object-id - Spell-id for cleanup after selection confirmed
     effect - The :peek-and-select effect map with:
       :effect/count - Number of cards to peek (default 0)
       :effect/select-count - Number to select for hand (default 1)
       :effect/selected-zone - Zone for selected cards (default :hand)
       :effect/remainder-zone - Zone for non-selected (default :bottom-of-library)
       :effect/shuffle-remainder? - Whether to shuffle remainder (default false)
     effects-after - Remaining effects to execute after selection

   Anti-pattern: NO auto-select even with 1 card. Player must choose or fail-to-find."
  [db player-id object-id effect effects-after]
  (let [peek-count (or (:effect/count effect) 0)]
    (when (pos? peek-count)
      (let [library-cards (queries/get-top-n-library db player-id peek-count)]
        (when (seq library-cards)
          (let [candidate-ids (set library-cards)
                select-count (or (:effect/select-count effect) 1)
                ;; Actual count is min of requested and available
                actual-select-count (min select-count (count candidate-ids))]
            {:selection/zone :peek  ; Distinct from :library (tutor)
             :selection/effect-type :peek-and-select
             :selection/candidates candidate-ids
             :selection/select-count actual-select-count
             :selection/exact? false  ; Can select fewer (fail-to-find)
             :selection/allow-fail-to-find true
             :selection/selected #{}
             :selection/player-id player-id
             :selection/spell-id object-id
             :selection/remaining-effects (vec effects-after)
             :selection/selected-zone (or (:effect/selected-zone effect) :hand)
             :selection/remainder-zone (or (:effect/remainder-zone effect) :bottom-of-library)
             :selection/shuffle-remainder? (:effect/shuffle-remainder? effect)}))))))


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


(defn build-graveyard-selection
  "Build pending selection state for returning cards from graveyard.
   Unlike tutor (hidden zone), graveyard is public - no fail-to-find option.
   Player can select 0 to :effect/count cards.

   Arguments:
     db - Datascript game database
     player-id - Caster's player-id (used to resolve :self target)
     object-id - Spell-id for cleanup after selection confirmed
     effect - The :return-from-graveyard effect map with:
       :effect/target - :self, :opponent, or player-id (defaults to caster)
       :effect/count - Max cards to return (0 to N selection)
       :effect/selection - Should be :player for this to be called
     effects-after - Vector of effects to execute after selection

   Returns selection state map for UI to render graveyard selection."
  [db player-id object-id effect effects-after]
  (let [target (get effect :effect/target player-id)
        target-player (cond
                        (= target :opponent) (queries/get-opponent-id db player-id)
                        (= target :self) player-id
                        :else target)
        gy-cards (or (queries/get-objects-in-zone db target-player :graveyard) [])
        candidate-ids (set (map :object/id gy-cards))]
    {:selection/type :graveyard-return
     :selection/zone :graveyard
     :selection/count (:effect/count effect)
     :selection/min-count 0  ; Can select 0 cards (up to N)
     :selection/player-id target-player
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/effect-type :return-from-graveyard
     :selection/candidate-ids candidate-ids}))


(defn- build-player-target-selection
  "Build pending selection state for a player-targeted effect.
   Used when :effect/target is :any-player - player must choose target."
  [player-id object-id target-effect effects-after]
  {:selection/type :player-target
   :selection/player-id player-id
   :selection/selected-target nil  ; Will be :player-1 or :opponent
   :selection/spell-id object-id
   :selection/target-effect target-effect  ; The effect needing a target
   :selection/remaining-effects effects-after
   :selection/effect-type :player-target})


(defn- resolve-effect-with-stored-target
  "Resolve an effect using stored targets from :object/targets.
   Returns the effect with :any-player replaced by the actual stored target."
  [effect stored-targets]
  (if (= :any-player (:effect/target effect))
    ;; Look up the stored player target and resolve
    (if-let [stored-player (:player stored-targets)]
      (assoc effect :effect/target stored-player)
      effect)
    effect))


(defn resolve-spell-with-selection
  "Resolve a spell, pausing if a selection effect is encountered.

   Returns a map with:
     :db - The updated game-db
     :pending-selection - Selection state if paused for selection, nil otherwise

   When a selection effect is encountered:
   - Effects before the selection are executed
   - The selection effect creates pending selection state
   - Effects after the selection are stored for later execution

   If :object/targets contains stored targets from cast-time targeting:
   - Uses stored targets instead of prompting
   - Resolves effects normally using the pre-selected targets

   Handles two types of selection:
   - :discard with :selection :player - player chooses cards to discard
   - :tutor - player searches library for matching card (with fail-to-find)"
  [db player-id object-id]
  (let [obj (queries/get-object db object-id)]
    (if (not= :stack (:object/zone obj))
      {:db db :pending-selection nil}  ; No-op if spell not on stack
      (let [card (:object/card obj)
            stored-targets (:object/targets obj)
            effects-list (or (rules/get-active-effects db player-id card object-id) [])
            selection-idx (find-selection-effect-index effects-list)
            ;; Check if stored targets can satisfy the selection effect
            has-stored-player-target (and stored-targets
                                          (:player stored-targets)
                                          selection-idx
                                          (= :any-player (:effect/target (nth effects-list selection-idx))))
            ;; Check if spell has object targets (for fizzle checking)
            has-stored-object-targets (and stored-targets
                                           (seq stored-targets)
                                           (not (:player stored-targets)))]
        (cond
          ;; Cast-time targeting with player target: check fizzle, use stored targets
          has-stored-player-target
          (let [cast-mode (:object/cast-mode obj)
                destination (or (:mode/on-resolve cast-mode) :graveyard)]
            ;; Fizzle check: if any target is no longer legal, skip effects
            (if (targeting/all-targets-legal? db object-id)
              ;; Targets legal - resolve normally with stored targets
              (let [resolved-effects (mapv #(resolve-effect-with-stored-target % stored-targets) effects-list)
                    db-after-effects (reduce (fn [d effect]
                                               (effects/execute-effect d player-id effect))
                                             db
                                             resolved-effects)
                    db-final (zones/move-to-zone db-after-effects object-id destination)]
                {:db db-final
                 :pending-selection nil})
              ;; Fizzle: targets invalid, skip effects, move spell off stack
              {:db (zones/move-to-zone db object-id destination)
               :pending-selection nil}))

          ;; Cast-time targeting with object target (e.g., Recoup): check fizzle, resolve with object-id
          has-stored-object-targets
          (let [cast-mode (:object/cast-mode obj)
                destination (or (:mode/on-resolve cast-mode) :graveyard)]
            ;; Fizzle check: if any target is no longer legal, skip effects
            (if (targeting/all-targets-legal? db object-id)
              ;; Targets legal - resolve with object-id for effects that need stored targets
              {:db (rules/resolve-spell db player-id object-id)
               :pending-selection nil}
              ;; Fizzle: targets invalid, skip effects, move spell off stack
              {:db (zones/move-to-zone db object-id destination)
               :pending-selection nil}))

          ;; No selection effect and no stored targets - resolve normally
          (nil? selection-idx)
          {:db (rules/resolve-spell db player-id object-id)
           :pending-selection nil}

          ;; Has selection effect without stored targets - pause for selection
          :else
          (let [effects-before (subvec (vec effects-list) 0 selection-idx)
                selection-effect (nth effects-list selection-idx)
                effects-after (subvec (vec effects-list) (inc selection-idx))
                effect-type (:effect/type selection-effect)
                ;; Execute effects before selection (pass object-id for effects like :exile-self)
                db-after-before (reduce (fn [d effect]
                                          (effects/execute-effect d player-id effect object-id))
                                        db
                                        effects-before)
                ;; Build selection state based on effect type or target type
                pending-selection (cond
                                    ;; Player targeting (e.g., Deep Analysis "target player draws")
                                    (= :any-player (:effect/target selection-effect))
                                    (build-player-target-selection player-id object-id
                                                                   selection-effect effects-after)

                                    ;; Tutor effect
                                    (= effect-type :tutor)
                                    (build-tutor-selection db-after-before player-id object-id
                                                           selection-effect effects-after)

                                    ;; Discard effect
                                    (= effect-type :discard)
                                    (build-discard-selection player-id object-id
                                                             selection-effect effects-after)

                                    ;; Scry effect
                                    (= effect-type :scry)
                                    (build-scry-selection db-after-before player-id object-id
                                                          selection-effect effects-after)

                                    ;; Return from graveyard effect
                                    (= effect-type :return-from-graveyard)
                                    (build-graveyard-selection db-after-before player-id object-id
                                                               selection-effect effects-after)

                                    ;; Default for unknown selection types
                                    :else
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


(defn- build-player-target-selection-for-trigger
  "Build pending selection state for a player-targeted effect from an activated ability trigger.
   Uses :selection/trigger-id instead of :selection/spell-id for cleanup."
  [player-id trigger-id target-effect effects-after]
  {:selection/type :player-target
   :selection/player-id player-id
   :selection/selected-target nil  ; Will be :player-1 or :opponent
   :selection/trigger-id trigger-id
   :selection/source-type :trigger   ; Indicates this is from a trigger, not a spell
   :selection/target-effect target-effect  ; The effect needing a target
   :selection/remaining-effects effects-after
   :selection/effect-type :player-target})


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

   If stored targets exist in :trigger/data :targets (from cast-time targeting):
   - Uses stored targets for :any-player effects instead of prompting
   - Still prompts for discard/tutor selection effects
   - Resolves effects in order, stopping at selection effects

   For abilities without selection effects, use resolve-trigger :activated-ability instead."
  [db trigger]
  (let [controller (:trigger/controller trigger)
        source-id (:trigger/source trigger)
        trigger-id (:trigger/id trigger)
        effects-list (get-in trigger [:trigger/data :effects] [])
        stored-targets (get-in trigger [:trigger/data :targets])
        selection-idx (find-selection-effect-index effects-list)
        stored-player (:player stored-targets)
        ;; Check if stored targets can satisfy the :any-player effect
        ;; (the first selection effect is :any-player targeting)
        has-stored-player-target (and stored-player
                                      selection-idx
                                      (= :any-player (:effect/target (nth effects-list selection-idx))))]
    (cond
      ;; Stored targets exist for player targeting - use them instead of prompting for player
      ;; But there may still be subsequent selection effects (like discard)
      has-stored-player-target
      (let [;; The :any-player effect is at selection-idx
            ;; Resolve it with stored target, then check for next selection effect
            player-target-effect (nth effects-list selection-idx)
            resolved-player-effect (assoc player-target-effect :effect/target stored-player)
            effects-before (subvec (vec effects-list) 0 selection-idx)
            effects-after (subvec (vec effects-list) (inc selection-idx))
            ;; Execute effects before the player target, resolving :self/:controller
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
            ;; Execute the resolved player target effect (e.g., draw 3 for target player)
            db-after-player-effect (effects/execute-effect db-after-before controller resolved-player-effect)
            ;; Check if there's another selection effect in effects-after
            next-selection-idx (find-selection-effect-index effects-after)]
        (if next-selection-idx
          ;; There's another selection effect (e.g., discard) - build selection for it
          (let [effects-before-next (subvec (vec effects-after) 0 next-selection-idx)
                next-selection-effect (nth effects-after next-selection-idx)
                effects-after-next (subvec (vec effects-after) (inc next-selection-idx))
                ;; Execute effects before the next selection
                db-after-before-next (reduce (fn [d effect]
                                               (effects/execute-effect d controller effect))
                                             db-after-player-effect
                                             effects-before-next)
                ;; Build selection state for the discard (target player discards)
                pending-selection (when (= :player (:effect/selection next-selection-effect))
                                    {:selection/type :discard
                                     :selection/player-id stored-player  ; Target player discards, not controller!
                                     :selection/count (:effect/count next-selection-effect)
                                     :selection/selected #{}
                                     :selection/trigger-id trigger-id
                                     :selection/source-type :trigger
                                     :selection/remaining-effects effects-after-next
                                     :selection/effect-type :discard})]
            {:db db-after-before-next
             :pending-selection pending-selection})
          ;; No more selection effects - execute remaining and clean up
          (let [db-after-all (reduce (fn [d effect]
                                       (effects/execute-effect d controller effect))
                                     db-after-player-effect
                                     effects-after)
                db-final (triggers/remove-trigger db-after-all trigger-id)]
            {:db db-final
             :pending-selection nil})))

      ;; No selection effect - resolve with stored targets if present
      (nil? selection-idx)
      (let [;; Execute effects, resolving :effect/target-ref from stored-targets
            db-after-effects (reduce
                               (fn [d effect]
                                 (let [;; Resolve target-ref from stored targets
                                       target-ref (:effect/target-ref effect)
                                       resolved-target (when target-ref
                                                         (get stored-targets target-ref))
                                       ;; Also handle :self and :controller targets
                                       resolved-effect (cond
                                                         ;; Has target-ref with stored target - resolve to actual target
                                                         resolved-target
                                                         (assoc effect :effect/target resolved-target)

                                                         (= :self (:effect/target effect))
                                                         (assoc effect :effect/target source-id)

                                                         (= :controller (:effect/target effect))
                                                         (assoc effect :effect/target controller)

                                                         :else effect)]
                                   (effects/execute-effect d controller resolved-effect source-id)))
                               db
                               effects-list)
            db-final (triggers/remove-trigger db-after-effects trigger-id)]
        {:db db-final
         :pending-selection nil})

      ;; Has selection effect without stored targets - pause for selection
      :else
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
            ;; Build selection state based on effect type
            pending-selection (cond
                                ;; Player targeting (e.g., Cephalid Coliseum "target player draws")
                                (= :any-player (:effect/target selection-effect))
                                (build-player-target-selection-for-trigger controller trigger-id
                                                                           selection-effect effects-after)

                                ;; Tutor effect
                                (= :tutor (:effect/type selection-effect))
                                (build-tutor-selection-for-trigger db-after-before controller trigger-id
                                                                   selection-effect effects-after)

                                ;; Discard selection
                                (= :player (:effect/selection selection-effect))
                                {:selection/type :discard
                                 :selection/player-id controller
                                 :selection/count (:effect/count selection-effect)
                                 :selection/selected #{}
                                 :selection/trigger-id trigger-id
                                 :selection/source-type :trigger
                                 :selection/remaining-effects effects-after
                                 :selection/effect-type :discard}

                                :else nil)]
        {:db db-after-before
         :pending-selection pending-selection}))))


(rf/reg-event-db
  ::set-pending-selection
  (fn [db [_ selection-state]]
    (assoc db :game/pending-selection selection-state)))


(rf/reg-event-db
  ::toggle-selection
  (fn [db [_ object-id]]
    (let [selection (get db :game/pending-selection)
          selected (get selection :selection/selected #{})
          selection-type (get selection :selection/type)
          effect-type (get selection :selection/effect-type)
          ;; Max count depends on selection type
          max-count (cond
                      ;; Pile choice uses :hand-count
                      (= selection-type :pile-choice)
                      (get selection :selection/hand-count 1)
                      ;; Multi-select tutors use :select-count
                      (get selection :selection/select-count)
                      (get selection :selection/select-count)
                      ;; Default to :count
                      :else
                      (get selection :selection/count 0))
          currently-selected? (contains? selected object-id)
          new-selected (cond
                         ;; Deselecting current selection
                         currently-selected?
                         (disj selected object-id)

                         ;; For single-select tutors (max 1): replace current selection
                         (and (= effect-type :tutor) (= max-count 1) (not= selection-type :pile-choice))
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
          trigger-id (:selection/trigger-id selection)
          source-type (:selection/source-type selection)
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
              ;; Clean up source: move spell to destination OR remove trigger from stack
              ;; BUT only if the spell is still on the stack (effects like :exile-self may have moved it)
              db-final (if (= source-type :trigger)
                         ;; Trigger-based selection (activated ability): remove trigger
                         (triggers/remove-trigger db-after-remaining trigger-id)
                         ;; Spell-based selection: use cast mode's destination (e.g., :exile for flashback)
                         (let [spell-obj (queries/get-object db-after-remaining spell-id)
                               current-zone (:object/zone spell-obj)]
                           ;; Only move if still on stack (effects like :exile-self may have already moved it)
                           (if (= current-zone :stack)
                             (let [cast-mode (:object/cast-mode spell-obj)
                                   destination (or (:mode/on-resolve cast-mode) :graveyard)]
                               (zones/move-to-zone db-after-remaining spell-id destination))
                             db-after-remaining)))]
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


;; === Graveyard Selection ===
;; For :return-from-graveyard effects with player selection

(rf/reg-event-db
  ::confirm-graveyard-selection
  (fn [db _]
    (let [selection (:game/pending-selection db)
          selected (:selection/selected selection)
          max-count (:selection/count selection)
          candidates (:selection/candidate-ids selection)
          spell-id (:selection/spell-id selection)
          trigger-id (:selection/trigger-id selection)
          source-type (:selection/source-type selection)
          remaining-effects (:selection/remaining-effects selection)
          player-id (:selection/player-id selection)
          game-db (:game/db db)
          ;; Validate: 0 to max-count cards, all from candidate set
          valid-selection? (and (<= (count selected) max-count)
                                (every? #(contains? candidates %) selected))]
      (if valid-selection?
        (let [;; Move selected cards from graveyard to hand
              db-after-effect (reduce (fn [gdb obj-id]
                                        (zones/move-to-zone gdb obj-id :hand))
                                      game-db
                                      selected)
              ;; Execute remaining effects
              db-after-remaining (reduce (fn [d effect]
                                           (effects/execute-effect d player-id effect))
                                         db-after-effect
                                         (or remaining-effects []))
              ;; Clean up source: move spell to destination OR remove trigger
              ;; BUT only if the spell is still on the stack (effects like :exile-self may have moved it)
              db-final (if (= source-type :trigger)
                         (triggers/remove-trigger db-after-remaining trigger-id)
                         (let [spell-obj (queries/get-object db-after-remaining spell-id)
                               current-zone (:object/zone spell-obj)]
                           ;; Only move if still on stack (effects like :exile-self may have already moved it)
                           (if (= current-zone :stack)
                             (let [cast-mode (:object/cast-mode spell-obj)
                                   destination (or (:mode/on-resolve cast-mode) :graveyard)]
                               (zones/move-to-zone db-after-remaining spell-id destination))
                             db-after-remaining)))]
          (-> db
              (assoc :game/db db-final)
              (dissoc :game/pending-selection)))
        ;; Invalid selection - do nothing (player must fix selection)
        db))))


;; === Tutor Selection ===

(defn execute-tutor-selection
  "Execute a tutor selection - move selected cards to target zone and shuffle.
   Pure function: (db, selection) -> db

   Arguments:
     db - Datascript game database (not app-db)
     selection - Selection state map with:
       :selection/selected - Set of object-ids (1 or more for multi-select tutor)
       :selection/target-zone - Zone to move cards to (:hand for Merchant Scroll, :battlefield for fetchlands)
       :selection/player-id - Who is tutoring
       :selection/shuffle? - Whether to shuffle after (default true)
       :selection/enters-tapped - If true and target-zone is :battlefield, enters tapped

   Handles:
     - Empty selection (fail-to-find): Just shuffles library
     - Cards selected: Moves ALL to target zone, sets tapped if needed, then shuffles ONCE

   CRITICAL: Move ALL cards BEFORE shuffle (anti-pattern: NO shuffling between moves)"
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
      ;; Cards found: move ALL to target zone, set tapped if needed, then shuffle ONCE
      (let [;; Move all selected cards to target zone
            db-after-moves (reduce (fn [d card-id]
                                     (zones/move-to-zone d card-id target-zone))
                                   db
                                   selected)
            ;; If entering battlefield tapped, set tapped state for all moved cards
            db-after-tapped (if enters-tapped?
                              (reduce (fn [d card-id]
                                        (let [obj-eid (d/q '[:find ?e .
                                                             :in $ ?oid
                                                             :where [?e :object/id ?oid]]
                                                           d card-id)]
                                          (d/db-with d [[:db/add obj-eid :object/tapped true]])))
                                      db-after-moves
                                      selected)
                              db-after-moves)]
        ;; Shuffle ONCE after all moves (anti-pattern: NO shuffling between moves)
        (if should-shuffle?
          (zones/shuffle-library db-after-tapped player-id)
          db-after-tapped)))))


(defn execute-peek-selection
  "Execute peek selection - move selected to hand, rest to bottom of library.
   Pure function: (db, selection) -> db

   Arguments:
     db - Datascript game database (not app-db)
     selection - Peek selection state with:
       :selection/selected - Set of object-ids going to hand
       :selection/candidates - Set of all object-ids that were peeked
       :selection/selected-zone - Zone for selected cards (typically :hand)
       :selection/remainder-zone - Zone for non-selected (typically :bottom-of-library)
       :selection/shuffle-remainder? - Whether to randomize remainder order
       :selection/player-id - Player performing the peek

   Handles:
     - Empty selection (fail-to-find): All candidates go to bottom
     - Cards selected: Selected go to hand, rest go to bottom
     - Remainder shuffling: If shuffle-remainder? true, randomize bottom order"
  [db selection]
  (let [selected (:selection/selected selection)
        candidates (:selection/candidates selection)
        remainder (clojure.set/difference candidates selected)
        selected-zone (or (:selection/selected-zone selection) :hand)
        player-id (:selection/player-id selection)
        shuffle-remainder? (:selection/shuffle-remainder? selection)
        ;; Move selected cards to hand
        db-after-selected (reduce (fn [d card-id]
                                    (zones/move-to-zone d card-id selected-zone))
                                  db
                                  selected)
        ;; Get max position in library to put remainder at bottom
        ;; Cards in library after removing selected ones
        library-objs (queries/get-objects-in-zone db-after-selected player-id :library)
        max-pos (if (seq library-objs)
                  (apply max (map :object/position library-objs))
                  -1)
        ;; Order remainder cards (shuffle if requested, otherwise keep original order)
        remainder-seq (if shuffle-remainder?
                        (shuffle (seq remainder))
                        (seq remainder))
        ;; Assign new positions to remainder cards starting after max-pos
        db-after-remainder (reduce-kv
                             (fn [d idx card-id]
                               (let [obj-eid (d/q '[:find ?e .
                                                    :in $ ?oid
                                                    :where [?e :object/id ?oid]]
                                                  d card-id)
                                     new-pos (+ max-pos 1 idx)]
                                 (d/db-with d [[:db/add obj-eid :object/position new-pos]])))
                             db-after-selected
                             (vec remainder-seq))]
    db-after-remainder))


(defn execute-pile-choice
  "Execute pile choice - move selected cards to hand, rest to graveyard.
   Pure function: (db, selection) -> db

   Arguments:
     db - Datascript game database (not app-db)
     selection - Pile choice selection state with:
       :selection/selected - Set of object-ids going to hand
       :selection/candidates - Set of all object-ids in pile choice

   Note: Does NOT shuffle library. Shuffle happens in confirm-pile-choice-selection
   after all pile choice operations are complete."
  [db selection]
  (let [hand-selected (:selection/selected selection)
        all-candidates (:selection/candidates selection)
        graveyard-cards (clojure.set/difference all-candidates hand-selected)
        ;; Move selected cards to hand
        db-after-hand (reduce (fn [d card-id]
                                (zones/move-to-zone d card-id :hand))
                              db
                              hand-selected)
        ;; Move remaining to graveyard
        db-after-graveyard (reduce (fn [d card-id]
                                     (zones/move-to-zone d card-id :graveyard))
                                   db-after-hand
                                   graveyard-cards)]
    db-after-graveyard))


(rf/reg-event-db
  ::confirm-tutor-selection
  (fn [db _]
    (let [selection (:game/pending-selection db)
          selected (:selection/selected selection)
          candidates (:selection/candidates selection)
          select-count (or (:selection/select-count selection) 1)
          spell-id (:selection/spell-id selection)
          trigger-id (:selection/trigger-id selection)
          source-type (:selection/source-type selection)
          remaining-effects (:selection/remaining-effects selection)
          player-id (:selection/player-id selection)
          pile-choice (:selection/pile-choice selection)
          game-db (:game/db db)
          ;; Validate: empty (fail-to-find) OR correct count of valid candidates
          valid-selection? (or (empty? selected)
                               (and (= (count selected) select-count)
                                    (every? #(contains? candidates %) selected)))]
      (if valid-selection?
        ;; Valid tutor selection - check if pile-choice needed
        (if (and pile-choice (seq selected))
          ;; Pile-choice present and cards selected: chain to pile-choice phase
          ;; Cards stay in library, spell stays on stack until pile-choice confirms
          (let [pile-selection (build-pile-choice-selection
                                 selected
                                 pile-choice
                                 player-id
                                 spell-id
                                 remaining-effects)]
            (assoc db :game/pending-selection pile-selection))
          ;; No pile-choice OR fail-to-find: normal tutor flow
          (let [db-after-tutor (execute-tutor-selection game-db selection)
                ;; Execute remaining effects
                db-after-remaining (reduce (fn [d effect]
                                             (effects/execute-effect d player-id effect))
                                           db-after-tutor
                                           (or remaining-effects []))
                ;; Clean up source: move spell to destination OR remove trigger from stack
                ;; BUT only if the spell is still on the stack (effects like :exile-self may have moved it)
                db-final (if (= source-type :trigger)
                           ;; Trigger-based selection (activated ability): remove trigger
                           (triggers/remove-trigger db-after-remaining trigger-id)
                           ;; Spell-based selection: use cast mode's destination (e.g., :exile for flashback)
                           (let [spell-obj (queries/get-object db-after-remaining spell-id)
                                 current-zone (:object/zone spell-obj)]
                             ;; Only move if still on stack (effects like :exile-self may have already moved it)
                             (if (= current-zone :stack)
                               (let [cast-mode (:object/cast-mode spell-obj)
                                     destination (or (:mode/on-resolve cast-mode) :graveyard)]
                                 (zones/move-to-zone db-after-remaining spell-id destination))
                               db-after-remaining)))]
            (-> db
                (assoc :game/db db-final)
                (dissoc :game/pending-selection))))
        ;; Invalid selection - do nothing
        db))))


;; === Pile Choice Selection ===
;; For Intuition-style effects where player distributes cards to hand/graveyard

(rf/reg-event-db
  ::select-random-pile-choice
  (fn [db _]
    (select-random-pile-choice db)))


(rf/reg-event-db
  ::confirm-pile-choice-selection
  (fn [db _]
    (let [selection (:game/pending-selection db)
          hand-count (:selection/hand-count selection)
          selected (:selection/selected selection)
          candidates (:selection/candidates selection)
          player-id (:selection/player-id selection)
          spell-id (:selection/spell-id selection)
          remaining-effects (:selection/remaining-effects selection)
          game-db (:game/db db)
          ;; Validate: correct number selected and all are candidates
          valid? (and (= hand-count (count selected))
                      (every? #(contains? candidates %) selected))]
      (if valid?
        (let [;; Execute pile choice: move cards to hand/graveyard
              db-after-pile (execute-pile-choice game-db selection)
              ;; Shuffle library AFTER pile choice (tutor requirement)
              db-after-shuffle (zones/shuffle-library db-after-pile player-id)
              ;; Execute remaining effects
              db-after-effects (reduce (fn [d effect]
                                         (effects/execute-effect d player-id effect))
                                       db-after-shuffle
                                       (or remaining-effects []))
              ;; Move spell to graveyard (or exile for flashback)
              ;; BUT only if the spell is still on the stack (effects like :exile-self may have moved it)
              spell-obj (queries/get-object db-after-effects spell-id)
              current-zone (:object/zone spell-obj)
              db-final (if (= current-zone :stack)
                         (let [cast-mode (:object/cast-mode spell-obj)
                               destination (or (:mode/on-resolve cast-mode) :graveyard)]
                           (zones/move-to-zone db-after-effects spell-id destination))
                         db-after-effects)]
          (-> db
              (assoc :game/db db-final)
              (dissoc :game/pending-selection)))
        ;; Invalid - do nothing
        db))))


;; === Scry Selection ===
;; For scry effects that reveal top N cards for rearrangement

(defn- reorder-library-for-scry
  "Reorder library after scry confirmation.
   top-pile cards go to positions 0, 1, 2... (click order)
   bottom-pile cards go to last positions (click order)
   Unassigned cards from selection stay at original relative positions.
   Cards not in scry selection are unaffected."
  [db player-id selection]
  (let [scry-card-ids (set (:selection/cards selection))
        top-pile (vec (or (:selection/top-pile selection) []))
        bottom-pile (vec (or (:selection/bottom-pile selection) []))
        assigned-ids (into (set top-pile) bottom-pile)
        ;; Get all library objects sorted by current position
        library-objs (->> (queries/get-objects-in-zone db player-id :library)
                          (sort-by :object/position))
        ;; Separate scry cards from non-scry cards
        non-scry-objs (remove #(scry-card-ids (:object/id %)) library-objs)
        ;; Cards in selection but not assigned to either pile (keep at top)
        unassigned (filterv #(and (scry-card-ids %)
                                  (not (assigned-ids %)))
                            (map :object/id library-objs))
        ;; Build new position order:
        ;; 1. Top pile (click order)
        ;; 2. Unassigned scry cards (original relative order)
        ;; 3. Non-scry cards (original positions preserved)
        ;; 4. Bottom pile (click order)
        new-order (vec (concat top-pile
                               unassigned
                               (map :object/id non-scry-objs)
                               bottom-pile))
        ;; Build position update transactions
        position-txs (map-indexed
                       (fn [idx obj-id]
                         (let [obj-eid (d/q '[:find ?e .
                                              :in $ ?oid
                                              :where [?e :object/id ?oid]]
                                            db obj-id)]
                           (when obj-eid
                             [:db/add obj-eid :object/position idx])))
                       new-order)]
    (d/db-with db (filterv some? position-txs))))


(defn confirm-scry-selection
  "Handle scry selection confirmation.
   Reorders library, executes remaining effects, moves spell to graveyard.
   Exported for testing."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        spell-id (:selection/spell-id selection)
        remaining-effects (:selection/remaining-effects selection)
        player-id (:selection/player-id selection)
        game-db (:game/db app-db)]
    (if (= :scry (:selection/type selection))
      (let [;; Reorder library based on pile assignments
            db-after-reorder (reorder-library-for-scry game-db player-id selection)
            ;; Execute remaining effects (e.g., draw for Opt)
            db-after-remaining (reduce (fn [d effect]
                                         (effects/execute-effect d player-id effect))
                                       db-after-reorder
                                       (or remaining-effects []))
            ;; Move spell to graveyard
            db-final (zones/move-to-zone db-after-remaining spell-id :graveyard)]
        (-> app-db
            (assoc :game/db db-final)
            (dissoc :game/pending-selection)))
      ;; Not a scry selection - do nothing
      app-db)))


(rf/reg-event-db
  ::confirm-scry-selection
  (fn [db _]
    (confirm-scry-selection db)))


;; === Scry Pile Assignment Events ===
;; Events for assigning cards to top/bottom piles during scry selection

(rf/reg-event-db
  ::scry-assign-top
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/top-pile] conj obj-id)
        (update-in [:game/pending-selection :selection/bottom-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


(rf/reg-event-db
  ::scry-assign-bottom
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/bottom-pile] conj obj-id)
        (update-in [:game/pending-selection :selection/top-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


(rf/reg-event-db
  ::scry-unassign
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/top-pile]
                   (fn [pile] (vec (remove #{obj-id} pile))))
        (update-in [:game/pending-selection :selection/bottom-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


;; === Player Target Selection ===
;; For effects with :effect/target :any-player (e.g., Deep Analysis "target player draws")

(rf/reg-event-db
  ::select-player-target
  (fn [db [_ target-player-id]]
    (assoc-in db [:game/pending-selection :selection/selected-target] target-player-id)))


(rf/reg-event-db
  ::confirm-player-target-selection
  (fn [db _]
    (let [selection (:game/pending-selection db)
          selected-target (:selection/selected-target selection)
          spell-id (:selection/spell-id selection)
          trigger-id (:selection/trigger-id selection)
          source-type (:selection/source-type selection)
          target-effect (:selection/target-effect selection)
          remaining-effects (vec (or (:selection/remaining-effects selection) []))
          player-id (:selection/player-id selection)
          game-db (:game/db db)]
      (if selected-target
        ;; Valid target - execute effect with resolved target
        (let [;; Replace :any-player with selected target
              resolved-effect (assoc target-effect :effect/target selected-target)
              ;; Execute the targeted effect
              db-after-effect (effects/execute-effect game-db player-id resolved-effect)
              ;; Check if remaining effects need selection
              next-selection-idx (find-selection-effect-index remaining-effects)]
          (if next-selection-idx
            ;; Chain to next selection (e.g., discard after draw)
            (let [effects-before-next (subvec remaining-effects 0 next-selection-idx)
                  next-selection-effect (nth remaining-effects next-selection-idx)
                  effects-after-next (subvec remaining-effects (inc next-selection-idx))
                  ;; Execute effects before the next selection
                  db-after-before (reduce (fn [d effect]
                                            (effects/execute-effect d player-id effect))
                                          db-after-effect
                                          effects-before-next)
                  ;; Build selection state for discard (targeting the same player)
                  ;; The selected-target becomes the player who must discard
                  next-selection (when (= :player (:effect/selection next-selection-effect))
                                   {:selection/type :discard
                                    :selection/player-id selected-target  ; Target player discards
                                    :selection/count (:effect/count next-selection-effect)
                                    :selection/selected #{}
                                    :selection/spell-id spell-id
                                    :selection/trigger-id trigger-id
                                    :selection/source-type source-type
                                    :selection/remaining-effects effects-after-next
                                    :selection/effect-type :discard})]
              (-> db
                  (assoc :game/db db-after-before)
                  (assoc :game/pending-selection next-selection)))
            ;; No more selections - execute all remaining and clean up
            (let [db-after-remaining (reduce (fn [d effect]
                                               (effects/execute-effect d player-id effect))
                                             db-after-effect
                                             remaining-effects)
                  ;; Clean up source: move spell to destination OR remove trigger from stack
                  db-final (if (= source-type :trigger)
                             ;; Trigger-based selection (activated ability): remove trigger
                             (triggers/remove-trigger db-after-remaining trigger-id)
                             ;; Spell-based selection: use cast mode's destination (e.g., :exile for flashback)
                             (let [spell-obj (queries/get-object db-after-remaining spell-id)
                                   cast-mode (:object/cast-mode spell-obj)
                                   destination (or (:mode/on-resolve cast-mode) :graveyard)]
                               (zones/move-to-zone db-after-remaining spell-id destination)))]
              (-> db
                  (assoc :game/db db-final)
                  (dissoc :game/pending-selection)))))
        ;; No target selected - do nothing
        db))))


;; === Mode Selection System ===
;; For spells with multiple valid casting modes from the same zone
;; (e.g., a Gush-like card with primary cost and alternate cost from hand)

(rf/reg-event-db
  ::select-casting-mode
  (fn [db [_ mode]]
    (let [game-db (:game/db db)
          pending (:game/pending-mode-selection db)
          object-id (:object-id pending)]
      (if (and pending object-id mode)
        ;; Check for targeting requirements
        (let [obj (queries/get-object game-db object-id)
              card (:object/card obj)
              targeting-reqs (targeting/get-targeting-requirements card)]
          (if (seq targeting-reqs)
            ;; Has targeting - prompt for targets before casting
            (let [first-req (first targeting-reqs)
                  selection (build-cast-time-target-selection game-db :player-1 object-id mode first-req)]
              (-> db
                  (assoc :game/pending-selection selection)
                  (dissoc :game/selected-card)
                  (dissoc :game/pending-mode-selection)))
            ;; No targeting - cast immediately
            (-> db
                (assoc :game/db (rules/cast-spell-mode game-db :player-1 object-id mode))
                (dissoc :game/selected-card)
                (dissoc :game/pending-mode-selection))))
        db))))


(rf/reg-event-db
  ::cancel-mode-selection
  (fn [db _]
    (dissoc db :game/pending-mode-selection)))


;; === Cast-Time Targeting System ===
;; For spells with :card/targeting that require target selection BEFORE going on stack

(defn- build-cast-time-target-selection
  "Build pending selection state for cast-time targeting.
   Used when a spell has :card/targeting requirements.

   Arguments:
     db - Datascript database
     player-id - Casting player
     object-id - Spell being cast
     mode - Casting mode being used
     target-req - First targeting requirement (player target for now)

   Returns selection state map."
  [db player-id object-id mode target-req]
  (let [valid-targets (targeting/find-valid-targets db player-id target-req)]
    {:selection/type :cast-time-targeting
     :selection/player-id player-id
     :selection/object-id object-id
     :selection/mode mode
     :selection/target-requirement target-req
     :selection/valid-targets valid-targets
     :selection/selected-target nil}))


(defn cast-spell-with-targeting
  "Cast a spell, pausing for target selection if needed.

   For spells with :card/targeting:
   - Returns {:db db :pending-target-selection selection-state}
   - Spell does NOT go to stack yet
   - After target confirmed, call confirm-cast-time-target to complete

   For spells without targeting:
   - Returns {:db db :pending-target-selection nil}
   - Spell immediately goes to stack via rules/cast-spell

   Arguments:
     db - Datascript database
     player-id - Casting player
     object-id - Object to cast"
  [db player-id object-id]
  (let [obj (queries/get-object db object-id)
        card (:object/card obj)
        targeting-reqs (targeting/get-targeting-requirements card)
        modes (rules/get-casting-modes db player-id object-id)
        ;; Pick best mode (primary if available, else first)
        primary (first (filter #(= :primary (:mode/id %)) modes))
        mode (or primary (first modes))]
    (if (and (seq targeting-reqs)
             (rules/can-cast-mode? db player-id object-id mode))
      ;; Has targeting - pause for target selection
      (let [first-req (first targeting-reqs)
            selection (build-cast-time-target-selection db player-id object-id mode first-req)]
        {:db db
         :pending-target-selection selection})
      ;; No targeting - cast normally
      {:db (if (rules/can-cast? db player-id object-id)
             (rules/cast-spell db player-id object-id)
             db)
       :pending-target-selection nil})))


(defn confirm-cast-time-target
  "Complete casting a spell after target selection.

   1. Pays mana and additional costs
   2. Moves spell to stack
   3. Stores the selected target on the object as :object/targets

   Arguments:
     db - Datascript database
     selection - Cast-time target selection state with :selection/selected-target set

   Returns updated db with spell on stack and target stored."
  [db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/object-id selection)
        mode (:selection/mode selection)
        target-req (:selection/target-requirement selection)
        selected-target (:selection/selected-target selection)
        target-id (:target/id target-req)]
    (if selected-target
      ;; Cast spell and store target
      (let [;; Cast via rules/cast-spell-mode (pays costs, moves to stack)
            db-after-cast (rules/cast-spell-mode db player-id object-id mode)
            ;; Store the target on the object
            obj-eid (d/q '[:find ?e .
                           :in $ ?oid
                           :where [?e :object/id ?oid]]
                         db-after-cast object-id)
            db-with-target (d/db-with db-after-cast
                                      [[:db/add obj-eid :object/targets {target-id selected-target}]])]
        db-with-target)
      ;; No target selected - return unchanged
      db)))
