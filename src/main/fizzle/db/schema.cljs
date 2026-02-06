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
   :object/targets    {}  ; Map of target-ref keyword → target-id (UUID or player keyword)
   :object/is-copy    {}  ; Boolean, marks spell copies (for storm, etc.)
   :object/grants     {}  ; Vector of grant maps (temporary abilities/costs)
   :object/x-value    {}  ; Integer, value of X for spells with X in cost
   :object/cast-mode  {}  ; Map storing the casting mode used (for flashback, etc.)

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

   ;; === Game State (singleton) ===
   :game/id            {:db/unique :db.unique/identity}
   :game/turn          {}  ; Integer, current turn number
   :game/phase         {}  ; :main1 :main2 :combat :end
   :game/step          {}  ; :untap :upkeep :draw (within phases)
   :game/active-player {:db/valueType :db.type/ref}  ; Whose turn it is
   :game/priority      {:db/valueType :db.type/ref}  ; Who can act right now
   :game/winner        {:db/valueType :db.type/ref}  ; nil until game ends
   :game/loss-condition {}  ; Keyword like :empty-library, :life-zero when player loses

   ;; === Triggers (triggered abilities on stack) ===
   ;; Triggers are objects on the stack that represent triggered abilities.
   ;; They resolve before the spell that created them (LIFO stack order).
   :trigger/id         {:db/unique :db.unique/identity}  ; UUID, unique identifier
   :trigger/type       {}  ; Keyword, e.g. :storm, :etb
   :trigger/source     {}  ; ID of source object (not ref, may be in graveyard)
   :trigger/controller {}  ; Player ID who controls this trigger
   :trigger/data       {}  ; Map of trigger-specific data
   :trigger/description {}  ; Human-readable description for stack display
   :trigger/stack-order {}})
