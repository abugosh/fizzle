# Fizzle

**Fizzle, fork, try again.**

A ClojureScript-based Magic: The Gathering combo deck practice tool with fork/replay capabilities, simplified opponent AI, and tactics training.

**Version:** 0.4.0
**Last Updated:** February 2026
**Target Format:** Premodern combo decks

> See [fizzle-roadmap.md](fizzle-roadmap.md) for the implementation roadmap.

---

```
    ╔═╗╦╔═╗╔═╗╦  ╔═╗
    ╠╣ ║╔═╝╔═╝║  ║╣ 
    ╚  ╩╚═╝╚═╝╩═╝╚═╝
    
    fizzle, fork, try again.
```

---

## Table of Contents

1. [Project Goals](#1-project-goals)
2. [User Stories](#2-user-stories)
3. [Technical Architecture](#3-technical-architecture)
4. [Data Models](#4-data-models)
5. [Game Engine](#5-game-engine)
6. [Card Effect System](#6-card-effect-system)
7. [Bot System](#7-bot-system)
8. [Fork/Replay System](#8-forkreplay-system)
9. [UI Components](#9-ui-components)
10. [Future: Persistence & Sharing](#10-future-persistence--sharing)
11. [Starter Deck: Iggy Pop](#11-starter-deck-iggy-pop)
12. [Testing Strategy](#12-testing-strategy)
13. [Design Retrospective](#13-design-retrospective) *(new)*

---

## 1. Project Goals

### Primary Goals

1. **Efficient combo practice** — Get more reps in less time than Cockatrice/MTGO
2. **Post-sideboard hand sculpting** — Start games with specific cards in hand (Flores technique)
3. **Fork and replay** — Branch decision trees after fizzling, try different lines
4. **Tactics training** — Save positions as puzzles, drill specific scenarios
5. **Simplified opponents** — Practice against archetypal disruption patterns

### Non-Goals (for v1)

- Full MTG rules engine (we simplify aggressively)
- Multiplayer or networked play
- Complete card database (only implement cards we need)
- Mobile-first design (desktop-first, responsive later)

### Design Philosophy

**Simplify ruthlessly.** We're not building MTGO. We're building a practice tool for combo players who already know the rules. If a rules interaction is rare or irrelevant to storm, we skip it.

**Data over code.** Cards are data. Effects are data. Game state is data. The engine interprets data; it doesn't encode card-specific logic.

**Immutability enables features.** Fork/replay is trivial when state is immutable. This is why we chose ClojureScript.

---

## 2. User Stories

### Core Practice Flow

```
As a storm player, I want to:
- Load my decklist and start goldfishing
- Mulligan with London mulligan rules
- Play through my combo turn with accurate mana/storm tracking
- See my graveyard (threshold matters for Cabal Ritual)
- Track floating mana across priority passes
```

### Hand Sculpting (Flores Technique)

```
As a player preparing for a tournament, I want to:
- Specify that my opening hand contains [LED, Cabal Ritual, Ill-Gotten Gains]
- Specify that 2 Orim's Chant are shuffled into my deck (post-board)
- Practice the specific scenarios I expect to face
```

### Fork/Replay

```
As a player who just fizzled, I want to:
- Rewind to the decision point where I chose to cast Intuition
- Try a different pile configuration
- Compare the outcomes of different lines
- Save interesting decision points for later review
```

### Tactics Training

```
As a player drilling specific scenarios, I want to:
- Save the current game state as a "puzzle"
- Name and tag puzzles (e.g., "IGG line vs 2 cards in hand")
- Load puzzles and practice from that position
- Track my success rate on specific puzzles
```

### Test Against Specific Cards

```
As a player practicing against disruption, I want to:
- Configure which hate cards the opponent has (Seal of Cleansing, Chalice, etc.)
- Specify the opponent's starting hand for deterministic scenarios
- Face a burn clock (20 Mountains + 40 Lightning Bolts) for time pressure
- Practice forcing through countermagic
- Set up and share specific test scenarios with preset configurations
```

---

## 3. Technical Architecture

### Stack

| Layer | Technology | Rationale |
|-------|------------|-----------|
| Build | shadow-cljs | Fast builds, excellent JS interop, hot reload |
| UI Framework | Reagent | React wrapper, hiccup syntax, simple |
| State Management | re-frame | Event sourcing built-in, time-travel debugging |
| Local Database | Datascript | In-memory Datalog, perfect for game state queries |
| Styling | Tailwind CSS | Utility-first, fast iteration |
| PWA | Workbox | Service worker tooling for offline support |

### Why This Stack?

**re-frame's event model is our action log.** Every game action dispatches an event. State is a pure reduction over events. This gives us fork/replay almost for free.

**Datascript enables declarative queries.** "What cards in hand can I cast?" is a query, not imperative code. As game state grows complex, queries stay simple.

**ClojureScript's immutability means fork is free.** Keeping a reference to an old state doesn't copy anything. Structural sharing handles it.

### Project Structure

```
fizzle/
├── deps.edn                    ; Clojure deps
├── shadow-cljs.edn             ; Build config
├── package.json                ; JS deps (tailwind, etc.)
├── Makefile                    ; Build commands (make test, make validate, etc.)
├── CLAUDE.md                   ; Claude Code project context
├── fizzle-design.md            ; This document
├── fizzle-roadmap.md           ; Implementation roadmap
├── docs/
│   └── adr/                    ; Architecture Decision Records
├── resources/
│   └── public/
│       ├── index.html
│       └── css/
├── src/
│   └── main/
│       └── fizzle/
│           ├── core.cljs           ; Entry point, 3-screen router, layout
│           │
│           ├── db/
│           │   ├── schema.cljs     ; Datascript schema (5 entity types)
│           │   └── queries.cljs    ; Common Datalog queries
│           │
│           ├── engine/             ; Pure game engine (17 files, ~3000 lines)
│           │   ├── rules.cljs      ; Casting system, modes, spell resolution
│           │   ├── effects.cljs    ; Effect interpreter (20+ effect types)
│           │   ├── mana.cljs       ; Mana pool (add, pay, X-cost support)
│           │   ├── stack.cljs      ; Unified stack-item entity (LIFO)
│           │   ├── zones.cljs      ; Zone transitions, shuffle, remove
│           │   ├── costs.cljs      ; Ability cost system (6 cost types)
│           │   ├── grants.cljs     ; Temporary grants (alternate costs, restrictions)
│           │   ├── abilities.cljs  ; Generic ability activation
│           │   ├── targeting.cljs  ; Target requirements and validation
│           │   ├── conditions.cljs ; Condition evaluators (threshold)
│           │   ├── triggers.cljs   ; Turn-based triggers, spell copy creation
│           │   ├── trigger_dispatch.cljs  ; Event dispatch routing
│           │   ├── trigger_registry.cljs  ; Dynamic trigger registration
│           │   ├── turn_based.cljs ; Turn-based action registration
│           │   ├── state_based.cljs; State-based action framework
│           │   ├── events.cljs     ; Event creation helpers
│           │   └── deck_parser.cljs; Deck text parser (Moxfield/MTGGoldfish)
│           │
│           ├── events/             ; re-frame event handlers
│           │   ├── game.cljs       ; Core game events (~770 lines)
│           │   ├── selection.cljs  ; Selection/choice events (~1540 lines)
│           │   ├── abilities.cljs  ; Ability activation events (~300 lines)
│           │   ├── opening_hand.cljs ; Mulligan flow
│           │   └── setup.cljs      ; Game setup, deck management
│           │
│           ├── subs/               ; re-frame subscriptions (60+)
│           │   ├── game.cljs       ; Game state queries
│           │   ├── history.cljs    ; Fork/replay subscriptions
│           │   ├── opening_hand.cljs ; Mulligan state
│           │   └── setup.cljs      ; Deck/preset queries
│           │
│           ├── cards/              ; Card definitions as EDN data
│           │   ├── iggy_pop.cljs   ; Core Iggy Pop deck (26 cards)
│           │   ├── cephalid_coliseum.cljs
│           │   ├── deep_analysis.cljs
│           │   ├── flash_of_insight.cljs
│           │   ├── ill_gotten_gains.cljs
│           │   ├── orims_chant.cljs
│           │   ├── ray_of_revelation.cljs
│           │   ├── recoup.cljs
│           │   └── seal_of_cleansing.cljs
│           │
│           ├── history/            ; Fork/replay system
│           │   ├── core.cljs       ; Fork tree, branch operations
│           │   ├── descriptions.cljs ; Human-readable event descriptions
│           │   ├── events.cljs     ; Step navigation events
│           │   └── interceptor.cljs; re-frame interceptor for auto-tracking
│           │
│           └── views/              ; Reagent components (14 files)
│               ├── setup.cljs      ; Deck selection, hand sculpting
│               ├── import_modal.cljs ; Deck text import UI
│               ├── opening_hand.cljs ; London mulligan interface
│               ├── hand.cljs       ; Hand display with selection
│               ├── battlefield.cljs; Permanents with mana/activated abilities
│               ├── graveyard.cljs  ; Graveyard with threshold indicator
│               ├── stack.cljs      ; Stack item display
│               ├── mana_pool.cljs  ; Mana pool with color symbols
│               ├── opponent.cljs   ; Opponent life display
│               ├── controls.cljs   ; Cast, Play Land, Resolve buttons
│               ├── phase_bar.cljs  ; Turn/phase indicators
│               ├── history.cljs    ; Action log, fork controls
│               ├── modals.cljs     ; Modal system (10+ modal types)
│               └── common.cljs     ; Shared helpers (collapsible zones)
│
└── src/
    └── test/
        └── fizzle/                 ; 72 test files, 872+ tests
            ├── cards/              ; Card-specific tests (22 files)
            ├── engine/             ; Engine unit tests (22 files)
            ├── events/             ; Event handler tests (16 files)
            ├── history/            ; Fork/replay tests (5 files)
            ├── subs/               ; Subscription tests (4 files)
            ├── views/              ; Component tests (3 files)
            └── db/                 ; Schema tests (2 files)
```

---

## 4. Data Models

### Datascript Schema

```clojure
(def schema
  {;; === Cards (definitions, immutable templates) ===
   :card/id           {:db/unique :db.unique/identity}
   :card/name         {:db/index true}
   :card/cmc          {}
   :card/mana-cost    {}  ; Map: {:black 1 :generic 2}
   :card/colors       {:db/cardinality :db.cardinality/many}
   :card/types        {:db/cardinality :db.cardinality/many}
   :card/subtypes     {:db/cardinality :db.cardinality/many}
   :card/supertypes   {:db/cardinality :db.cardinality/many}
   :card/text         {}
   :card/effects      {}  ; EDN vector of effect maps
   :card/abilities    {}  ; EDN vector of activated abilities
   :card/etb-effects  {}  ; Effects on entering battlefield
   :card/triggers     {}  ; EDN vector of triggered abilities
   :card/keywords     {:db/cardinality :db.cardinality/many}
   :card/targeting    {}  ; Cast-time targeting requirements
   :card/alternate-costs {}  ; Flashback, etc.
   :card/conditional-effects {}  ; Map by condition: {:threshold -> [effects]}
   :card/kicker       {}  ; Kicker cost map
   :card/kicked-effects {}  ; Effects when kicked

   ;; === Game Objects (instances of cards in a game) ===
   :object/id         {:db/unique :db.unique/identity}
   :object/card       {:db/valueType :db.type/ref}
   :object/zone       {}  ; :library, :hand, :battlefield, :graveyard, :stack, :exile
   :object/owner      {:db/valueType :db.type/ref}
   :object/controller {:db/valueType :db.type/ref}
   :object/tapped     {}
   :object/counters   {}  ; Map of counter-type -> count
   :object/position   {}  ; Position in zone (for library order)
   :object/targets    {}  ; Map: target-ref-id -> target-id
   :object/is-copy    {}  ; Boolean, marks storm copies
   :object/grants     {}  ; Vector of grant maps (temporary abilities)
   :object/x-value    {}  ; Value of X for X spells
   :object/cast-mode  {}  ; Casting mode used (:flashback, :kicker)

   ;; === Players ===
   :player/id         {:db/unique :db.unique/identity}
   :player/name       {}
   :player/life       {}
   :player/is-opponent {}
   :player/mana-pool  {}  ; Map: {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
   :player/storm-count {}
   :player/land-plays-left {}
   :player/max-hand-size {}  ; Default 7
   :player/grants     {}  ; Vector of temporary restrictions/effects

   ;; === Game State (singleton) ===
   :game/id           {:db/unique :db.unique/identity}
   :game/turn         {}
   :game/phase        {}  ; :main1, :main2, :combat, :end
   :game/step         {}  ; :untap, :upkeep, :draw, etc.
   :game/active-player {:db/valueType :db.type/ref}
   :game/priority     {:db/valueType :db.type/ref}
   :game/winner       {:db/valueType :db.type/ref}
   :game/loss-condition {}  ; :empty-library, :life-zero

   ;; === Stack Items (unified representation) ===
   :stack-item/position    {}  ; Integer, LIFO ordering (higher = resolves first)
   :stack-item/type        {}  ; :spell, :storm-copy, :activated-ability, :etb, etc.
   :stack-item/controller  {:db/valueType :db.type/ref}
   :stack-item/source      {}  ; Source object entity ID
   :stack-item/effects     {}  ; Vector of effect maps
   :stack-item/targets     {}  ; Targeting choices
   :stack-item/description {}  ; Human-readable text
   :stack-item/is-copy     {}  ; Boolean, marks storm copies
   :stack-item/cast-mode   {}  ; Casting mode used
   :stack-item/object-ref  {:db/valueType :db.type/ref}})  ; For spells
```

**Key design decisions in the schema:**

- **Dual representation**: Cards are immutable templates; Objects are game instances referencing their card.
- **Unified stack-item**: A single entity type handles spells, storm copies, triggered abilities, and activated abilities. This simplified the stack engine significantly compared to the original design which had separate types.
- **Grants system**: Temporary modifiers (alternate costs, restrictions, keywords) stored as grant vectors on objects and players, with turn/phase expiration.
- **Targets as maps**: Changed from cardinality-many refs to a map of `{target-ref-id -> target-id}`, enabling named target slots (e.g., `:target-player`, `:target-object`).

### Card Definition Format (EDN)

Cards are defined as pure data. The engine interprets this data to execute game actions.

```clojure
;; Example: Dark Ritual
{:card/id :dark-ritual
 :card/name "Dark Ritual"
 :card/cmc 1
 :card/colors #{:black}
 :card/types #{:instant}
 :card/text "Add {B}{B}{B}."
 :card/effects [{:effect/type :add-mana
                 :effect/mana {:black 3}}]}

;; Example: Cabal Ritual
{:card/id :cabal-ritual
 :card/name "Cabal Ritual"
 :card/cmc 2
 :card/colors #{:black}
 :card/types #{:instant}
 :card/text "Add {B}{B}{B}. Threshold — Add {B}{B}{B}{B}{B} instead."
 :card/effects [{:effect/type :add-mana
                 :effect/mana {:black 3}
                 :effect/condition {:condition/type :threshold-not-met}}
                {:effect/type :add-mana
                 :effect/mana {:black 5}
                 :effect/condition {:condition/type :threshold}}]}

;; Example: Lion's Eye Diamond
{:card/id :lions-eye-diamond
 :card/name "Lion's Eye Diamond"
 :card/cmc 0
 :card/types #{:artifact}
 :card/text "Sacrifice LED, Discard your hand: Add three mana of any one color."
 :card/abilities [{:ability/type :activated
                   :ability/timing :mana-ability
                   :ability/cost {:cost/sacrifice :self
                                  :cost/discard-hand true}
                   :ability/effects [{:effect/type :add-mana
                                      :effect/mana {:choice 3}
                                      :effect/choice-type :one-color}]}]}

;; Example: Ill-Gotten Gains
{:card/id :ill-gotten-gains
 :card/name "Ill-Gotten Gains"
 :card/cmc 4
 :card/colors #{:black}
 :card/types #{:sorcery}
 :card/text "Exile IGG. Each player discards hand, then returns up to 3 cards from GY to hand."
 :card/effects [{:effect/type :exile-self}
                {:effect/type :each-player
                 :effect/player-effects [{:effect/type :discard-hand}
                                         {:effect/type :return-from-graveyard
                                          :effect/count {:up-to 3}
                                          :effect/choice :player}]}]}

;; Example: Brain Freeze
{:card/id :brain-freeze
 :card/name "Brain Freeze"
 :card/cmc 2
 :card/colors #{:blue}
 :card/types #{:instant}
 :card/keywords #{:storm}
 :card/text "Target player mills 3. Storm."
 :card/effects [{:effect/type :mill
                 :effect/count 3
                 :effect/target :chosen-player}]}
```

### Mana Representation

```clojure
;; Mana pool is a map
{:white 0
 :blue 2
 :black 5
 :red 0
 :green 0
 :colorless 0}

;; Mana costs use the same format with :generic for generic mana
{:black 2 :generic 2}  ; 2BB

;; For choices (e.g., LED "any one color"), we use:
{:choice 3 :choice-type :one-color}
```

### Game State Snapshots (for fork/replay)

Snapshots are stored as part of history entries (see Section 8). Each entry captures the complete Datascript db value after the action, enabling instant rewind to any point without event replay.

---

## 5. Game Engine

### Simplified Rules

We implement a subset of MTG rules sufficient for goldfishing storm:

#### Phases & Steps (Simplified)

```
Turn Structure:
├── Beginning Phase
│   ├── Untap (automatic)
│   ├── Upkeep (trigger check)
│   └── Draw (automatic, skipped turn 1)
├── First Main Phase
│   └── [PRIMARY PLAY AREA - most actions happen here]
├── Combat Phase (simplified - just attack declaration)
├── Second Main Phase
└── End Phase
    └── Cleanup (discard to 7, clear mana pool)
```

**Simplifications:**
- No combat damage calculation (creatures just deal their power)
- No blocking (opponent creatures always get through, we just track life)
- No instants during combat (main phase focus)
- Mana abilities don't use the stack

#### The Stack (Unified Stack-Item Model)

The stack uses a single `:stack-item` entity type for all entries, with a `:stack-item/type` discriminator:

```clojure
;; Stack-item types:
;; :spell          - Cast from hand (or flashback from graveyard)
;; :storm-copy     - Storm copies
;; :activated-ability - Permanent abilities (Seal of Cleansing, etc.)
;; :etb            - Enter-the-battlefield triggers
;; :permanent-tapped - Tap triggers (City of Brass damage)
;; :land-entered   - Land enters triggers (City of Traitors sacrifice)

;; Resolution: LIFO via :stack-item/position (highest resolves first)
;; Storm creates stack-items on cast with {:effect/type :storm-copies}
;; Copies have :stack-item/is-copy true and don't re-trigger storm

;; Priority system is implemented:
;; - Yield: resolve one, pass priority
;; - Yield-all: resolve entire stack
;; - Phase stops for untap/cleanup (no player actions)
;; - Bots auto-pass or act based on heuristics
;;
;; We still skip:
;; - Split second
;; - Replacement effects
;; - Layers / continuous effects
```

#### Zones

```
Zones:
├── library     ; Ordered, face-down
├── hand        ; Private to player
├── battlefield ; Permanents in play
├── graveyard   ; Public, ordered
├── stack       ; Spells being cast
└── exile       ; Removed from game
```

### Core Engine Functions

The engine is split across 24 files in `engine/` (~4000 lines). Key patterns:

```clojure
;; engine/rules.cljs — Casting system
;;
;; get-casting-modes: Returns all valid modes (primary + alternate + kicked + granted)
;; can-cast?: Checks timing, restrictions, costs, targets, zone
;; cast-spell: Pay mana/additional costs, move to stack, create stack-item, storm
;; resolve-spell: Execute effects, handle copies, place destination zone
;; get-active-effects: Select effects by mode → conditional → default

;; engine/effects.cljs — Effect interpreter (multimethod on :effect/type)
;; 20+ effect types, each (db, context, effect) -> db
;; Effects that need player choices return db unchanged;
;; the event layer (events/selection.cljs) handles the selection UI

;; engine/mana.cljs — Mana pool
;; add-mana, can-pay? (with X-cost support), pay-mana, empty-pool
;; Generic mana paid from largest available pools in descending order

;; engine/stack.cljs — Unified stack-item entity
;; create-stack-item, get-top-stack-item, remove-stack-item, stack-empty?
;; resolve-effect-target: resolves symbolic targets (:self, :controller, etc.)

;; engine/costs.cljs — Ability cost system
;; Multimethod: pay-cost, can-pay? dispatch on cost type
;; Types: :tap, :remove-counter, :sacrifice-self, :discard-hand, :pay-life, :mana

;; engine/grants.cljs — Temporary modifiers
;; Grant types: :alternate-cost, :ability, :keyword, :static-effect, :restriction
;; Grants expire at turn/phase boundaries (checked in cleanup)

;; engine/targeting.cljs — Target validation
;; get-targeting-requirements, find-valid-targets, has-valid-targets?
;; target-still-legal? (for fizzle check on resolution)
```

### Effect Interpreter

The effect system uses a multimethod `execute-effect-impl` dispatching on `:effect/type`. Each method receives `(db context effect)` where context includes the source object and controller information.

Effects that require player interaction (tutor, discard choices, graveyard return) set up a `:pending-selection` in app-db. The event layer (`events/selection.cljs`) presents the appropriate modal, collects the player's choice, and resumes effect execution with the remaining effects.

```clojure
;; Condition checking is separate (engine/conditions.cljs)
;; threshold?: (>= graveyard-count 7)
;; Conditional effects on cards use :card/conditional-effects map
;; e.g., Cabal Ritual: {:threshold [{:effect/type :add-mana :effect/mana {:black 5}}]}
;; Engine selects conditional or default effects via get-active-effects
```

---

## 6. Card Effect System

### Effect Types

| Effect Type | Parameters | Description |
|-------------|------------|-------------|
| `:add-mana` | `:mana`, `:condition` | Add mana to pool |
| `:draw` | `:count` | Draw cards (loss on empty library) |
| `:discard` | `:count`, `:choice` | Discard cards (player selects or random) |
| `:discard-hand` | — | Discard entire hand |
| `:mill` | `:count`, `:target` | Mill cards to graveyard |
| `:tutor` | `:zone`, `:destination`, `:filter`, `:reveal` | Search library (selection UI) |
| `:return-from-graveyard` | `:count`, `:destination` | Return cards from GY (selection UI) |
| `:exile-self` | — | Exile the source spell |
| `:sacrifice` | `:target` | Sacrifice permanent to graveyard |
| `:destroy` | `:target`, `:target-ref` | Destroy target permanent |
| `:lose-life` | `:amount`, `:target` | Reduce life total (auto-loss at ≤0) |
| `:gain-life` | `:amount`, `:target` | Increase life total |
| `:deal-damage` | `:amount`, `:target` | Deal damage with loss condition trigger |
| `:add-counters` | `:counters`, `:target` | Add counters to permanent |
| `:grant-flashback` | `:target` | Grant alternate cost to graveyard card |
| `:add-restriction` | `:restriction` | Add restriction grant to player |
| `:scry` | `:count` | Look at top N, assign to top/bottom (selection UI) |
| `:peek-and-select` | `:count`, `:select-count` | Peek N cards, select some for hand |
| `:storm-copies` | `:count` | Create storm copies on stack |
| `:each-player` | `:player-effects` | Apply effects to each player |

### Targeting

The targeting system supports both cast-time and resolution-time targeting via a declarative requirements structure:

```clojure
;; Target requirement definition
{:target/type :object | :player
 :target/id :target-ref-keyword     ; Named slot for storing choice
 :target/required true | false
 :target/zone :hand | :battlefield  ; For object targets
 :target/controller :self | :opponent | :any
 :target/criteria {:card/types #{:artifact :enchantment}}}  ; OR logic

;; Resolution-time symbolic targets (in effect maps)
:self           ; The source object
:controller     ; Controller of the source
:any-player     ; Requires player target selection
:target-ref     ; Resolve from stored targeting choice
```

**Key functions:** `find-valid-targets`, `has-valid-targets?`, `target-still-legal?` (checks for fizzle on resolution), `all-targets-legal?`.

### Keyword Abilities

```clojure
;; Storm: creates stack-items on cast, not on resolution
;; maybe-create-storm-trigger adds a stack-item with
;; {:effect/type :storm-copies :effect/count (storm-count - 1)}
;; Copies have :object/is-copy true and don't re-trigger storm

;; Threshold: handled via conditions on effects (see Cabal Ritual)
;; Checked by engine/conditions.cljs: (>= graveyard-count 7)

;; Flash: allows casting at instant speed (checked in rules/can-cast?)
```

### Choice Resolution (Selection System)

Player choices (Intuition piles, IGG returns, LED color, tutor targets) are handled by the **selection system** in `events/selection.cljs`. Rather than a generic pending-choice, each choice type has a dedicated builder/confirm pattern:

```clojure
;; Engine effect sets up selection state in app-db:
{:pending-selection
 {:type :tutor | :discard | :graveyard-return | :pile-choice | :scry | ...
  :source-id object-id
  :candidates [card-ids...]
  :selected #{}
  :min-count 0
  :max-count 3
  :context {...}}}  ; Type-specific data

;; UI renders appropriate modal based on :type
;; Player toggles selections via ::toggle-selection
;; Confirms via ::confirm-*-selection (type-specific handler)
;; Handler executes remaining effects and resumes game flow
```

**10+ selection modal types:** tutor, discard, graveyard return, pile choice (Intuition), scry (top/bottom), peek-and-select (Flash of Insight), exile cost payment, X mana value, player target, object target, casting mode.

---

## 7. Bot System

> **Status: Partially implemented.** The bot protocol, goldfish bot, and burn bot are built and working. Priority system with yield/yield-all is complete. The remaining work is configurable bot scenarios (custom decks, starting hands, behavior rules) and the UX for configuring them.

### Design Philosophy: Test Against Cards, Not Decks

The bot system is not about simulating a real opponent deck. It's about putting specific hate cards and pressure in front of you so you practice beating the cards that matter in your matchups.

A storm player doesn't need to play against a full control deck. They need to practice forcing through a Counterspell. They don't need a full aggro matchup — they need to practice winning with a Phyrexian Dreadnought clock bearing down on them. The bot is a delivery mechanism for test scenarios.

### What's Built

**Priority System:** Real MTG priority passing. Both players must pass priority for phases to advance. Yield resolves one stack item and passes. Yield-all resolves the entire stack. Phase stops prevent player actions during untap and cleanup.

**Bot Protocol:** `IBot` multimethod protocol with archetype dispatch. Bots integrate with the priority system — they get priority passes during their turns and make decisions using the full engine (cast-spell, resolve, etc.). No special bot-only code paths.

**Goldfish Bot:** Simplest possible opponent. Plays a land per turn, passes priority on everything. Pure combo practice with no interaction.

**Burn Bot:** 20 Mountains + 40 Lightning Bolts. Casts through the full engine path: `rules/cast-spell` creates stack-item, resolution dispatches `deal-damage` effect, life total decrements, loss condition triggers at 0 life. Creates real clock pressure.

**Bot Turn Cycle:** Opponent turns are real turns with active-player switching. The bot draws, gets a main phase, plays lands, casts spells — all using the same events as the human player.

### Implementation

```
bots/
  protocol.cljs     ; IBot multimethod (get-archetype, should-act?, choose-action, etc.)
  definitions.cljs  ; Archetype definitions (goldfish, burn)
  rules.cljs        ; Decision heuristics (play land, bolt face, pass priority)
  interceptor.cljs  ; re-frame interceptor that drives bot turns automatically
```

The bot interceptor fires after priority passes and phase transitions. When the bot has priority, it evaluates `should-act?` and `choose-action` against the current game state, then dispatches the appropriate re-frame event. The human player sees the bot's actions appear in the history log and can respond normally.

### Remaining Work: Configurable Scenarios

The next step is making bot scenarios configurable rather than hardcoded. The vision:

**Scenario configuration:**
- **Deck** — Specify which cards the opponent has (like deck building for the bot)
- **Starting hand** — Sculpt the opponent's opening hand (like hand sculpting for the player)
- **Behavior rules** — How the bot plays its cards (play lands, cast spells on curve, bolt face, hold up countermagic, etc.)

**Target scenarios:**
- **Goldfish** — No interaction, pure combo practice (built)
- **Burn clock** — Lightning Bolt pressure, practice winning under a life total clock (built)
- **Hate pieces** — Opponent starts with Seal of Cleansing, Chalice of the Void, Sphere of Resistance on the battlefield. Player must use real cards to interact (Chain of Vapor, Hurkyl's Recall)
- **Countermagic** — Opponent holds up Counterspell. Player practices forcing through disruption
- **Creature clock** — Fast creatures (e.g., Phyrexian Dreadnought) as a kill clock. Requires creatures and combat (see roadmap)
- **Named presets** — Out-of-the-box configurations bundling deck + hand + behavior rules

**Open design question:** The engine supports robust rule-based bot behavior. The challenge is how to expose behavior configuration to the player without dumping raw EDN. This needs its own design session. Options range from simple presets to a per-card behavior config UI.

### Player-Directed Interaction

For permanents like Seal of Cleansing, the interaction model is player-directed: the bot *has* the permanent on its battlefield, but the *player* decides when and how to interact with it. The player activates their own Chain of Vapor targeting the Seal, or the player can activate the Seal on the opponent's behalf to test timing scenarios. The bot doesn't make strategic decisions about when to crack the Seal — that's not the point. The point is that the Seal is there, affecting the game state, and the player needs to deal with it.

;; This enables:
;; - Testing specific disruption patterns
;; - "What if they had the Force?"
;; - Learning what disruption does to your lines
```

---

## 8. Fork/Replay System

### Core Concept

Every game action creates a history entry with a **snapshot** of the complete game state. This enables:

1. **Instant replay**: Jump to any position by restoring its snapshot (no event reduction needed)
2. **Fork**: Create a named branch at any point in the history
3. **Step navigation**: Move forward/backward through effective entries

**Design deviation:** The original design proposed pure event replay (reduce events 0..N). The implementation uses **snapshot-based history** instead — each entry stores the full Datascript db value. This trades memory for instant state access, which is the right tradeoff for a practice tool where fast rewind matters more than memory efficiency.

### History Data Structure

```clojure
;; Stored in re-frame app-db (not Datascript)
{:history/main []                  ; Main branch entries
 :history/forks {}                 ; Fork map: fork-id -> fork-data
 :history/current-branch nil       ; Active branch (nil = main)
 :history/position -1}             ; Current position in effective entries

;; Each history entry
{:entry/description "Cast Dark Ritual"  ; Human-readable
 :entry/turn 1                          ; Turn number
 :entry/phase :main1                    ; Phase when action occurred
 :entry/snapshot <datascript-db-value>} ; Complete game state

;; Fork data
{:fork/id (uuid)
 :fork/name "Try different Intuition pile"
 :fork/branch-point 5           ; Index in parent's entries
 :fork/parent nil               ; Parent fork ID (nil = main)
 :fork/entries [...]}           ; Entries from branch point forward
```

### Fork Operations

The fork system supports a **tree of branches** (forks can have child forks):

- **Create fork**: Branch at any position, auto-named or user-named
- **Auto-fork**: Taking an action while not at the tip auto-creates a fork
- **Switch branches**: Jump between main and any fork
- **Delete fork**: Remove branch and all descendants
- **Rename fork**: Change fork display name

**Implementation files:**
- `history/core.cljs` — Fork tree logic, effective entries computation
- `history/descriptions.cljs` — Human-readable descriptions for each action type
- `history/events.cljs` — re-frame events for step-back, step-forward, create-fork, etc.
- `history/interceptor.cljs` — re-frame interceptor that auto-captures snapshots on game events

### UI Integration

```
Action History Panel:
┌─────────────────────────────────────┐
│ ▼ Turn 1                            │
│   • Drew 7 cards                    │
│   • Kept hand                       │
│   • Played Polluted Delta           │
│   • Cast Lotus Petal                │
│   • Sacrificed Petal for U         │
│   • Cast Careful Study              │
│     └─ [Fork here] [Rewind to]      │  <- Actions on hover
│   • Discarded: IGG, Dark Ritual     │
│ ▼ Turn 2                            │
│   • Drew Lion's Eye Diamond         │
│   ...                               │
├─────────────────────────────────────┤
│ Forks:                              │
│   • main (current)                  │
│   • "Keep 2nd ritual" @ event 7     │
│   • "Different IGG targets" @ 23    │
└─────────────────────────────────────┘
```

---

## 9. UI Components

### Layout

```
┌──────────────────────────────────────────────────────────────────┐
│  Fizzle                    [Setup] [Tactics] [Settings]   │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────┐  ┌─────────────────────────────────┐   │
│  │     OPPONENT        │  │         ACTION LOG              │   │
│  │  Life: 20           │  │  • Cast Dark Ritual            │   │
│  │  Clock: 3 turns     │  │  • Add BBB                      │   │
│  │  [Bolt] [Counter]   │  │  • Cast Cabal Ritual           │   │
│  │                     │  │  • Add BBBBB (threshold)        │   │
│  └─────────────────────┘  │  • Cast Ill-Gotten Gains       │   │
│                           │  ← [Fork] [Rewind]              │   │
│  ┌─────────────────────┐  └─────────────────────────────────┘   │
│  │   BATTLEFIELD       │                                        │
│  │  [Gemstone Mine]    │  ┌─────────────────────────────────┐   │
│  │  [LED] [LED]        │  │       GRAVEYARD (12)            │   │
│  └─────────────────────┘  │  Dark Ritual, Dark Ritual,      │   │
│                           │  Careful Study, Mental Note...   │   │
│  ┌─────────────────────┐  │  [View All]                      │   │
│  │   MANA POOL         │  └─────────────────────────────────┘   │
│  │  BBB UU (5 total)   │                                        │
│  │  Storm Count: 7     │                                        │
│  └─────────────────────┘                                        │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                         HAND (4)                          │   │
│  │   [Intuition]  [IGG]  [Brain Freeze]  [City of Brass]    │   │
│  │                                                          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  [Untap] [Upkeep] [Draw] [Main] ●  [Combat] [Main 2] [End]      │
└──────────────────────────────────────────────────────────────────┘
```

### Component Hierarchy

```clojure
;; core.cljs — 3-screen router with responsive 3-column layout
[app]
├── [nav-bar]               ; Setup / Game buttons, "New Game"
├── [setup-view]            ; Screen: :setup
│   ├── [deck-selector]     ; Preset/imported deck list
│   ├── [deck-editor]       ; Main/sideboard card lists with swap
│   ├── [hand-sculptor]     ; Must-contain cards for opening hand
│   ├── [clock-config]      ; Goldfish clock turns
│   ├── [preset-manager]    ; Save/load/delete presets
│   └── [import-modal]      ; Deck text import (Moxfield/MTGGoldfish)
├── [opening-hand-view]     ; Screen: :opening-hand
│   ├── [hand-display]      ; 7-card hand with selection
│   ├── [mulligan-controls] ; Keep / Mulligan buttons
│   └── [bottom-selection]  ; London mulligan bottoming phase
├── [game-view]             ; Screen: game (default)
│   ├── [opponent-panel]    ; Life total with color-coded health
│   ├── [battlefield]       ; Permanents with mana ability / activated ability buttons
│   ├── [mana-pool]         ; Color symbols + storm counter
│   ├── [controls]          ; Cast, Play Land, Resolve buttons
│   ├── [hand]              ; Cards with selection highlighting
│   ├── [phase-bar]         ; Turn counter, phase indicators, advance button
│   │                       ; Right column (collapsible panels):
│   ├── [stack-panel]       ; Stack items with source names
│   ├── [graveyard-panel]   ; Threshold indicator, flashback badges
│   └── [history-panel]     ; Action log grouped by turn, fork controls
└── [modals]                ; Overlay modal system (10+ types)
    ├── [selection-modal]   ; Tutor, discard, graveyard return
    ├── [scry-modal]        ; Top/bottom pile assignment
    ├── [pile-choice-modal] ; Intuition-style opponent choice
    ├── [peek-select-modal] ; Flash of Insight peek
    ├── [exile-cards-modal] ; Exile cost payment
    ├── [x-mana-modal]      ; X cost value selection
    ├── [player-target-modal]  ; Target player selection
    ├── [object-target-modal]  ; Target object selection
    └── [mode-selector-modal]  ; Casting mode selection
```

### Reagent Component Examples

```clojure
(ns fizzle.views.hand
  (:require [re-frame.core :as rf]))

(defn card-in-hand [{:keys [object-id]}]
  (let [card @(rf/subscribe [:card-for-object object-id])
        castable? @(rf/subscribe [:can-cast? object-id])
        selected? @(rf/subscribe [:selected? object-id])]
    [:div.card
     {:class [(when castable? "castable")
              (when selected? "selected")]
      :on-click #(rf/dispatch [:select-card object-id])}
     [:div.card-name (:card/name card)]
     [:div.card-cost (format-mana (:card/cmc card))]
     (when castable?
       [:button.cast-btn
        {:on-click #(rf/dispatch [:cast-spell object-id])}
        "Cast"])]))

(defn hand []
  (let [hand-objects @(rf/subscribe [:hand])]
    [:div.hand
     [:h3 (str "Hand (" (count hand-objects) ")")]
     [:div.hand-cards
      (for [obj hand-objects]
        ^{:key (:object/id obj)}
        [card-in-hand {:object-id (:object/id obj)}])]]))
```

### Key UX Decisions

Based on the specific needs of storm practice, here are the UX patterns for complex cards:

#### Intuition Pile Selection

```
┌─────────────────────────────────────────────────────────────┐
│  INTUITION - Search for 3 cards                             │
├─────────────────────────────────────────────────────────────┤
│  Search your library...                                     │
│                                                             │
│  Selected (3/3):                                            │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐                       │
│  │  IGG    │ │  IGG    │ │  IGG    │    ← 3x same card     │
│  └─────────┘ └─────────┘ └─────────┘                       │
│                                                             │
│  [Search Library...]                                        │
│                                                             │
│  Which card goes to hand?                                   │
│  ○ Card 1 (IGG)                                            │
│  ○ Card 2 (IGG)                                            │
│  ○ Card 3 (IGG)                                            │
│                                                             │
│  [You Choose]  [Random]  [All Same → Auto-pick]            │
│                                                             │
│  Note: "All Same" shortcut available when 3 identical cards │
└─────────────────────────────────────────────────────────────┘
```

**UX Rules:**
- Player always picks all 3 cards (no bot involvement in selection)
- Player chooses which goes to hand OR clicks "Random" for opponent simulation
- When all 3 cards are identical (e.g., 3x IGG), show shortcut button that auto-completes
- Remaining 2 cards go to graveyard automatically

#### Ill-Gotten Gains Opponent Returns

```
┌─────────────────────────────────────────────────────────────┐
│  ILL-GOTTEN GAINS - Opponent returns up to 3 cards          │
├─────────────────────────────────────────────────────────────┤
│  Opponent's graveyard (after discarding hand):              │
│  [Counterspell] [Counterspell] [Force of Will] [Island]    │
│                                                             │
│  Opponent returns to hand:                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ○ Disabled (pure goldfish)                           │  │
│  │ ○ Random 3 cards                                     │  │
│  │ ○ You choose (practice against worst case)    ← sel  │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  If "You choose": Select up to 3 cards for opponent:       │
│  [x] Counterspell                                          │
│  [x] Counterspell                                          │
│  [ ] Force of Will                                         │
│  [ ] Island                                                │
│                                                             │
│  [Confirm]                                                  │
└─────────────────────────────────────────────────────────────┘
```

**UX Rules:**
- Three modes: Disabled (opponent returns nothing), Random, You Choose
- Default to "Disabled" for pure goldfishing
- "You Choose" lets player practice worst-case (opponent gets back all their interaction)
- Only matters when opponent has interaction in their graveyard
- Setting persists per session

#### Lion's Eye Diamond Timing

```
┌─────────────────────────────────────────────────────────────┐
│  LION'S EYE DIAMOND                                         │
├─────────────────────────────────────────────────────────────┤
│  Timing: Instant speed only (not while announcing spells)   │
│                                                             │
│  [Sacrifice for BBB]  [Sacrifice for UUU]  [Sacrifice ...]  │
│                                                             │
│  ⚠️ Cannot activate while casting a spell from hand.        │
│  Common patterns:                                           │
│  • Cast IGG, hold priority, crack LED in response           │
│  • Cast Infernal Tutor, hold priority, crack LED            │
└─────────────────────────────────────────────────────────────┘
```

**UX Rules:**
- Model LED as instant-speed activated ability (not mana ability)
- LED button is disabled during spell announcement phase
- After spell goes on stack, LED can be activated before resolution
- UI shows "Hold Priority" hint when relevant spells are cast
- Trust player to use correctly — this is a practice tool, not a rules enforcer

#### Scryfall Integration for Card Images

```clojure
;; Fetch card image from Scryfall
(defn scryfall-image-url [card-name]
  (str "https://api.scryfall.com/cards/named?exact=" 
       (js/encodeURIComponent card-name)
       "&format=image&version=normal"))

;; Usage in component
(defn card-image [{:keys [card-name]}]
  [:img.card-image
   {:src (scryfall-image-url card-name)
    :alt card-name
    :loading "lazy"  ; Don't block initial load
    :on-error #(reset! show-fallback? true)}])

;; Fallback to text-only display if image fails
(defn card-display [{:keys [card]}]
  (let [show-fallback? (r/atom false)]
    (fn []
      (if @show-fallback?
        [card-text-only card]
        [card-image {:card-name (:card/name card)}]))))
```

**Scryfall API Notes:**
- Free for non-commercial use
- Rate limit: 10 requests/second (we'll cache aggressively)
- Use `version=small` for hand/graveyard, `version=normal` for focused view
- Cache images in localStorage for offline access
- Premodern cards are all available

---

## 10. Future: Persistence & Sharing

> **Status: Not yet implemented.** The tactics/puzzle system (save positions, share EDN) has not been built. The fork/replay system handles most "rewind to interesting position" use cases for now. localStorage persistence for presets and imported decks is implemented in the setup system. Puzzle save/load is planned after the bot scenario system and card expansion (see roadmap Step 4).

### Phase 1: Local Storage (v1)

For offline-first MVP, use browser localStorage:

```clojure
(ns fizzle.tactics.storage)

(defn save-puzzle! [puzzle]
  (let [puzzles (get-all-puzzles)
        updated (assoc puzzles (:puzzle/id puzzle) puzzle)]
    (.setItem js/localStorage "fizzle-puzzles" 
              (pr-str updated))))

(defn load-puzzle [puzzle-id]
  (let [puzzles (edn/read-string 
                  (.getItem js/localStorage "fizzle-puzzles"))]
    (get puzzles puzzle-id)))

(defn export-puzzle-edn [puzzle]
  ;; Returns EDN string for sharing via copy/paste
  (pr-str puzzle))

(defn import-puzzle-edn [edn-string]
  (edn/read-string edn-string))
```

### Phase 2: Cloud Persistence (Future)

Options analysis for when we add cloud sync:

| Option | Pros | Cons | Cost |
|--------|------|------|------|
| **Firebase Firestore** | Real-time sync, good free tier, auth included | Google lock-in, proprietary | Free tier generous |
| **Supabase** | PostgreSQL, open source, self-hostable | Newer, smaller community | Free tier decent |
| **PocketBase** | Single binary, SQLite, self-host easy | Less scalable, newer | Free (self-host) |
| **Plain S3 + Auth0** | Maximum control | More work to build | Pay per use |

**Recommendation: Start with Supabase.**

Rationale:
- PostgreSQL gives us Datalog-like querying capability
- Open source means we can self-host if needed
- Good ClojureScript interop via JS SDK
- Row-level security for user puzzles vs. public puzzles
- Easier to migrate away from than Firebase

### Data Model for Sharing

```clojure
;; Puzzle stored in cloud
{:puzzle/id (uuid)
 :puzzle/name "IGG Line vs Empty Hand"
 :puzzle/author "storm_player_42"
 :puzzle/created #inst "2026-01-15"
 :puzzle/tags #{"iggy-pop" "igg-line" "tutorial"}
 :puzzle/difficulty :intermediate
 :puzzle/description "Practice the basic IGG loop..."
 :puzzle/initial-state {...}  ; Serialized Datascript db
 :puzzle/solution-hint "LED in response to IGG"
 :puzzle/public? true
 :puzzle/play-count 142
 :puzzle/success-rate 0.67}
```

### Community Features (Future)

- Public puzzle library with search/filter
- Upvoting/favoriting puzzles
- "Daily puzzle" feature
- Puzzle collections/courses
- Success rate tracking per user

---

## 11. Starter Deck: Iggy Pop

### Decklist

```
Maindeck (60):
4 Dark Ritual
4 Cabal Ritual
4 Lotus Petal
4 Lion's Eye Diamond
4 Ill-Gotten Gains
4 Careful Study
3 Mental Note
1 Deep Analysis
3 Intuition
3 Merchant Scroll
3 Cunning Wish
1 Brain Freeze
1 Recoup
1 Vision Charm
4 Urza's Bauble
4 Polluted Delta
4 Gemstone Mine
3 City of Brass
1 City of Traitors
3 Island
1 Swamp

Sideboard (15):
1 Brain Freeze
1 Intuition
1 Meditate
1 Chain of Vapor
1 Hurkyl's Recall
1 Hunting Pack
1 Tormod's Crypt
1 Abeyance
2 Orim's Chant
3 Cabal Therapy
2 Xantid Swarm
```

### Implementation Status

32 unique cards implemented across 11 card files (`cards/iggy_pop.cljs` for core deck cards, plus 10 individual card files). The core Iggy Pop combo is fully playable — the unimplemented cards are mostly sideboard and utility cards that represent engine capabilities not yet built (tokens, bounce, delayed triggers).

| Status | Cards |
|--------|-------|
| **Implemented (iggy_pop.cljs)** | Dark Ritual, Cabal Ritual, Lotus Petal, Lion's Eye Diamond, Careful Study, Mental Note, Brain Freeze, Polluted Delta, Gemstone Mine, City of Brass, City of Traitors, Island, Swamp, Underground River, Merchant Scroll, Opt, Intuition |
| **Implemented (individual files)** | Ill-Gotten Gains, Deep Analysis, Recoup, Orim's Chant, Flash of Insight, Cephalid Coliseum, Ray of Revelation, Seal of Cleansing |
| **Implemented (extra)** | Lightning Bolt (burn bot), Plains, Mountain, Forest (basic lands) |
| **Not yet implemented** | Cunning Wish, Vision Charm, Urza's Bauble, Meditate, Chain of Vapor, Hurkyl's Recall, Hunting Pack, Tormod's Crypt, Abeyance, Cabal Therapy, Xantid Swarm |

Unimplemented cards represent engine capabilities needed for card expansion: token creation (Hunting Pack), bounce effects (Chain of Vapor, Hurkyl's Recall), delayed triggers (Urza's Bauble), skip-turn (Meditate), name-a-card (Cabal Therapy), creature triggers (Xantid Swarm), and wish/sideboard access (Cunning Wish). These gaps will be filled naturally during the broader Premodern card expansion phase.

### Card Definition Examples

#### Mana Sources

```clojure
;; Lands
(def island
  {:card/id :island
   :card/name "Island"
   :card/types #{:land}
   :card/subtypes #{:island}
   :card/supertypes #{:basic}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:blue 1}}]}]})

(def swamp
  {:card/id :swamp
   :card/name "Swamp"
   :card/types #{:land}
   :card/subtypes #{:swamp}
   :card/supertypes #{:basic}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:black 1}}]}]})

(def polluted-delta
  {:card/id :polluted-delta
   :card/name "Polluted Delta"
   :card/types #{:land}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:cost/tap true
                                    :cost/pay-life 1
                                    :cost/sacrifice :self}
                     :ability/effects [{:effect/type :tutor
                                        :effect/zone :library
                                        :effect/destination :battlefield
                                        :effect/filter {:or [{:subtype :island}
                                                             {:subtype :swamp}]}
                                        :effect/tapped true}]}]})

(def gemstone-mine
  {:card/id :gemstone-mine
   :card/name "Gemstone Mine"
   :card/types #{:land}
   :card/enters-with {:counters {:mining 3}}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true
                                    :cost/remove-counter {:type :mining :count 1}}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:choice 1}
                                        :effect/choice-type :any-color}]}]
   :card/triggers [{:trigger/type :state-based
                    :trigger/condition {:no-counters :mining}
                    :trigger/effects [{:effect/type :sacrifice :effect/target :self}]}]})

(def city-of-brass
  {:card/id :city-of-brass
   :card/name "City of Brass"
   :card/types #{:land}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true}
                     :ability/effects [{:effect/type :damage
                                        :effect/amount 1
                                        :effect/target :controller}
                                       {:effect/type :add-mana
                                        :effect/mana {:choice 1}
                                        :effect/choice-type :any-color}]}]})

(def city-of-traitors
  {:card/id :city-of-traitors
   :card/name "City of Traitors"
   :card/types #{:land}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:colorless 2}}]}]
   :card/triggers [{:trigger/type :on-land-play
                    :trigger/condition {:controller-played-another-land true}
                    :trigger/effects [{:effect/type :sacrifice :effect/target :self}]}]})

;; Artifacts
(def lotus-petal
  {:card/id :lotus-petal
   :card/name "Lotus Petal"
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/sacrifice :self}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:choice 1}
                                        :effect/choice-type :any-color}]}]})

(def lions-eye-diamond
  {:card/id :lions-eye-diamond
   :card/name "Lion's Eye Diamond"
   :card/cmc 0
   :card/types #{:artifact}
   :card/text "Sacrifice LED, Discard your hand: Add three mana of any one color. Activate only as an instant."
   :card/abilities [{:ability/type :mana
                     :ability/timing :instant-speed  ; Special LED timing
                     :ability/cost {:cost/sacrifice :self
                                    :cost/discard-hand true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:choice 3}
                                        :effect/choice-type :one-color}]}]})

(def urzas-bauble
  {:card/id :urzas-bauble
   :card/name "Urza's Bauble"
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:cost/tap true
                                    :cost/sacrifice :self}
                     :ability/effects [{:effect/type :look-at-hand
                                        :effect/target :chosen-player}
                                       {:effect/type :draw-delayed
                                        :effect/count 1
                                        :effect/timing :next-upkeep}]}]})
```

#### Rituals

```clojure
(def dark-ritual
  {:card/id :dark-ritual
   :card/name "Dark Ritual"
   :card/cmc 1
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]})

