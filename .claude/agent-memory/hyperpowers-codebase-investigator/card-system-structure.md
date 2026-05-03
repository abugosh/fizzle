# Fizzle Card System Architecture

## Overview
The Fizzle card system is a data-driven architecture where cards are defined as pure EDN data structures interpreted by game engine rules. ADR-010 requires routing all card pool access through the Interpretation Core to maintain a stable interface.

## 1. Card Definitions: Structure and Location

### File Organization
- **Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/`
- **Files**: 11 ClojureScript modules
- **Total Card Definitions**: 31 unique card definitions

### Card Definition Files
| File | Purpose | Examples |
|------|---------|----------|
| `iggy_pop.cljs` | Core Iggy Pop deck (20 cards + 1 decklist struct) | Dark Ritual, Brain Freeze, Cabal Ritual, city-of-brass, etc. |
| `basic_lands.cljs` | Basic lands for bot decks | plains, mountain, forest |
| `deep_analysis.cljs` | Single card with flashback | Deep Analysis |
| `flash_of_insight.cljs` | Single card | Flash of Insight |
| `lightning_bolt.cljs` | Single targeted spell | Lightning Bolt |
| `cephalid_coliseum.cljs` | Single land | Cephalid Coliseum |
| `ill_gotten_gains.cljs` | Single spell | Ill-Gotten Gains |
| `orims_chant.cljs` | Single spell | Orim's Chant |
| `ray_of_revelation.cljs` | Single spell | Ray of Revelation |
| `recoup.cljs` | Single spell | Recoup |
| `seal_of_cleansing.cljs` | Single spell | Seal of Cleansing |

### Card Definition Structure
Every card is a Clojure map with required and optional fields:

```clojure
{:card/id :lightning-bolt              ; Unique keyword identifier
 :card/name "Lightning Bolt"           ; Display name (string)
 :card/cmc 1                           ; Converted mana cost (integer)
 :card/mana-cost {:red 1}              ; Mana cost map {:color amount, ...}
 :card/colors #{:red}                 ; Set of colors
 :card/types #{:instant}              ; Set of types (#{:land :instant :sorcery ...})
 :card/text "Lightning Bolt deals..."  ; Rules text (string, for display)

 ; Optional fields:
 :card/subtypes #{:mountain}          ; Set of subtypes (#{:island :swamp ...})
 :card/supertypes #{:basic}           ; Set of supertypes (#{:basic :legendary})
 :card/keywords #{:storm}             ; Set of keywords (#{:storm :threshold})
 :card/effects [{...}]                ; Vector of effect maps (cast-time effects)
 :card/targeting [{...}]              ; Vector of targeting specs
 :card/abilities [{...}]              ; Vector of activated ability specs
 :card/etb-effects [{...}]            ; Vector of enter-battlefield effects
 :card/triggers [{...}]               ; Vector of triggered ability specs
 :card/alternate-costs [{...}]        ; Vector of alternate casting costs (flashback, etc.)
 :card/conditional-effects {:threshold [...]} ; Map of condition -> effects
 :card/kicker {:red 1}                ; Kicker cost (optional)
 :card/kicked-effects [{...}]         ; Effects when kicked
}
```

### Effect System
Effects are data structures that the engine interprets:

```clojure
{:effect/type :add-mana
 :effect/mana {:black 3}}

{:effect/type :mill
 :effect/amount 3
 :effect/target :any-player}

{:effect/type :deal-damage
 :effect/amount 3
 :effect/target-ref :target}
```

**Valid effect types**: `:add-mana`, `:mill`, `:lose-life`, `:gain-life`, `:deal-damage`, `:add-counters`, `:draw`, `:exile-self`, `:discard-hand`, `:return-from-graveyard`, `:sacrifice`, `:destroy`, `:discard`, `:tutor`, `:scry`, `:peek-and-select`, `:grant-flashback`, `:add-restriction`, `:storm-copies`

## 2. Card Aggregation

### The Aggregation File: iggy_pop.cljs (Lines 349-359)

```clojure
(def all-cards
  [dark-ritual brain-freeze cabal-ritual city-of-brass city-of-traitors
   gemstone-mine island swamp underground-river lotus-petal lions-eye-diamond
   careful-study mental-note opt merchant-scroll intuition polluted-delta
   basic-lands/plains basic-lands/mountain basic-lands/forest
   deep-analysis/deep-analysis cephalid-coliseum/cephalid-coliseum recoup/recoup
   ill-gotten-gains/ill-gotten-gains lightning-bolt/lightning-bolt orims-chant/orims-chant
   ray-of-revelation/ray-of-revelation seal-of-cleansing/seal-of-cleansing
   flash-of-insight/flash-of-insight])

