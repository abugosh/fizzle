(ns fizzle.history.descriptions
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.stack :as stack]))


(def ^:private phase-names
  "Human-readable names for game phases."
  {:untap   "Untap"
   :upkeep  "Upkeep"
   :draw    "Draw"
   :main1   "Main 1"
   :combat  "Combat"
   :main2   "Main 2"
   :end     "End"
   :cleanup "Cleanup"})


(defn- get-card-name
  "Look up a card name by object-id in a game-db. Returns nil on failure."
  [db object-id]
  (when (and db object-id)
    (try
      (let [obj (queries/get-object db object-id)]
        (get-in obj [:object/card :card/name]))
      (catch :default _ nil))))


(defn- get-stack-top-info
  "Get the top stack item from a game-db. Returns map or nil."
  [db]
  (when db
    (try
      (stack/get-top-stack-item db)
      (catch :default _ nil))))


(defn- get-target-info
  "Get targets map from the stack-item.
   Both spells and abilities store targets on :stack-item/targets."
  [stack-item _db]
  (:stack-item/targets stack-item))


(defn- format-target-suffix
  "Format target info from a targets map and controller.
   Returns ' targeting opponent', ' targeting self', or empty string."
  [targets controller]
  (if-let [target-player (:player targets)]
    (if (= target-player controller)
      " targeting self"
      " targeting opponent")
    ""))


(defn- describe-cast-spell
  "Describe a cast-spell event using game-db-after (spell is now on stack).
   Falls back to casting-spell-id when spell hasn't reached the stack yet
   (e.g., mid-chain through X cost → mana allocation)."
  ([game-db-after]
   (describe-cast-spell game-db-after nil))
  ([game-db-after casting-spell-id]
   (if-let [top (get-stack-top-info game-db-after)]
     (if-let [card-name (get-card-name game-db-after (:stack-item/source top))]
       (let [targets (get-target-info top game-db-after)]
         (str "Cast " card-name (format-target-suffix targets (:stack-item/controller top))))
       "Cast spell")
     ;; Spell not on stack yet — try casting-spell-id fallback
     (if-let [card-name (get-card-name game-db-after casting-spell-id)]
       (str "Cast " card-name)
       "Cast spell"))))


(defn- describe-cast-and-yield
  "Describe a cast-and-yield event. After this event, the spell is typically
   resolved (off the stack), so we use casting-spell-id or pre-game-db to
   find the card name. Falls back to game-db-after stack top if spell is
   still on the stack (e.g., when resolution was skipped due to selection)."
  [pre-game-db game-db-after casting-spell-id]
  (let [card-name (or (get-card-name pre-game-db casting-spell-id)
                      (get-card-name game-db-after casting-spell-id)
                      (when-let [top (get-stack-top-info game-db-after)]
                        (get-card-name game-db-after (:stack-item/source top))))]
    (if card-name
      (str "Cast & Yield " card-name)
      "Cast & Yield")))


(defn- describe-resolve-top
  "Describe a resolve-top event using pre-game-db (stack item about to resolve)."
  [pre-game-db]
  (if-let [top (get-stack-top-info pre-game-db)]
    (let [item-type (:stack-item/type top)]
      (case item-type
        (:spell :storm-copy)
        (if-let [card-name (get-card-name pre-game-db (:stack-item/source top))]
          (str "Resolve " card-name)
          "Resolve top of stack")

        :activated-ability
        (if-let [card-name (get-card-name pre-game-db (:stack-item/source top))]
          (str "Resolve " card-name " ability")
          "Resolve top of stack")

        :storm
        (if-let [desc (:stack-item/description top)]
          (str "Resolve " desc)
          "Resolve top of stack")

        ;; Triggers: :etb, :permanent-tapped, :land-entered
        (if-let [card-name (get-card-name pre-game-db (:stack-item/source top))]
          (str "Resolve " card-name " trigger")
          "Resolve top of stack")))
    "Resolve top of stack"))


(defn- describe-advance-phase
  "Describe an advance-phase event using game-db-after."
  [game-db-after]
  (if game-db-after
    (try
      (let [game-state (queries/get-game-state game-db-after)
            phase (:game/phase game-state)]
        (if-let [phase-name (get phase-names phase)]
          (str "Advance to " phase-name)
          "Advance phase"))
      (catch :default _ "Advance phase"))
    "Advance phase"))