(def cabal-ritual
  {:card/id :cabal-ritual
   :card/name "Cabal Ritual"
   :card/cmc 2
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}. Threshold — Add {B}{B}{B}{B}{B} instead."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}
                   :effect/condition {:condition/type :threshold-not-met}}
                  {:effect/type :add-mana
                   :effect/mana {:black 5}
                   :effect/condition {:condition/type :threshold}}]})
```

#### Card Selection / Draw

```clojure
(def careful-study
  {:card/id :careful-study
   :card/name "Careful Study"
   :card/cmc 1
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards, then discard two cards."
   :card/effects [{:effect/type :draw :effect/count 2}
                  {:effect/type :discard :effect/count 2 :effect/choice :controller}]})

(def mental-note
  {:card/id :mental-note
   :card/name "Mental Note"
   :card/cmc 1
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Mill 2, then draw a card."
   :card/effects [{:effect/type :mill :effect/count 2 :effect/target :controller}
                  {:effect/type :draw :effect/count 1}]})

(def deep-analysis
  {:card/id :deep-analysis
   :card/name "Deep Analysis"
   :card/cmc 4
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards. Flashback—{1}{U}, Pay 3 life."
   :card/effects [{:effect/type :draw :effect/count 2}]
   :card/flashback {:cost {:generic 1 :blue 1}
                    :additional-cost {:pay-life 3}}})

