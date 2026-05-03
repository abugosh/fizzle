# Snapshot Sharing Feature - Codebase Investigation Report

## Executive Summary

This report details the exact data structures, code paths, and file locations needed to implement a snapshot sharing feature for Fizzle. All findings are verified through direct code inspection with specific line numbers and file paths.

---

## 1. Card Registry Structure

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs` (lines 1-147)

### Registration Pattern
- **Individual cards**: Imported by namespace alias, then referenced in `all-cards` vector (lines 75-141)
- **Cycle files**: Return a `cards` vector (e.g., basic_lands, pain_lands, fetch_lands, medallions) which is concatenated into `all-cards` (line 146)

### Data Structure
```clojure
(def all-cards
  "All card definitions available in the card pool."
  (vec
    (concat
      ;; Individual cards - 141 cards
      [dark-ritual/card ...]
      ;; Cycle cards
      basic-lands/cards      ; 5 cards
      pain-lands/cards       ; 10 cards
      fetch-lands/cards      ; 10 cards
      medallions/cards)))    ; 4 cards
```

### Card Referencing
- Cards are referenced by `:card/id` keywords (e.g., `:dark-ritual`, `:brain-freeze`)
- Card definitions are **immutable during game** and loaded into Datascript via `engine/cards.cljs` (ADR-010)
- No dynamic card lookups — all 50+ cards are pre-loaded into the registry

### Current Card Count
- 47 card definitions: 22 individual cards + 3 cycle files
- Accessed via `engine/cards.cljs` sole gateway (not directly from registry)

---

## 2. Game State Schema (Datascript)

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` (lines 1-113)

### Complete Entity Types and Attributes

#### Card Definitions (Templates - Immutable)
Lines 12-29:
```clojure
:card/id           {:db/unique :db.unique/identity}  ; Keyword identifier
:card/name         {:db/index true}
:card/cmc          {}
:card/mana-cost    {}
:card/colors       {:db/cardinality :db.cardinality/many}
:card/types        {:db/cardinality :db.cardinality/many}
:card/subtypes     {:db/cardinality :db.cardinality/many}
:card/supertypes   {:db/cardinality :db.cardinality/many}
:card/text         {}
:card/effects      {}  ; EDN vector
:card/abilities    {}  ; EDN vector
:card/etb-effects  {}  ; EDN vector
:card/triggers     {}  ; EDN vector
:card/state-triggers {}
:card/keywords     {:db/cardinality :db.cardinality/many}
```

#### Game Objects (Instances - Mutable)
Lines 31-54:
```clojure
:object/id          {:db/unique :db.unique/identity}  ; UUID
:object/card        {:db/valueType :db.type/ref}      ; Reference to card def
:object/zone        {}  ; :hand :stack :graveyard :battlefield :library :exile
:object/owner       {:db/valueType :db.type/ref}
:object/controller  {:db/valueType :db.type/ref}
:object/tapped      {}
:object/counters    {}  ; {:charge 3 :loyalty 4}
:object/position    {}  ; Position in zone (library ordering)
:object/is-copy     {}
:object/grants      {}  ; Vector of grant maps
:object/x-value     {}
:object/cast-mode   {}
:object/chosen-mode {}
:object/power       {}  ; integer — base power (creature)
:object/toughness   {}  ; integer — base toughness (creature)
:object/damage-marked   {}
:object/summoning-sick  {}
:object/attacking   {}
:object/blocking    {}  ; ref to attacker
:object/is-token    {}
:object/last-exiled-cmc {}
```

#### Players
Lines 56-68:
```clojure
:player/id              {:db/unique :db.unique/identity}
:player/name            {}
:player/life            {}
:player/mana-pool       {}  ; {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
:player/storm-count     {}
:player/land-plays-left {}
:player/max-hand-size   {}
:player/is-opponent     {}
:player/grants          {}  ; Vector of grant maps
:player/bot-archetype   {}  ; :goldfish | :burn | nil
:player/stops           {}  ; #{:main1 :main2 ...}
:player/drew-from-empty {}
```