(defn- describe-start-turn
  "Describe a start-turn event using game-db-after."
  [game-db-after]
  (if game-db-after
    (try
      (let [game-state (queries/get-game-state game-db-after)
            turn (:game/turn game-state)]
        (if turn
          (str "Start Turn " turn)
          "Start new turn"))
      (catch :default _ "Start new turn"))
    "Start new turn"))


(defn- describe-play-land
  "Describe a play-land event. Event args contain [_ object-id]."
  [object-id game-db-after]
  (if-let [card-name (get-card-name game-db-after object-id)]
    (str "Play " card-name)
    "Play land"))


(defn- format-mana-color
  "Format a mana color keyword for display. Returns nil for unknown colors."
  [color]
  (case color
    :white "W" :blue "U" :black "B" :red "R" :green "G" :colorless "C"
    nil))


(defn- describe-activate-mana
  "Describe an activate-mana-ability event. Event args contain [_ object-id color]."
  [object-id mana-color pre-game-db]
  (let [card-name (get-card-name pre-game-db object-id)
        color-str (format-mana-color mana-color)]
    (cond
      (and card-name color-str) (str "Tap " card-name " for " color-str)
      card-name (str "Tap " card-name)
      :else "Activate mana ability")))


(defn- describe-activate-ability
  "Describe an activate-ability event. Event args contain [_ object-id ability-index].
   Uses pre-game-db because source may be sacrificed as cost."
  [object-id ability-index pre-game-db]
  (if-let [card-name (get-card-name pre-game-db object-id)]
    (let [obj (try (queries/get-object pre-game-db object-id)
                   (catch :default _ nil))
          abilities (get-in obj [:object/card :card/abilities])]
      (if (> (count abilities) 1)
        ;; Multi-ability: show description for disambiguation
        (let [ability (nth abilities ability-index nil)
              desc (:ability/description ability)]
          (if desc
            (str "Activate " card-name ": " desc)
            (str "Activate " card-name)))
        (str "Activate " card-name)))
    "Activate ability"))


(defn- describe-activate-from-stack
  "Describe an ability activation using game-db-after (ability is now on stack).
   Used for confirm-ability-target where the source may have been sacrificed as cost."
  [game-db-after]
  (if-let [top (get-stack-top-info game-db-after)]
    (if-let [card-name (get-card-name game-db-after (:stack-item/source top))]
      (let [targets (get-target-info top game-db-after)
            suffix (format-target-suffix targets (:stack-item/controller top))]
        (str "Activate " card-name suffix))
      "Activate ability")
    "Activate ability"))


(defn describe-event
  "Generate a human-readable description for a re-frame event.
   Takes event vector [event-id & args], optional game-db snapshots,
   optional selection-type, and optional casting-spell-id.
   Returns string or nil.
   Only priority-action events need descriptions (the interceptor filters others)."
  ([[event-id & _args] pre-game-db game-db-after]
   (describe-event (into [event-id] _args) pre-game-db game-db-after nil nil))
  ([[event-id & _args] pre-game-db game-db-after selection-type]
   (describe-event (into [event-id] _args) pre-game-db game-db-after selection-type nil))
  ([[event-id & _args] pre-game-db game-db-after selection-type casting-spell-id]
   (case event-id
     :fizzle.events.game/init-game
     "Game started"

     :fizzle.events.game/cast-spell
     (describe-cast-spell game-db-after casting-spell-id)

     :fizzle.events.game/cast-and-yield
     (describe-cast-and-yield pre-game-db game-db-after casting-spell-id)

     :fizzle.events.game/resolve-top
     (describe-resolve-top pre-game-db)

     :fizzle.events.game/advance-phase
     (describe-advance-phase game-db-after)

     :fizzle.events.game/start-turn
     (describe-start-turn game-db-after)

     :fizzle.events.game/play-land
     (describe-play-land (first _args) game-db-after)

     :fizzle.events.abilities/activate-mana-ability
     (describe-activate-mana (first _args) (second _args) pre-game-db)

     :fizzle.events.abilities/activate-ability
     (describe-activate-ability (first _args) (second _args) pre-game-db)

     ;; Unified confirm-selection: description depends on selection type
     :fizzle.events.selection/confirm-selection
     (case selection-type
       (:cast-time-targeting :x-mana-cost :exile-cards-cost)
       (describe-cast-spell game-db-after casting-spell-id)

       :ability-targeting
       (describe-activate-from-stack game-db-after)

       nil)

     nil))
  ;; Backward-compatible 1-arity: no game-dbs available
  ([event]
   (describe-event event nil nil nil nil)))