(def intuition
  {:card/id :intuition
   :card/name "Intuition"
   :card/cmc 3
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Search your library for 3 cards. Opponent chooses 1 for your hand, rest go to graveyard."
   :card/effects [{:effect/type :tutor-intuition
                   :effect/search-count 3
                   :effect/opponent-chooses 1
                   :effect/remainder-to :graveyard}]})

(def merchant-scroll
  {:card/id :merchant-scroll
   :card/name "Merchant Scroll"
   :card/cmc 2
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Search your library for a blue instant and put it in your hand."
   :card/effects [{:effect/type :tutor
                   :effect/zone :library
                   :effect/destination :hand
                   :effect/filter {:color :blue :type :instant}
                   :effect/reveal true}]})

(def cunning-wish
  {:card/id :cunning-wish
   :card/name "Cunning Wish"
   :card/cmc 3
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose an instant from outside the game, put it in hand. Exile Cunning Wish."
   :card/effects [{:effect/type :wish
                   :effect/filter {:type :instant}
                   :effect/destination :hand}
                  {:effect/type :exile-self}]})
```

#### Engine Card

```clojure
(def ill-gotten-gains
  {:card/id :ill-gotten-gains
   :card/name "Ill-Gotten Gains"
   :card/cmc 4
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Exile IGG. Each player discards their hand, then returns up to 3 cards from their graveyard to their hand."
   :card/effects [{:effect/type :exile-self}
                  {:effect/type :each-player
                   :effect/player-effects 
                   [{:effect/type :discard-hand}
                    {:effect/type :return-from-graveyard
                     :effect/count {:up-to 3}
                     :effect/destination :hand
                     :effect/choice :that-player}]}]})