#### Game State (Singleton)
Lines 70-82:
```clojure
:game/id            {:db/unique :db.unique/identity}
:game/turn          {}
:game/phase         {}  ; :main1 :main2 :combat :end
:game/step          {}  ; :untap :upkeep :draw
:game/active-player {:db/valueType :db.type/ref}
:game/priority      {:db/valueType :db.type/ref}
:game/winner        {:db/valueType :db.type/ref}
:game/loss-condition {}
:game/passed        {:db/cardinality :db.cardinality/many}
:game/auto-mode     {}
:game/human-player-id {}
:game/peek-result   {}
```

#### Stack Items
Lines 84-97:
```clojure
:stack-item/position    {}
:stack-item/type        {}  ; :spell :storm-copy :activated-ability :etb :permanent-tapped :land-entered
:stack-item/controller  {}
:stack-item/source      {}
:stack-item/effects     {}  ; Vector of effect maps
:stack-item/targets     {}  ; Map of targeting choices
:stack-item/description {}
:stack-item/is-copy     {}
:stack-item/cast-mode   {}
:stack-item/chosen-x    {}
:stack-item/object-ref  {:db/valueType :db.type/ref}
```

#### Triggers
Lines 99-113:
```clojure
:trigger/event-type     {}
:trigger/source         {:db/valueType :db.type/ref}
:trigger/controller     {:db/valueType :db.type/ref}
:trigger/filter         {}
:trigger/effects        {}
:trigger/description    {}
:trigger/uses-stack?    {}
:trigger/always-active? {}
:trigger/type           {}
:object/triggers        {:db/cardinality :db.cardinality/many :db/isComponent true}
```

---

## 3. Battlefield Card State & Permanent Attributes

**Key State Attributes** (from schema.cljs lines 36-54):

| Attribute | Type | Purpose |
|-----------|------|---------|
| `:object/zone` | keyword | Current zone location (:battlefield for permanents) |
| `:object/tapped` | boolean | Tapped/untapped status |
| `:object/counters` | map | Counter types and counts (e.g. {:charge 3}) |
| `:object/grants` | vector | Temporary abilities/costs (see grants system below) |
| `:object/power` | integer | Base power (creature cards) |
| `:object/toughness` | integer | Base toughness (creature cards) |
| `:object/damage-marked` | integer | Damage taken this turn (default 0) |
| `:object/summoning-sick` | boolean | Entered battlefield this turn |
| `:object/attacking` | boolean | Declared as attacker |
| `:object/blocking` | ref | Entity ID of attacker being blocked |
| `:object/is-token` | boolean | Token creature status |
| `:object/controller` | ref | Controlling player |
| `:object/owner` | ref | Owning player |
| `:object/position` | integer | Position in zone (library only) |
| `:object/x-value` | integer | Value of X chosen during casting |
| `:object/cast-mode` | map | Casting mode used (flashback, etc.) |
| `:object/chosen-mode` | map | Chosen spell mode (modal spells) |

### Grants System

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/grants.cljs` (lines 1-233)

**Grant Data Structure** (lines 13-17):
```clojure
{:grant/id        uuid
 :grant/type      keyword  ; :alternate-cost :ability :keyword :static-effect :restriction
 :grant/source    uuid     ; Source object ID
 :grant/expires   map      ; {:expires/turn N :expires/phase :cleanup}
 :grant/data      map}     ; The granted thing