(def iggy-pop-decklist
  {:deck/id :iggy-pop
   :deck/name "Iggy Pop"
   :deck/main [{:card/id :dark-ritual :count 4}
               ...]
   :deck/side [{:card/id :merchant-scroll :count 2}
               ...]})
```

### Aggregation Pattern
- Each card definition file can define one or multiple cards as module-level `def`s
- `iggy_pop.cljs` imports all other card files
- `iggy_pop.cljs` exports an `all-cards` vector (unordered collection of all 31 card definitions)
- `iggy_pop.cljs` exports an `iggy-pop-decklist` map defining the main deck (22 card entries) and sideboard (9 card entries)

### Lookup Map
The Interpretation Core builds a lookup map:
```clojure
(def card-by-id
  (into {} (map (juxt :card/id identity) all-cards)))
```

This allows `O(1)` lookup of card definitions by `:card/id` keyword.

## 3. Card Pool Consumers (Architectural Routing)

### Current Pattern (as of Feb 2026)
According to ADR-010, card pool access should flow through Interpretation Core:

```
Presentation/Subscriptions
            ↓
    Interpretation Core (fizzle.engine.cards)
            ↓
    Card Pool (fizzle.cards.iggy-pop)
```

### Interpretation Core: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/cards.cljs`

```clojure
(ns fizzle.engine.cards
  (:require [fizzle.cards.iggy-pop :as iggy-pop]))

(def all-cards
  "All card definitions available in the card pool."
  iggy-pop/all-cards)

(def card-by-id
  "Lookup map from :card/id keyword to card definition."
  (into {} (map (juxt :card/id identity) all-cards)))
```

### Direct Consumers

1. **fizzle.events.game** (Line 5)
   - Import: `[fizzle.cards.iggy-pop :as cards]`
   - Usage (Line 147): `(card-spec/validate-cards! cards/all-cards)` at game initialization
   - Usage (Line 149): `(d/transact! conn cards/all-cards)` — loads all cards into Datascript DB
   - Usage (Line 190): `(or config {:main-deck (:deck/main cards/iggy-pop-decklist)})` — default deck

2. **fizzle.events.setup** (Line 5)
   - Import: `[fizzle.cards.iggy-pop :as cards]`
   - Usage (Line 47): `(:deck/main cards/iggy-pop-decklist)` — init setup
   - Usage (Line 64): `(:deck/main cards/iggy-pop-decklist)` — select deck

3. **fizzle.subs.setup** (Line 4) ✓ ROUTED THROUGH ENGINE
   - Import: `[fizzle.engine.cards :as cards]` — Uses Interpretation Core
   - Usage (Line 81): `(get cards/card-by-id id)` — lookup card by ID
   - Usage (Line 104): `(get cards/card-by-id card-id)` — lookup card name

### ADR-010 Violation Status
- ✗ `fizzle.events.game` — Directly imports cards/iggy-pop (not routed)
- ✗ `fizzle.events.setup` — Directly imports cards/iggy-pop (not routed)
- ✓ `fizzle.subs.setup` — Correctly uses engine.cards

The subscription layer correctly follows ADR-010, but the event layer violates it.

## 4. Setup Screen: Deck Presentation

### Deck Selection Flow
1. **Initialization** (`fizzle.events.setup/init-setup-handler`, Line 40)
   - Loads `:iggy-pop` as the default selected deck
   - Loads deck main/sideboard from `cards/iggy-pop-decklist`

2. **Available Decks** (`fizzle.subs.setup/available-decks`, Line 22)
   - Returns a vector of deck info maps
   - Hard-coded deck: `{:deck/id :iggy-pop :deck/name "Iggy Pop"}`
   - Plus any imported decks from localStorage

3. **Deck Selection** (`fizzle.events.setup/select-deck-handler`, Line 57)
   - Only hard-coded case: `:iggy-pop`
   - Falls back to checking `:setup/imported-decks` for other deck IDs