```

#### Win Conditions

```clojure
(def brain-freeze
  {:card/id :brain-freeze
   :card/name "Brain Freeze"
   :card/cmc 2
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Target player mills 3. Storm."
   :card/effects [{:effect/type :mill
                   :effect/count 3
                   :effect/target :chosen-player}]})
```

#### Utility

```clojure
(def recoup
  {:card/id :recoup
   :card/name "Recoup"
   :card/cmc 2
   :card/colors #{:red}
   :card/types #{:sorcery}
   :card/text "Target sorcery card in your graveyard gains flashback until end of turn. Flashback {3}{R}."
   :card/effects [{:effect/type :grant-flashback
                   :effect/target :sorcery-in-graveyard
                   :effect/duration :end-of-turn}]
   :card/flashback {:cost {:generic 3 :red 1}}})

(def vision-charm
  {:card/id :vision-charm
   :card/name "Vision Charm"
   :card/cmc 1
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose one: Target player mills 4; or phase out target artifact; or choose land type A and B, all A become B until end of turn."
   :card/effects [{:effect/type :modal
                   :effect/modes
                   [{:mode/name "Mill"
                     :mode/effects [{:effect/type :mill
                                     :effect/count 4
                                     :effect/target :chosen-player}]}
                    {:mode/name "Phase Out"
                     :mode/effects [{:effect/type :phase-out
                                     :effect/target :chosen-artifact}]}
                    {:mode/name "Land Type"
                     :mode/effects [{:effect/type :change-land-types
                                     :effect/duration :end-of-turn}]}]}]})