```

**Grant Operations**:
- `add-grant(db, object-id, grant)` — adds grant to object (line 31)
- `remove-grant(db, object-id, grant-id)` — removes grant by ID (line 41)
- `get-grants-by-type(db, object-id, grant-type)` — filters grants (line 23)
- `expire-grants(db, turn, phase)` — removes expired grants (line 226)

**Expiration Format**:
```clojure
{:expires/turn N :expires/phase :cleanup}     ; Expires at turn N
{:expires/permanent true}                      ; Never expires
nil                                            ; Never expires (default)
```

**Phase Order** (line 152):
```clojure
[:untap :upkeep :draw :main1 :combat :main2 :end :cleanup]
```

**Player Grants** (lines 85-131):
- Grants can also be applied to players (e.g., "can't cast spells" restrictions)
- Same structure, stored in `:player/grants` vector
- Queried via `get-player-grants(db, player-id)`

---

## 4. Stack Items Data Structure

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` (lines 84-97)

**Complete Stack Item Entity**:
```clojure
{:stack-item/position    integer             ; LIFO ordering (higher = top)
 :stack-item/type        keyword             ; :spell :storm-copy :activated-ability :etb :permanent-tapped :land-entered
 :stack-item/controller  entity-ref          ; Player who controls this
 :stack-item/source      uuid                ; Source object entity ID
 :stack-item/effects     vector              ; Vector of effect maps [{:effect/type ...}]
 :stack-item/targets     map                 ; Map of targeting choices (resolved at resolution)
 :stack-item/description string              ; Human-readable for stack display
 :stack-item/is-copy     boolean             ; True for storm copies
 :stack-item/cast-mode   map                 ; The casting mode used (flashback, etc.)
 :stack-item/chosen-x    integer             ; Value of X chosen (pay X life, etc.)
 :stack-item/object-ref  entity-ref}         ; Reference to game object (spells only)
```

**Target Storage** (`:stack-item/targets` map):
- Stores target references resolved during casting
- Format: `{:target-id "object-uuid"}` or `{:player-target "player-1"}`
- Example from Brain Freeze: targets a player for mill effect

**Queries**:
- `get-all-stack-items(db)` — returns all items sorted by position (descending)
- `get-top-stack-item(db)` — returns highest position item

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/queries.cljs`
- Lines 136-155: Stack item queries

---

## 5. Mana Pool Representation

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` (line 60)
```clojure
:player/mana-pool {}  ; {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
```

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/init.cljs` (lines 15-22)
```clojure
(def empty-mana-pool
  "A mana pool with zero of each color."
  {:white 0
   :blue 0
   :black 0
   :red 0
   :green 0
   :colorless 0})
```

**Query Function**:
- `get-mana-pool(db, player-id)` — returns mana pool map (queries.cljs lines 31-39)

**Color Values**: `:white :blue :black :red :green :colorless`

---

## 6. Fork/History System

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/history/core.cljs` (lines 1-271)

### History Data Structure

**Initialized** (lines 4-9):
```clojure
(defn init-history []
  {:history/main []                    ; Vector of entry maps (main branch)
   :history/forks {}                   ; Map of fork-id -> fork map
   :history/current-branch nil         ; Current branch ID (nil = main)
   :history/position -1})              ; Current position in effective entries
```

### Entry Structure (lines 12-20)
```clojure
(defn make-entry
  [game-db event-type description turn & [principal]]
  {:entry/snapshot game-db             ; Immutable game state snapshot
   :entry/event-type event-type        ; Keyword: cast, play-land, resolve, etc.
   :entry/description description      ; Human-readable (e.g., "Cast Dark Ritual")
   :entry/turn turn})                  ; Turn number
   ;; Optional:
   ; :entry/principal principal         ; Player ID who took action
```

### Fork Structure (lines 99-113)
```clojure
(defn auto-fork [db entry]
  (let [fork-id (random-uuid)
        fork-name (str "Fork " (inc (count (:history/forks db))))]
    {:fork/id fork-id
     :fork/name fork-name
     :fork/branch-point position         ; Position in parent where fork branches
     :fork/parent parent-id              ; Parent branch ID (nil = from main)
     :fork/entries [entry]}))            ; Vector of new entries on fork
```

### Key Functions

