(ns fizzle.db.schema
  "Datascript schema for Fizzle game state.

   Entities:
   - Cards: Template definitions (immutable during game)
   - Objects: Instances of cards in a game (move between zones)
   - Players: Player state including mana pool and storm count
   - Game: Global game state (turn, phase, active player)")


(def schema
  {;; === Card Definitions (templates) ===
   ;; Cards are the immutable definitions of what a card does.
   ;; Game objects reference cards to know their effects.
   :card/id         {:db/unique :db.unique/identity}
   :card/name       {:db/index true}
   :card/cmc        {}  ; Converted mana cost (integer)
   :card/mana-cost  {}  ; {:black 1 :generic 2} for 2B
   :card/colors     {:db/cardinality :db.cardinality/many}  ; #{:black :blue}
   :card/types      {:db/cardinality :db.cardinality/many}  ; #{:instant :sorcery}
   :card/subtypes   {:db/cardinality :db.cardinality/many}  ; #{:arcane}
   :card/supertypes {:db/cardinality :db.cardinality/many}  ; #{:legendary}
   :card/text       {}  ; Rules text (for display)
   :card/effects    {}  ; EDN vector of effect maps
   :card/abilities    {}  ; EDN for activated abilities
   :card/etb-effects  {}  ; EDN vector of effects to execute on entering battlefield
   :card/triggers     {}  ; EDN vector of triggered abilities {:trigger/type :becomes-tapped ...}
   :card/keywords     {:db/cardinality :db.cardinality/many}  ; #{:storm :threshold}

   ;; === Game Objects (instances) ===
   ;; Objects are instances of cards in a game.
   ;; They have position (zone), state (tapped), and identity.
   :object/id         {:db/unique :db.unique/identity}
   :object/card       {:db/valueType :db.type/ref}  ; Reference to card definition
   :object/zone       {}  ; :hand :stack :graveyard :battlefield :library :exile
   :object/owner      {:db/valueType :db.type/ref}  ; Player who owns this
   :object/controller {:db/valueType :db.type/ref}  ; Player who controls this
   :object/tapped     {}  ; Boolean
   :object/counters   {}  ; {:charge 3 :loyalty 4}
   :object/position   {}  ; Position in zone (for library ordering)
   :object/is-copy    {}  ; Boolean, marks spell copies (for storm, etc.)
   :object/grants     {}  ; Vector of grant maps (temporary abilities/costs)
   :object/x-value    {}  ; Integer, value of X for spells with X in cost
   :object/cast-mode  {}  ; Map storing the casting mode used (for flashback, etc.)
   :object/chosen-mode {}  ; Map storing the chosen spell mode for modal spells (REB, BEB, etc.)

   ;; === Players ===
   :player/id              {:db/unique :db.unique/identity}
   :player/name            {}
   :player/life            {}  ; Integer, typically starts at 20
   :player/mana-pool       {}  ; {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
   :player/storm-count     {}  ; Number of spells cast this turn
   :player/land-plays-left {}  ; Land plays remaining this turn
   :player/max-hand-size   {}  ; Maximum hand size (default 7, some effects modify)
   :player/is-opponent     {}  ; Boolean, for bot players
   :player/grants          {}  ; Vector of grant maps (temporary restrictions/effects)
   :player/bot-archetype   {}  ; :goldfish | :burn | nil
   :player/stops           {}  ; #{:main1 :main2 ...} — phases where player wants priority
   :player/drew-from-empty {}  ; Boolean, set by :draw when library is empty, cleared by SBA

   ;; === Game State (singleton) ===
   :game/id            {:db/unique :db.unique/identity}
   :game/turn          {}  ; Integer, current turn number
   :game/phase         {}  ; :main1 :main2 :combat :end
   :game/step          {}  ; :untap :upkeep :draw (within phases)
   :game/active-player {:db/valueType :db.type/ref}  ; Whose turn it is
   :game/priority      {:db/valueType :db.type/ref}  ; Who can act right now
   :game/winner        {:db/valueType :db.type/ref}  ; nil until game ends
   :game/loss-condition {}  ; Keyword like :empty-library, :life-zero when player loses
   :game/passed         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}  ; Players who have passed priority
   :game/auto-mode      {}  ; :resolving | :f6 | nil
   :game/human-player-id {}  ; :player-1 — explicit identity of the human player

   ;; === Stack Items (unified stack representation) ===
   ;; Stack items represent anything on the stack awaiting resolution.
   ;; Spells reference their game object; triggers/abilities have inline effects.
   :stack-item/position    {}  ; Integer, LIFO ordering (higher = resolves first)
   :stack-item/type        {}  ; Keyword: :spell, :storm-copy, :activated-ability, :etb, :permanent-tapped, :land-entered
   :stack-item/controller  {}  ; Player entity ID
   :stack-item/source      {}  ; Source object entity ID
   :stack-item/effects     {}  ; Vector of effect maps (EDN)
   :stack-item/targets     {}  ; Map of stored targeting choices
   :stack-item/description {}  ; String, human-readable for stack display
   :stack-item/is-copy     {}  ; Boolean, true for storm copies
   :stack-item/cast-mode   {}  ; Map, the casting mode used
   :stack-item/chosen-x    {}  ; Integer, value of X chosen during cost payment (e.g., pay X life)
   :stack-item/object-ref  {:db/valueType :db.type/ref}  ; Reference to game object entity (spells only)

   ;; === Trigger Entities (trigger registry in Datascript) ===
   ;; Triggers represent event-driven abilities stored as immutable DB values.
   ;; Component of source object -- auto-retracted when source is retracted (tokens).
   :trigger/event-type     {}              ; Keyword: :permanent-tapped, :zone-change, :card-cycled, :phase-entered
   :trigger/source         {:db/valueType :db.type/ref}  ; Ref to source object entity (nil for game-rule triggers)
   :trigger/controller     {:db/valueType :db.type/ref}  ; Ref to controlling player entity
   :trigger/filter         {}              ; EDN map: {:event/object-id :self}, {:event/phase :draw}, etc.
   :trigger/effects        {}              ; Vector of effect maps [{:effect/type :deal-damage :effect/amount 1}]
   :trigger/description    {}              ; String, human-readable for UI/log
   :trigger/uses-stack?    {}              ; Boolean (default true when nil)
   :trigger/always-active? {}              ; Boolean, true for game-rule triggers (no source zone check)
   :trigger/type           {}              ; Original trigger type keyword from card def (:becomes-tapped, :land-entered, :draw-step)
   :object/triggers        {:db/valueType   :db.type/ref
                            :db/cardinality :db.cardinality/many
                            :db/isComponent true}})