```

#### Key Sideboard Cards

```clojure
(def orims-chant
  {:card/id :orims-chant
   :card/name "Orim's Chant"
   :card/cmc 1
   :card/colors #{:white}
   :card/types #{:instant}
   :card/text "Kicker {W}. Target player can't cast spells this turn. If kicked, creatures can't attack."
   :card/effects [{:effect/type :silence
                   :effect/target :chosen-player
                   :effect/duration :end-of-turn}]
   :card/kicker {:cost {:white 1}
                 :effects [{:effect/type :prevent-attacks
                            :effect/duration :end-of-turn}]}})

(def abeyance
  {:card/id :abeyance
   :card/name "Abeyance"
   :card/cmc 2
   :card/colors #{:white}
   :card/types #{:instant}
   :card/text "Until end of turn, target player can't cast instants or sorceries or activate non-mana abilities. Draw a card."
   :card/effects [{:effect/type :restrict-spells
                   :effect/target :chosen-player
                   :effect/spell-types #{:instant :sorcery}
                   :effect/duration :end-of-turn}
                  {:effect/type :restrict-abilities
                   :effect/target :chosen-player
                   :effect/ability-types #{:activated}
                   :effect/except #{:mana}
                   :effect/duration :end-of-turn}
                  {:effect/type :draw :effect/count 1}]})