| Function | Purpose |
|----------|---------|
| `append-entry(db, entry)` | Add entry to main or current branch |
| `step-to(db, position)` | Jump to position, clear UI state, restore snapshot |
| `auto-fork(db, entry)` | Create fork when editing off-tip |
| `create-named-fork(db, name)` | Create empty fork for player |
| `switch-branch(db, fork-id)` | Change active branch, restore tip |
| `pop-entry(db)` | Undo (only when at tip) |
| `effective-entries(db)` | Get all entries on current branch (main + fork entries) |
| `can-step-back?/can-step-forward?/can-pop?` | Check valid operations |

### Fork Tree Structure (lines 189-197)
```clojure
(defn fork-tree [forks]
  ; Returns nested tree: [{:fork/id ... :children [...]}]
  ; Structured for recursive rendering with parent-child relationships
```

### UI Events

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/history/events.cljs` (lines 1-59)

| Event | Handler |
|-------|---------|
| `::step-back` | Move back one position |
| `::step-forward` | Move forward one position |
| `::switch-branch` | Change to fork-id |
| `::jump-to` | Jump to absolute position |
| `::create-fork` | Create named fork |
| `::rename-fork` | Rename existing fork |
| `::delete-fork` | Delete fork and descendants |
| `::pop-entry` | Undo last action |

---

## 7. Phase/Turn State

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` (lines 70-82)

**Attributes**:
- `:game/turn` — integer, current turn number
- `:game/phase` — keyword: `:main1 :main2 :combat :end`
- `:game/step` — keyword: `:untap :upkeep :draw` (within phases)
- `:game/active-player` — entity-ref to player whose turn it is
- `:game/priority` — entity-ref to player who can act right now

**Phase Order** (from grants.cljs line 152):
```clojure
[:untap :upkeep :draw :main1 :combat :main2 :end :cleanup]
```

**Query Functions** (queries.cljs):
- `get-active-player-id(db)` — returns `:player/id` keyword (lines 112-121)
- `get-game-state(db)` — returns full game entity (lines 104-109)

---

## 8. URL/Hash Fragment Handling

**Current State**: NO EXISTING URL HANDLING

**Entry Point File**: `/Users/abugosh/g/fizzle/src/main/fizzle/core.cljs` (lines 1-132)

### App Initialization (lines 125-131)
```clojure
(defn init []
  (history-interceptor/register!)
  (bot-interceptor/register!)
  (sba-interceptor/register!)
  (rf/dispatch-sync [::setup/init-setup])
  (mount-root))
```

### HTML Entry Point
**File**: `/Users/abugosh/g/fizzle/resources/public/index.html` (lines 1-12)
```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Fizzle</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
  <div id="app"></div>
  <script src="/js/main.js"></script>  <!-- shadow-cljs bundle -->
</body>
</html>
```

**No `window.location.hash` or URL parsing currently exists.**

The app initializes by dispatching `::setup/init-setup` which loads setup screen state. To add snapshot sharing:
- Hash fragment parsing would need to happen in the `init` function (before `mount-root`)
- A new re-frame event handler could be dispatched with decoded snapshot data

---

## 9. Game Setup & Initialization

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs`

NOT FULLY READ (too large), but key exports from `events/game.cljs` lines 32-46:
```clojure
(def init-game-state init/init-game-state)
```

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/init.cljs` (lines 1-75)

### Initialization Function (lines 25-74)
```clojure
(defn init-game-state []
  (let [conn (d/create-conn schema)]
    ;; 1. Transact card definitions
    (d/transact! conn [(cards/card-by-id :dark-ritual)])

    ;; 2. Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool empty-mana-pool
                        :player/storm-count 0
                        :player/land-plays-left 1}])

    ;; 3. Get entity IDs for references
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)
          card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] @conn)]

      ;; 4. Transact game object (Dark Ritual in hand)
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :hand
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])

      ;; 5. Transact game state
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid
                          :game/human-player-id :player-1}]))

    @conn))  ; Return immutable db value
```