### Card Lookup in Setup
The setup view uses `fizzle.subs.setup/current-main-grouped` (Line 76):
- Fetches current main deck from app-db
- For each card entry `{:card/id :count}`:
  - Looks up card via `(get cards/card-by-id id)`
  - Extracts `:card/name` and `:card/types`
  - Groups by card type (land, instant, sorcery, artifact, creature, enchantment, other)

## 5. Datascript Schema for Cards

### Card Entity Schema (`/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs`, Lines 12-28)

```clojure
:card/id         {:db/unique :db.unique/identity}  ; Unique keyword per card
:card/name       {:db/index true}                  ; Indexed for queries
:card/cmc        {}                                 ; Integer, no special attributes
:card/mana-cost  {}                                 ; Map: {:black 1 :generic 2}
:card/colors     {:db/cardinality :db.cardinality/many}    ; Set of colors
:card/types      {:db/cardinality :db.cardinality/many}    ; Set of types
:card/subtypes   {:db/cardinality :db.cardinality/many}    ; Set of subtypes
:card/supertypes {:db/cardinality :db.cardinality/many}    ; Set of supertypes
:card/text       {}                                 ; String, for display
:card/effects    {}                                 ; Vector of effect maps (EDN)
:card/abilities  {}                                 ; Vector of activated abilities
:card/etb-effects  {}                              ; Vector of ETB effects
:card/triggers   {}                                 ; Vector of trigger specs
:card/keywords   {:db/cardinality :db.cardinality/many}    ; Set of keywords
```

### Game Object Entity Schema (`db/schema.cljs`, Lines 30-44)

Objects are game instances of cards:
```clojure
:object/id         {:db/unique :db.unique/identity}
:object/card       {:db/valueType :db.type/ref}    ; Reference to card definition
:object/zone       {}                               ; :hand :stack :graveyard :battlefield
:object/owner      {:db/valueType :db.type/ref}    ; Owning player
:object/controller {:db/valueType :db.type/ref}    ; Controlling player
:object/tapped     {}                               ; Boolean
:object/counters   {}                               ; Map: {:charge 3}
:object/position   {}                               ; Position in zone
:object/is-copy    {}                               ; Boolean (storm copies)
:object/grants     {}                               ; Vector of temporary abilities
```

### Data Loading Flow
1. Game initialization calls `(card-spec/validate-cards! cards/all-cards)` — validates all 31 cards
2. Loads all cards into Datascript: `(d/transact! conn cards/all-cards)`
3. Creates game objects that reference card entities via `:object/card` ref
4. Queries can now join objects to cards to retrieve definitions

## 6. ADR-010: Interpretation Core Routing Requirement

**File**: `/Users/abugosh/g/fizzle/doc/arch/adr-010.md`

**Decision**: All Card Pool access must route through Interpretation Core, not import directly.

**Current Status**:
- Presentation (subscriptions): ✓ Correctly uses `fizzle.engine.cards`
- Game events: ✗ Still imports `fizzle.cards.iggy-pop` directly

**Future Impact**: As the card pool grows (new sets, new definitions), the stable Interpretation Core interface allows presentation and events to evolve independently of how card data is physically organized or stored.

## 7. Card Validation System

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs`

Uses cljs.spec.alpha to validate card definitions at load time:
- Validates all 31 cards when `init-game-state` runs
- Throws `ex-info` with card name and spec violations on error
- Catches typos and missing fields before silent runtime failures

### Key Validation Rules
- All required fields must be present and correct type
- Effect types must be in: `#{:add-mana :mill :draw :deal-damage ...}`
- Card types must be sets of: `#{:land :instant :sorcery :artifact :creature :enchantment}`
- Colors must be: `#{:white :blue :black :red :green}`
- Mana costs are maps with color keys and nat-int values

## Summary: Current vs. Intended Architecture

### How It Works Today (Feb 2026)
1. Events load all cards from `fizzle.cards.iggy-pop` ✗ (not routed)
2. Subscriptions load cards via `fizzle.engine.cards` ✓ (routed)
3. 31 card definitions in 11 files, aggregated in `iggy_pop.cljs`
4. Only one hard-coded deck available: `:iggy-pop` (can import others)

### ADR-010 Compliance Path
1. Move direct imports from events to use `fizzle.engine.cards` instead
2. Extend Interpretation Core with any needed query functions
3. Single consumer (Interpretation Core) of Card Pool enables future expansion

### Expansion Ready
- Card system is designed for growth (11→N files)
- Aggregation pattern is simple to extend
- Deck parsing system exists for imported decks
- Schema and specs are already extensible