(def chain-of-vapor
  {:card/id :chain-of-vapor
   :card/name "Chain of Vapor"
   :card/cmc 1
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Return target nonland permanent to its owner's hand. Then that permanent's controller may sacrifice a land. If they do, they may copy this spell and choose a new target."
   :card/effects [{:effect/type :bounce
                   :effect/target :chosen-nonland-permanent}
                  {:effect/type :chain-offer
                   :effect/cost {:sacrifice-land true}
                   :effect/copy-spell true}]})

(def hurkyls-recall
  {:card/id :hurkyls-recall
   :card/name "Hurkyl's Recall"
   :card/cmc 2
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Return all artifacts target player owns to their hand."
   :card/effects [{:effect/type :mass-bounce
                   :effect/filter {:type :artifact}
                   :effect/target :chosen-player}]})

(def cabal-therapy
  {:card/id :cabal-therapy
   :card/name "Cabal Therapy"
   :card/cmc 1
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Name a nonland card. Target player reveals hand and discards all cards with that name. Flashback—Sacrifice a creature."
   :card/effects [{:effect/type :name-card
                   :effect/restriction :nonland}
                  {:effect/type :reveal-hand
                   :effect/target :chosen-player}
                  {:effect/type :discard-named
                   :effect/target :chosen-player}]
   :card/flashback {:cost {:sacrifice-creature true}}})

(def meditate
  {:card/id :meditate
   :card/name "Meditate"
   :card/cmc 3
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Draw 4 cards. Skip your next turn."
   :card/effects [{:effect/type :draw :effect/count 4}
                  {:effect/type :skip-turn :effect/target :controller}]})