**Key Points**:
- Returns **immutable Datascript db value** (not a connection)
- Uses `d/create-conn schema` to create a new connection
- Transactions via `d/transact!` (mutable conn)
- Final `@conn` dereferences to get immutable db value
- Game objects are instantiated with `random-uuid` for IDs
- References use Datascript entity IDs (not UUIDs) for `:object/card`, `:object/owner`, etc.

### Card Instantiation Pattern
- Card definitions exist as `:card/id` references
- Each card instance in game is a separate object with `:object/id` UUID
- Card definition lookup via `:object/card` entity-ref

---

## 10. Views Architecture

### Main Game Screen Layout
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/core.cljs` (lines 68-92)

```clojure
(defn- game-screen []
  [:div {:class "game-grid min-h-0"}
   ;; Left sidebar: graveyard
   [collapsible-left-column "Graveyard" ::subs/gy-collapsed ...]
   ;; Center column: primary interaction
   [:div {:class "p-4 overflow-y-auto min-w-[400px]"}
    [battlefield/battlefield-view]
    [stack/stack-view]
    [battlefield/phase-bar-section]
    [hand/hand-view]
    [controls/controls-view]           ;; <-- BUTTONS HERE
    [mana-pool/unless-pay-view]
    [:div {:class "flex gap-8"}
     [mana-pool/mana-pool-view]
     [mana-pool/storm-count-view]]
    [zone-counts/zone-counts-view]]
   ;; Right sidebar: history
   [collapsible-right-column "History" ::subs/history-collapsed ... [history/history-sidebar]]])
```

### History Sidebar
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/views/history.cljs` (lines 1-222)

**Components**:
1. `step-controls` (lines 18-35) — Back/Undo/Forward buttons
2. `action-log` (lines 38-86) — Turn-grouped action log with click-to-rewind
3. `fork-controls` (lines 89-146) — Create/rename/delete forks
4. `fork-tree` (lines 166-182) — Hierarchical fork tree view
5. `history-sidebar` (lines 214-221) — Main container

**Fork Controls UI** (lines 100-104):
```clojure
[:button {:class "py-1 px-2 text-xs border border-border rounded bg-surface-hover text-perm-text cursor-pointer"
          :on-click #(rf/dispatch [::events/create-fork ...])}
 "+ New Fork"]
```

### Control Buttons Location
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/views/controls.cljs` (lines 1-83)

Current buttons:
- "Play" / "Cast" (with card name)
- "Play & Yield" / "Cast & Yield"
- "Cycle" (if applicable)
- "Yield"
- "Yield All"

**Share Button Could Go Here** — alongside these action buttons

### Header Navigation
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/core.cljs` (lines 100-111)

```clojure
[:div {:class "flex items-center gap-4 px-4 py-2 border-b border-border bg-surface-raised"}
 [:h1 {:class "text-text font-bold text-lg"} "Fizzle"]
 [:button ... "Setup"]
 [:button ... "Game"]
 (when (= screen :game)
   [:button ... "New Game"])]
```

**Alternative Location for Share Button** — header navigation area

---

## 11. View Files Overview

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/views/`

| File | Purpose |
|------|---------|
| battlefield.cljs | Permanent creatures/artifacts on battlefield |
| stack.cljs | Stack items awaiting resolution |
| hand.cljs | Player hand |
| graveyard.cljs | Graveyard view |
| mana_pool.cljs | Mana pool and storm count display |
| controls.cljs | **Play/Cast/Yield buttons** |
| history.cljs | **Fork/history/action-log sidebar** |
| modals.cljs | Selection and mode-selector overlays |
| game_over.cljs | End-game state |
| opening_hand.cljs | Mulligan screen |
| setup.cljs | Deck selection and bot config |
| phase_bar.cljs | Turn phase indicator |
| card_styles.cljs | Card rendering utilities |
| common.cljs | Shared components |
| zone_counts.cljs | Zone card counts |
| opponent.cljs | Opponent info display |
| import_modal.cljs | Deck import dialog |

---

## Example Card Definitions

### Simple Spell (Dark Ritual)
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/black/dark_ritual.cljs`
```clojure
(def card
  {:card/id :dark-ritual
   :card/name "Dark Ritual"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]})
```