(def hunting-pack
  {:card/id :hunting-pack
   :card/name "Hunting Pack"
   :card/cmc 7
   :card/colors #{:green}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Create a 4/4 green Beast creature token. Storm."
   :card/effects [{:effect/type :create-token
                   :effect/token {:name "Beast"
                                  :power 4
                                  :toughness 4
                                  :colors #{:green}
                                  :types #{:creature}
                                  :subtypes #{:beast}}}]})

(def tormods-crypt
  {:card/id :tormods-crypt
   :card/name "Tormod's Crypt"
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:cost/tap true
                                    :cost/sacrifice :self}
                     :ability/effects [{:effect/type :exile-graveyard
                                        :effect/target :chosen-player}]}]})

(def xantid-swarm
  {:card/id :xantid-swarm
   :card/name "Xantid Swarm"
   :card/cmc 1
   :card/colors #{:green}
   :card/types #{:creature}
   :card/subtypes #{:insect}
   :card/power 0
   :card/toughness 1
   :card/keywords #{:flying}
   :card/triggers [{:trigger/type :on-attack
                    :trigger/effects [{:effect/type :silence
                                       :effect/target :defending-player
                                       :effect/duration :end-of-turn}]}]})

;; === Bot Deck Cards (Opponent Interaction) ===

(def gaeas-blessing
  {:card/id :gaeas-blessing
   :card/name "Gaea's Blessing"
   :card/cmc 2
   :card/colors #{:green}
   :card/types #{:sorcery}
   :card/text "Target player shuffles up to 3 cards from graveyard into library. Draw a card. When GB is put into graveyard from library, shuffle graveyard into library."
   :card/effects [{:effect/type :shuffle-cards-into-library
                   :effect/count {:up-to 3}
                   :effect/from :graveyard
                   :effect/target :chosen-player}
                  {:effect/type :draw :effect/count 1}]
   :card/triggers [{:trigger/type :zone-change
                    :trigger/from :library
                    :trigger/to :graveyard
                    :trigger/effects [{:effect/type :shuffle-graveyard-into-library
                                       :effect/target :owner}]}]})

(def counterspell
  {:card/id :counterspell
   :card/name "Counterspell"
   :card/cmc 2
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Counter target spell."
   :card/effects [{:effect/type :counter-spell
                   :effect/target :chosen-spell}]})

(def duress
  {:card/id :duress
   :card/name "Duress"
   :card/cmc 1
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Target opponent reveals hand. You choose a noncreature, nonland card. They discard it."
   :card/effects [{:effect/type :reveal-hand
                   :effect/target :opponent}
                  {:effect/type :choose-and-discard
                   :effect/target :opponent
                   :effect/filter {:not #{:creature :land}}}]})

(def lightning-bolt
  {:card/id :lightning-bolt
   :card/name "Lightning Bolt"
   :card/cmc 1
   :card/colors #{:red}
   :card/types #{:instant}
   :card/text "Deal 3 damage to any target."
   :card/effects [{:effect/type :damage
                   :effect/amount 3
                   :effect/target :any}]})
```

---

## 12. Testing Strategy

The architecture—pure functions, immutable state, event sourcing—lends itself to straightforward testing. This section outlines the testing approach.

### Testing Philosophy

**Test the engine, trust the UI.** The game engine, effect interpreter, and subscriptions are pure functions that deserve thorough testing. Reagent components are thin wrappers over subscriptions; if subscriptions are correct, the UI follows.

**Leverage immutability.** Deterministic replay is a feature *and* a testing tool. If the same events produce different states, something is broken.

**Data-driven tests for data-driven cards.** Cards are data; card tests should be data too. Define scenarios as EDN, let a test runner interpret them.

### Test Categories

#### 1. Effect Interpreter Tests

The effect interpreter is the core of the engine. Each effect type gets unit tests verifying the state transformation:

```clojure
(deftest add-mana-effect
  (let [db (-> (empty-db)
               (set-mana-pool :player-1 {:black 0}))
        result (execute-effect db :player-1
                 {:effect/type :add-mana
                  :effect/mana {:black 3}})]
    (is (= 3 (get-in result [:players :player-1 :mana-pool :black])))))

(deftest threshold-condition
  (let [db-below (set-graveyard-count (empty-db) :player-1 6)
        db-at    (set-graveyard-count (empty-db) :player-1 7)]
    (is (false? (check-condition db-below :player-1 {:condition/type :threshold})))
    (is (true?  (check-condition db-at :player-1 {:condition/type :threshold})))))
```

#### 2. Property-Based Tests

Use `test.check` to verify invariants hold across random game sequences:

| Invariant | Description |
|-----------|-------------|
| Zone exclusivity | Each card exists in exactly one zone |
| Mana non-negative | Mana pool values ≥ 0 |
| Storm monotonic | Storm count only increases within a turn |
| Card conservation | Total cards across all zones = starting deck size |
| Replay determinism | Same events → same final state |

```clojure
(defspec replay-is-deterministic 100
  (prop/for-all [events (gen-game-events)]
    (let [state-1 (reduce apply-event (initial-db) events)
          state-2 (reduce apply-event (initial-db) events)]
      (= state-1 state-2))))
```

Property-based tests catch edge cases that example-based tests miss—especially around zone transitions, mana payment edge cases, and stack interactions.

#### 3. Card Scenario Tests

Define card tests as data. Each scenario specifies setup, action, and expected outcome:

```clojure
(def card-scenarios
  [{:name "Dark Ritual adds BBB"
    :setup {:mana-pool {:black 1}}
    :action [:cast :dark-ritual]
    :expect {:mana-pool {:black 3}}}  ; paid 1, gained 3

   {:name "Cabal Ritual without threshold"
    :setup {:mana-pool {:black 2}
            :graveyard-count 4}
    :action [:cast :cabal-ritual]
    :expect {:mana-pool {:black 3}}}  ; paid 2, gained 3

   {:name "Cabal Ritual with threshold"
    :setup {:mana-pool {:black 2}
            :graveyard-count 7}
    :action [:cast :cabal-ritual]
    :expect {:mana-pool {:black 5}}}])  ; paid 2, gained 5
```

A single test runner interprets all scenarios. Adding a new card means adding scenario data, not writing test code.

#### 4. Subscription Tests

re-frame subscriptions are pure functions from app-db to derived values. Test them directly:

```clojure
(deftest castable-cards-sub
  (let [db (-> (empty-db)
               (add-to-hand :player-1 :dark-ritual)
               (add-to-hand :player-1 :cabal-ritual)
               (set-mana-pool :player-1 {:black 1}))]
    ;; Can cast Dark Ritual (B), cannot cast Cabal Ritual (1B)
    (is (= #{:dark-ritual} (subs/castable-cards db :player-1)))))

(deftest storm-count-sub
  (let [db (-> (empty-db)
               (apply-event [:cast-spell :lotus-petal])
               (apply-event [:cast-spell :dark-ritual]))]
    (is (= 2 (subs/storm-count db)))))
```

#### 5. Fork/Replay Tests

The fork/replay system is core functionality. Test it explicitly:

```clojure
(deftest fork-preserves-history
  (let [game (-> (new-game)
                 (apply-events [...ten events...])
                 (create-fork "test fork" 5))]
    ;; Original history intact
    (is (= 10 (count (get-main-branch-events game))))
    ;; Fork branches at event 5
    (is (= 5 (:fork/branch-point (current-fork game))))))

(deftest rewind-and-diverge
  (let [game-a (-> (new-game)
                   (apply-events [e1 e2 e3 e4 e5]))
        game-b (-> game-a
                   (rewind-to 3)
                   (apply-events [e4' e5']))]
    ;; Different final states
    (is (not= (get-state game-a) (get-state game-b)))
    ;; But shared history up to branch point
    (is (= (take 3 (get-events game-a))
           (take 3 (get-events game-b))))))
```

### What We Don't Test

- **UI component rendering** — Reagent components are thin; testing subscriptions covers the logic. A few key view tests exist for battlefield, mana pool, and opponent display.
- **End-to-end browser tests** — High maintenance, low signal for a single-player tool.
- **Visual regression** — Overkill for an MVP. Consider later if UI churn becomes a problem.

### Actual Test Infrastructure (as built)

| Metric | Value |
|--------|-------|
| Test files | 104 |
| Tests | 1779 |
| Assertions | 4429 |

| Area | Files | Coverage |
|------|-------|----------|
| `cards/` | 25 | Per-card scenario tests (every implemented card) |
| `engine/` | 25 | Mana, effects, stack, costs, grants, targeting, triggers, SBAs, priority, resolution |
| `events/` | 28 | Game events, selection flows (6 domain modules), abilities |
| `bots/` | 5 | Bot protocol, decision rules, integration tests |
| `history/` | 6 | Fork tree, branch operations, step navigation |
| `subs/` | 4 | Subscription derivations |
| `views/` | 6 | Battlefield, mana pool, opponent display, zone counts |
| `db/` | 3 | Schema validation, queries |

| Tool | Purpose |
|------|---------|
| `cljs.test` | Built-in test framework |
| `shadow-cljs` | Test runner (`make test`) |
| `clj-kondo` | Linter (`make lint`) |
| `cljstyle` | Formatter (`make fmt`) |

**Validation workflow:** `make validate` runs lint + format-check + tests in sequence. Always run this, not just `make test`.

### Property-Based Tests

The original design proposed `test.check` for property-based testing. This has **not been implemented** — all 1779 tests are example-based. The invariants listed (zone exclusivity, mana non-negative, storm monotonic, card conservation) are tested via targeted unit tests rather than generative testing. Revisiting property-based tests could add value for edge case discovery but hasn't been a priority.

---

## 13. Design Retrospective

This section reflects on design choices after implementing the system (~4000 lines of engine, ~3100 lines of events, ~520 lines of bot logic, 32 cards, 104 test files, 1779 tests). What worked, what didn't, what we'd reconsider.

### Validated Choices

**re-frame event sourcing — Excellent fit.** The event model gave us fork/replay almost for free, exactly as predicted. The interceptor system was perfect for auto-capturing history entries *and* for driving bot turns automatically. Subscriptions cleanly compute derived state (castable cards, threshold status, flashback availability). This was the best decision in the original design, and it scaled well to the bot system — bots dispatch the same events as human players.

**Datascript for game state — Good, with caveats.** Entity refs make card-object-player relationships clean. Datalog queries are expressive for "find all objects in zone X owned by player Y with type Z." However, Datascript adds cognitive overhead for simple operations — updating a counter or moving a card between zones requires understanding entity IDs, transactions, and pull syntax. A plain nested map might have been simpler for 80% of operations, but Datascript's query power pays off for the complex 20% (targeting validation, flashback detection, etc.). **Keep it.** The trigger migration from atoms into Datascript further validated this — having triggers as queryable entities simplified registration and cleanup.

**Data-driven cards — Validated.** Cards as EDN data with a multimethod effect interpreter was the right call. Adding a new card means defining data and maybe a new effect type, not writing card-specific engine code. The 20+ effect types cover a wide range of MTG effects. The burn bot's Lightning Bolt works through the exact same engine path as a player-cast spell.

**Immutability for fork — Validated.** Structural sharing means keeping history snapshots is cheap. The fork tree works exactly as hoped.

**Bots use the same engine as players — Validated.** The decision to have bots dispatch re-frame events through the full engine path (not a separate "bot resolution" shortcut) was critical. Bot spells go on the stack, get resolved by the same multimethod, trigger the same state-based actions. This means any card that works for the player automatically works for the bot. The bot interceptor is ~250 lines of coordination, not a parallel game engine.

### Intentional Deviations

**Snapshot-based history instead of event replay.** The design proposed reducing events 0..N to reconstruct state. We store full Datascript db values at each step instead. Tradeoff: more memory, but instant state access. For a practice tool where you frequently rewind, this was clearly correct. Event replay would require deterministic reduction (tricky with random elements like library shuffling) and would be slow for deep rewinds.

**Unified stack-item entity instead of separate types.** The design had separate stack entry types (spell, ability, trigger). We use a single `:stack-item/*` entity with a `:stack-item/type` discriminator. This simplified queries, schema, and the resolution loop significantly. One `get-top-stack-item` function handles everything. The unified resolution multimethod (`engine/resolution.cljs`) dispatches on `:stack-item/type` with 5 defmethods.

**Selection decomposition instead of monolithic file.** The original `selection.cljs` was ~1540 lines. Rather than splitting by selection type (tutor.cljs, discard.cljs), we decomposed by domain concern: `core.cljs` (builders/toggle/confirm), `library.cljs` (tutor/scry/peek), `targeting.cljs` (player/object targets), `costs.cljs` (mana allocation/exile), `zone_ops.cljs` (graveyard return/discard), `storm.cljs` (storm copy targeting). The thin `selection.cljs` dispatches to these modules. This preserved the overview of how selections interact while making each file focused.

**Grants system (emergent).** Not in the original design. Emerged from needing temporary effects: flashback grants alternate costs, Orim's Chant grants casting restrictions, Recoup grants flashback to graveyard cards. The grants system (`engine/grants.cljs`) handles all of these with turn/phase-based expiration.

**Trigger registry in Datascript (evolved).** The design sketched triggers for Gaea's Blessing but didn't design a general system. The first implementation used an atom-based registry. This was later migrated into Datascript (`engine/trigger_db.cljs`) for immutability — triggers are now queryable entities that participate in snapshots, so fork/replay correctly captures trigger state. Event dispatch routing in `engine/trigger_dispatch.cljs` handles the coordination.

**Priority system with bot integration.** The design originally said "skip priority." Implementing bots required a real priority system — both players must pass for phases to advance. The priority engine (`engine/priority.cljs`) handles yield, yield-all, hold priority, and phase stops. Goldfish auto-passes everything. The burn bot checks for available spells during its priority windows.

### Choices to Revisit

**No property-based tests.** The design proposed `test.check` for invariants like zone exclusivity and card conservation. We have 1779 example-based tests instead. Property-based tests could catch edge cases we haven't thought of, particularly around mana payment with X costs, zone transitions during complex effect chains, and stack interactions. Worth adding for the engine core.

**Scryfall images — still absent.** The design included Scryfall integration for card images. Cards are text-only in the UI. This is fine for experienced players (the target audience knows what Dark Ritual does) but would improve the experience significantly. Low implementation effort; should probably just do it.

**PWA/offline — still absent.** The design specified Workbox for service worker support. Not implemented and probably not needed yet — the app works fine as a regular web page. Revisit if users want to install it as an app.

**Bot configuration UX — open question.** The bot engine supports robust rule-based behavior, but the current setup UI is a simple Goldfish/Burn dropdown. The vision is configurable scenarios: specify opponent deck, starting hand, and behavior rules. The challenge is exposing this power without overwhelming the user. Needs its own design session before the card expansion phase.

**Combat — intentionally minimal.** The phase exists for the turn structure but there's no creature combat. This is fine for storm (you rarely attack with creatures). When adding creature-based bot scenarios (Phyrexian Dreadnought clock, Xantid Swarm), basic combat will be needed. This is tracked as a separate epic (fizzle-u07p).

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| **Goldfish** | Practice against a non-interactive opponent |
| **Storm** | Keyword that copies a spell for each spell cast before it this turn |
| **Threshold** | Having 7+ cards in your graveyard |
| **IGG** | Ill-Gotten Gains |
| **LED** | Lion's Eye Diamond |
| **Fork** | Create a branch in the game history to try a different line |
| **Flores Technique** | Starting practice games with specific cards in hand |

---

## Appendix B: References

- [The EPIC Storm](https://theepicstorm.com/) - Legacy storm resources
- [Premodern](https://premodernmagic.com/) - Format rules and resources
- [re-frame](https://day8.github.io/re-frame/) - ClojureScript framework docs
- [Datascript](https://github.com/tonsky/datascript) - In-memory database docs

---

## Appendix C: Firebase vs Supabase Deep Dive

Since cloud sync is a future feature, we have time to make this decision carefully. Here's the analysis:

### The Core Question for Fizzle

Our use case is specific:
- **Primary mode**: Offline-first local practice tool
- **Future feature**: Share puzzles/tactics with community
- **Data shape**: Game states (complex nested structures), puzzles, user preferences
- **Query patterns**: Mostly key-value lookups, occasional "list puzzles by tag"

### Comparison Matrix

| Factor | Firebase | Supabase | Winner for Us |
|--------|----------|----------|---------------|
| **Offline Support** | Built-in, battle-tested. One line: `enablePersistence()`. Automatic sync. | No native offline. Requires PowerSync or custom solution. | 🏆 Firebase |
| **Data Model Fit** | NoSQL documents. Game state as JSON blob works well. | PostgreSQL tables. Would need JSONB column for game state. | Firebase (slight edge) |
| **ClojureScript Integration** | Several wrappers exist (re-frame-firebase, firemore). Direct JS interop works fine. | No wrappers. Direct JS interop via `@supabase/supabase-js`. | Tie |
| **Query Power** | Limited. No joins. Would need denormalization. | Full SQL. Complex puzzle queries trivial. | 🏆 Supabase |
| **Vendor Lock-in** | High. Proprietary. Migration painful. | Low. Open source. Self-hostable. Standard Postgres. | 🏆 Supabase |
| **Pricing Predictability** | Pay per read/write. Can spike unexpectedly. | Tiered. More predictable. | 🏆 Supabase |
| **Free Tier** | Generous: 1GB storage, 50K reads/day | Decent: 500MB, unlimited API | Firebase (slight edge) |
| **Maturity** | 10+ years. Very stable. | 5 years. Rapidly improving. | Firebase |
| **Community/Ecosystem** | Huge. Tons of examples. | Growing fast. Less ClojureScript-specific content. | Firebase |

### The Offline Problem

This is the critical differentiator. Firebase's offline support is genuinely excellent:

```javascript
// Firebase: One line enables offline
firebase.firestore().enablePersistence()

// Now all reads/writes work offline automatically
// Syncs when back online. Conflict resolution built-in.
```

Supabase has **no equivalent**. Their official stance is "use PowerSync" (a third-party service) or build your own with IndexedDB + custom sync logic. For a practice tool where offline is the primary use case, this is a significant gap.

**However**, for Fizzle specifically:

1. **v1 is offline-only anyway** — We're using localStorage/IndexedDB directly
2. **Cloud sync is for sharing puzzles** — Not for core gameplay
3. **Puzzle data is small** — We can cache aggressively on the client
4. **No real-time collaboration needed** — Users don't edit puzzles simultaneously

This means Supabase's offline weakness matters less for our use case than it would for, say, a collaborative note-taking app.

### ClojureScript Integration Reality

**Firebase:**
- `re-frame-firebase` exists but is dated (last updated 2021)
- `firemore` is more modern and has nice Clojure idioms
- Direct JS interop via shadow-cljs works well
- Example:

```clojure
(ns fizzle.firebase
  (:require ["firebase/app" :as firebase]
            ["firebase/firestore" :as firestore]))

(defn init! [config]
  (firebase/initializeApp (clj->js config)))

(defn save-puzzle! [puzzle]
  (-> (firestore/getFirestore)
      (firestore/collection "puzzles")
      (firestore/addDoc (clj->js puzzle))))
```

**Supabase:**
- No ClojureScript wrappers exist
- Direct JS interop works identically well:

```clojure
(ns fizzle.supabase
  (:require ["@supabase/supabase-js" :refer [createClient]]))

(def client (createClient supabase-url supabase-key))

(defn save-puzzle! [puzzle]
  (-> client
      (.from "puzzles")
      (.insert (clj->js puzzle))))
```

**Verdict**: Both work fine with shadow-cljs. No meaningful difference.

### Recommendation

**Start with localStorage + EDN files for v1.**

This gives us:
- Zero external dependencies
- True offline-first
- Portable puzzle format (EDN files can be shared via gist, email, etc.)
- Time to evaluate as both platforms evolve

**For v2 cloud sync, lean toward Supabase because:**

1. **Open source alignment** — Clojure community values this
2. **SQL for puzzle queries** — "Find puzzles tagged 'igg-line' with difficulty > 3" is trivial
3. **Self-hosting option** — Community could run their own instance
4. **Data portability** — Easy to migrate if needed
5. **We can solve offline ourselves** — Since v1 is localStorage-based anyway, we already have offline. Cloud sync just needs to push/pull puzzle EDN blobs.

**Practical architecture for v2:**

```
┌─────────────────────────────────────────────────────┐
│                    Browser                          │
├─────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────────────────┐    │
│  │ Datascript  │    │  localStorage / IDB     │    │
│  │ (game state)│    │  (puzzle cache)         │    │
│  └─────────────┘    └───────────┬─────────────┘    │
│                                 │                   │
│                      ┌──────────▼──────────┐       │
│                      │   Sync Manager      │       │
│                      │   - Pull new puzzles│       │
│                      │   - Push my puzzles │       │
│                      │   - Conflict: last  │       │
│                      │     write wins      │       │
│                      └──────────┬──────────┘       │
└─────────────────────────────────┼───────────────────┘
                                  │ (when online)
                                  ▼
                         ┌────────────────┐
                         │   Supabase     │
                         │  - puzzles     │
                         │  - users       │
                         │  - puzzle_tags │
                         └────────────────┘
```

This hybrid approach gives us Firebase-quality offline (because we built it) with Supabase's query power and openness for the cloud layer.

---

## Appendix D: Complex Stack Interactions (Gaea's Blessing)

### The Problem

Gaea's Blessing is a common sideboard card against storm. When milled from library to graveyard, it triggers and shuffles the graveyard back into the library. This creates a critical interaction:

```
Storm player casts Brain Freeze (storm count = 20)
→ 21 copies on stack, each targeting opponent
→ Copy 1 resolves: mill 3 (Blessing is card #2)
→ Blessing trigger goes on stack!
→ Trigger resolves: opponent shuffles GY into library
→ Copy 2 resolves: mill 3 (might hit Blessing again!)
→ ...infinite loop potential
```

To beat Gaea's Blessing, the storm player must either:
1. **Build enough storm** that total mill > library size in one resolution
2. **Tormod's Crypt in response** to the Blessing trigger
3. **Win through Hunting Pack** instead of Brain Freeze

This requires modeling:
- Triggered abilities going on the stack
- Players responding to triggers
- Stack ordering (LIFO)
- The shuffle resolving mid-storm-copies

### Stack Model (as implemented)

The unified `:stack-item` entity handles this. Gaea's Blessing would register a trigger via the trigger registry when in a zone, and zone-change events from milling would fire `trigger_dispatch.cljs` to create a new stack-item for the trigger:

```clojure
;; Gaea's Blessing trigger definition (on the card)
{:trigger/type :zone-change
 :trigger/from :library
 :trigger/to :graveyard
 :trigger/uses-stack? true   ; Goes on stack (can be responded to)
 :trigger/effects [{:effect/type :shuffle-graveyard-into-library
                    :effect/target :owner}]}

;; When milled, trigger_dispatch.cljs creates:
{:stack-item/type :triggered-ability
 :stack-item/source blessing-eid
 :stack-item/controller opponent-eid
 :stack-item/effects [{:effect/type :shuffle-graveyard-into-library ...}]
 :stack-item/description "Gaea's Blessing trigger"}
;; This goes on top of remaining Brain Freeze copies (LIFO)
```

### Stack Resolution Flow

```clojure
(defn resolve-stack-top
  "Resolve the top item on the stack, checking for triggers."
  [db]
  (let [top-entry (peek-stack db)
        db-after-resolve (execute-stack-entry db top-entry)
        new-triggers (check-triggers db db-after-resolve)]
    (if (seq new-triggers)
      ;; Triggers go on stack, APNAP order (Active Player, Non-Active Player)
      (reduce #(push-stack %1 %2) db-after-resolve new-triggers)
      db-after-resolve)))

(defn check-triggers
  "Check for any triggered abilities from state change."
  [db-before db-after]
  (let [zone-changes (diff-zones db-before db-after)]
    (for [change zone-changes
          :let [obj (get-object db-after (:object-id change))
                card (get-card db-after obj)
                triggers (:card/triggers card)]
          trigger triggers
          :when (trigger-matches? trigger change)]
      (create-trigger-entry trigger obj))))

;; Gaea's Blessing trigger check
(defn trigger-matches?
  [{:keys [trigger/type trigger/from trigger/to]} zone-change]
  (and (= type :zone-change)
       (= from (:from zone-change))
       (= to (:to zone-change))))
```

### Priority System (Simplified)

We don't need full MTG priority, but we need enough for "respond to trigger":

```clojure
;; After anything goes on the stack, check if player wants to respond
(defn offer-priority
  "Offer the player a chance to respond to the stack."
  [db]
  (if (empty? (get-stack db))
    db  ; Nothing on stack, proceed
    (assoc db :pending-choice
           {:type :priority-decision
            :player (get-priority-player db)
            :options [:pass :respond]
            :stack-top (peek-stack db)})))

;; UI shows:
;; "Gaea's Blessing trigger on stack. Shuffle graveyard into library."
;; [Pass] [Respond with Tormod's Crypt]
```

### Tormod's Crypt Interaction

```clojure
(def tormods-crypt
  {:card/id :tormods-crypt
   :card/name "Tormod's Crypt"
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/timing :instant-speed  ; Can respond to triggers!
                     :ability/cost {:cost/tap true
                                    :cost/sacrifice :self}
                     :ability/effects [{:effect/type :exile-graveyard
                                        :effect/target :chosen-player}]}]})

;; When Crypt resolves BEFORE Blessing trigger:
;; 1. Blessing is exiled (it was in the graveyard)
;; 2. Blessing trigger fizzles (source no longer exists? Or still resolves but GY is empty)

;; Note: In actual MTG rules, the Blessing trigger still resolves,
;; but shuffling an empty graveyard does nothing useful.
;; Critically: Blessing itself got exiled, so future mills don't trigger it.
```

### Implementation Priority

For Phase 2 (Iggy Pop complete), we need:

1. **Basic triggered abilities** — Just Gaea's Blessing style "on zone change" triggers
2. **Stack with multiple entry types** — Spells, copies, triggers
3. **Simple priority** — "Respond? Yes/No" after each stack addition
4. **Trigger checking** — On zone changes, check for matching triggers

We explicitly **don't need** for v1:
- State-based triggers (complex)
- Replacement effects
- Full APNAP ordering (single-player tool)
- Layers (no continuous effects)

### Bot Integration for Blessing

The control/anti-storm bot should be able to represent Gaea's Blessing:

```clojure
(def anti-storm-control-deck
  (concat
    (repeat 20 island)
    (repeat 12 counterspell)
    (repeat 4 gaeas-blessing)  ; The key anti-storm tech
    (repeat 24 filler-cards)))

;; Bot behavior when Blessing triggers:
;; - Always let it resolve (good for bot)
;; - Bot doesn't need to make choices for Blessing

;; Player must decide: try to Crypt in response, or accept the shuffle
```

### Test Scenarios

```clojure
;; Test: Brain Freeze kills through single Blessing
(deftest brain-freeze-vs-single-blessing
  (let [game (-> (new-game)
                 (setup-opponent-library 
                   ;; 20 cards, Blessing at position 5
                   (concat (repeat 4 :filler)
                           [:gaeas-blessing]
                           (repeat 15 :filler)))
                 (set-storm-count 20)
                 (cast-brain-freeze :target :opponent))]
    ;; After all copies resolve...
    ;; Mill 3 * 21 = 63 cards (if no interruption)
    ;; But Blessing triggers when milled, shuffles back
    ;; With 20-card library, we mill 15 before hitting Blessing
    ;; Blessing shuffles ~15 cards back, we have 6 copies left
    ;; 6 * 3 = 18 more mills, might hit Blessing again!
    ;; This is actually a race condition / probability problem
    (is (= :lost (:game/result game)))))  ; Can't win through 1 Blessing with storm 20

(deftest crypt-beats-blessing
  (let [game (-> (new-game)
                 (put-on-battlefield :tormods-crypt)
                 (setup-opponent-library [...blessing at position 5...])
                 (set-storm-count 10)
                 (cast-brain-freeze :target :opponent)
                 ;; After first copy resolves, Blessing triggers
                 ;; Player activates Crypt in response
                 (respond-with :activate-crypt :target :opponent))]
    ;; Crypt exiles their graveyard (including Blessing)
    ;; Blessing trigger resolves but shuffles empty GY
    ;; Remaining Brain Freeze copies mill them out
    (is (= :won (:game/result game)))))
```

This is exactly the kind of interaction that makes storm practice interesting and justifies building a dedicated tool rather than just using Cockatrice.

---

*Document version: 0.3.0*
*Updated: February 2026*
*For use with Claude Code to implement Fizzle*