### Targeted Spell (Brain Freeze)
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/brain_freeze.cljs`
```clojure
(def card
  {:card/id :brain-freeze
   :card/name "Brain Freeze"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Target player mills 3. Storm."
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]
   :card/effects [{:effect/type :mill
                   :effect/amount 3
                   :effect/target :any-player}]})
```

### Flashback Spell (Deep Analysis)
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/deep_analysis.cljs`
```clojure
(def card
  {:card/id :deep-analysis
   :card/name "Deep Analysis"
   :card/cmc 4
   :card/mana-cost {:colorless 3 :blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Target player draws two cards. Flashback—{1}{U}, Pay 3 life."
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options [:self :opponent :any-player]
                     :target/required true}]
   :card/effects [{:effect/type :draw
                   :effect/amount 2
                   :effect/target :any-player}]
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 1 :blue 1}
                           :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                           :alternate/on-resolve :exile}]})
```

### Land with Ability (City of Brass)
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/lands/city_of_brass.cljs`
```clojure
(def card
  {:card/id :city-of-brass
   :card/name "City of Brass"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "Whenever City of Brass becomes tapped, it deals 1 damage to you. {T}: Add one mana of any color."
   :card/triggers [{:trigger/type :becomes-tapped
                    :trigger/description "deals 1 damage to you"
                    :trigger/effects [{:effect/type :deal-damage
                                       :effect/amount 1
                                       :effect/target :controller}]}]
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:any 1}}]}]})
```

---

## Summary: Data Model for Snapshot Serialization

To serialize a game snapshot for sharing, you need:

1. **Game State Entities**:
   - Single `:game/id :game-1` entity with turn/phase/priority/active-player
   - All player entities (`:player/id` with all attributes)
   - All object entities (`:object/id` with card refs, zone, tapped, counters, grants, etc.)
   - All stack-item entities (`:stack-item/position` with effects/targets)

2. **Card Definitions**:
   - Full card data for all cards referenced in objects
   - Already loaded in registry, can be serialized by `:card/id` references only

3. **Transitive Data**:
   - Grants (expiration dates relative to current turn/phase)
   - Trigger entities (stored on objects via `:object/triggers`)
   - Counter maps, mana pools, etc.

4. **Immutable Value**:
   - Game state is stored as `:game/db` in app-db (immutable Datascript db value)
   - Can be serialized via `d/db->datoms` (Datascript API) or EDN serialization
   - Snapshots are stored in history entries as `:entry/snapshot game-db`

5. **Fork/History Context**:
   - Entire history structure (`:history/main`, `:history/forks`, `:history/position`)
   - Can snapshot entire app-db including history to preserve fork tree

---

## Key Implementation Considerations

1. **Immutable DB Serialization**: Game state is immutable Datascript db values — can be serialized to EDN and deserialized with `d/conn->datoms`

2. **Hash Fragment Parsing**: Need to add URL handling in `core.cljs:init` function before `mount-root`

3. **Re-frame State**: Complete snapshot includes both game state AND setup config (stashed in `:setup/stashed-config`)

4. **Fork Tree Preservation**: History structure with fork tree is separate from game state — both must be included for complete snapshot

5. **Button Placement**: Share button could go in:
   - Header (alongside Setup/Game/New Game)
   - Controls section (alongside Play/Yield buttons)
   - History sidebar (alongside fork controls)

6. **Card Registry**: All 50+ cards pre-loaded — can be serialized by `:card/id` references only during snapshot

---

END OF INVESTIGATION REPORT
