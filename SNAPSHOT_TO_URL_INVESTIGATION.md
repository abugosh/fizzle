# Snapshot-to-URL Serialization Investigation

**Investigation Date**: 2026-03-11
**Scope**: Game state structure, fork/replay system, serialization infrastructure
**Target Feature**: Enable sharing snapshots as URLs

---

## 1. Game State Shape

### 1.1 Top-Level App-DB Structure

The Fizzle app-db (re-frame subscription database) has two major sections:

**Game Screen State** (`/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs:161-169`):
```clojure
{:game/db              <Datascript DB>        ; Immutable game state (primary)
 :active-screen        :opening-hand|:game|:setup
 :opening-hand/mulligan-count 0
 :opening-hand/sculpted-ids #{}                ; UUID set of pre-drawn cards
 :opening-hand/must-contain {card-id count}    ; Pre-mulligan selection
 :opening-hand/phase   :viewing|:accepting     ; Mulligan phase
 :game/game-over-dismissed false
 :ui/stack-collapsed   false                   ; UI state only
 :ui/gy-collapsed      false
 :ui/history-collapsed false}
```

**History State** (`/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs:168-169`):
```clojure
{:history/main         []                       ; Vector of history entries (main branch)
 :history/forks        {}                       ; Map of {fork-id fork-data}
 :history/current-branch nil|fork-id            ; Currently active branch
 :history/position     -1|N                     ; Index into effective-entries}
```

**Setup Screen State** (`/Users/abugosh/g/fizzle/src/main/fizzle/events/setup.cljs:85-93`):
```clojure
{:setup/selected-deck   :iggy-pop|custom-id
 :setup/main-deck       [{:card/id :count}]     ; Deck list
 :setup/sideboard       [{:card/id :count}]
 :setup/bot-archetype   :goldfish|:burn|nil
 :setup/must-contain    {card-id count}         ; Hand sculpting
 :setup/presets         {name config}           ; Saved preset configs (from localStorage)
 :setup/last-preset     name|nil
 :setup/imported-decks  {deck-id deck-data}
 :setup/import-modal    {...}
 :active-screen         :setup}
```

**Stashed Config** (only in game screen, persists when returning to setup):
```clojure
{:setup/stashed-config {:setup/selected-deck ...
                        :setup/main-deck ...
                        :setup/sideboard ...
                        :setup/bot-archetype ...
                        :setup/must-contain ...
                        :setup/presets ...
                        :setup/last-preset ...
                        :setup/imported-decks ...}}
```

### 1.2 Datascript Game DB Structure

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs`

The Datascript `:game/db` is immutable and contains these entity types:

#### Card Entities (Template Definitions)
- **Unique identifier**: `:card/id` (keyword like `:dark-ritual`)
- **Core fields**:
  - `:card/name` (string)
  - `:card/cmc` (integer)
  - `:card/mana-cost` (map like `{:blue 1 :black 1}`)
  - `:card/colors` (set like `#{:blue :black}`)
  - `:card/types` (set like `#{:instant :sorcery}`, cardinality many)
  - `:card/text` (string, rules text)
  - `:card/effects` (EDN vector of effect maps)
  - `:card/abilities` (EDN for activated abilities, optional)
  - `:card/etb-effects` (EDN vector, optional)
  - `:card/triggers` (EDN vector of trigger maps, optional)
  - `:card/keywords` (set like `#{:storm}`, cardinality many)

#### Game Object Entities (Card Instances in Game)
- **Unique identifier**: `:object/id` (random UUID)
- **Core fields**:
  - `:object/card` (ref to card entity)
  - `:object/zone` (keyword: `:hand`, `:library`, `:graveyard`, `:battlefield`, `:stack`, `:exile`, `:sideboard`)
  - `:object/owner` (ref to player entity)
  - `:object/controller` (ref to player entity)
  - `:object/tapped` (boolean)
  - `:object/position` (integer for library ordering)
  - `:object/counters` (map like `{:charge 3}`)
  - `:object/grants` (vector of grant maps for temporary effects)
  - `:object/x-value` (integer, X cost value)
  - `:object/cast-mode` (map for flashback/alternate costs)
  - `:object/chosen-mode` (map for modal spells)
  - `:object/is-copy` (boolean)
  - `:object/power` (integer, creature base power)
  - `:object/toughness` (integer, creature base toughness)
  - `:object/damage-marked` (integer, damage taken)
  - `:object/summoning-sick` (boolean)
  - `:object/attacking` (boolean)
  - `:object/blocking` (ref to attacker's object-id)
  - `:object/is-token` (boolean)

#### Player Entities
- **Unique identifier**: `:player/id` (keyword like `:player-1` or `:opponent`)
- **Core fields**:
  - `:player/name` (string)
  - `:player/life` (integer, typically 20)
  - `:player/mana-pool` (map like `{:white 0 :blue 1 :black 0 :red 0 :green 0 :colorless 0}`)
  - `:player/storm-count` (integer)
  - `:player/land-plays-left` (integer)
  - `:player/max-hand-size` (integer, default 7)
  - `:player/is-opponent` (boolean)
  - `:player/bot-archetype` (keyword like `:goldfish`, `:burn`, or nil)
  - `:player/grants` (vector of grant maps for restrictions)
  - `:player/drew-from-empty` (boolean, SBA tracking)
  - `:player/stops` (set of phase keywords where player wants priority)

#### Game State Entity (Singleton)
- **Unique identifier**: `:game/id` (keyword `:game-1`)
- **Core fields**:
  - `:game/turn` (integer)
  - `:game/phase` (keyword: `:main1`, `:main2`, `:combat`, `:end`)
  - `:game/step` (keyword: `:untap`, `:upkeep`, `:draw`)
  - `:game/active-player` (ref to player entity)
  - `:game/priority` (ref to player entity)
  - `:game/winner` (ref to player entity or nil)
  - `:game/loss-condition` (keyword or nil)
  - `:game/passed` (set of player eids who have passed priority)
  - `:game/auto-mode` (keyword: `:resolving`, `:f6`, or nil)
  - `:game/human-player-id` (keyword `:player-1`)
  - `:game/peek-result` (string, ephemeral per resolution)

#### Stack Items (LIFO Stack)
- **Unique identifier**: `:stack-item/position` (integer, LIFO ordering)
- **Core fields**:
  - `:stack-item/type` (keyword: `:spell`, `:storm-copy`, `:activated-ability`, `:etb`, `:permanent-tapped`, `:land-entered`)
  - `:stack-item/controller` (ref to player entity)
  - `:stack-item/source` (ref to source object entity)
  - `:stack-item/effects` (EDN vector of effect maps)
  - `:stack-item/targets` (map of targeting choices)
  - `:stack-item/object-ref` (ref to game object, spells only)
  - `:stack-item/chosen-x` (integer, X value)
  - `:stack-item/description` (string for display)

#### Trigger Entities (Event-Driven Abilities)
- **Unique identifier**: None (component of source object)
- **Core fields**:
  - `:trigger/event-type` (keyword: `:permanent-tapped`, `:zone-change`, etc.)
  - `:trigger/source` (ref to source object)
  - `:trigger/controller` (ref to player)
  - `:trigger/filter` (EDN map for event matching)
  - `:trigger/effects` (EDN vector)
  - `:trigger/uses-stack?` (boolean)
  - `:object/triggers` (backref from object to triggers, cardinality many)

---

## 2. Fork/Replay System

### 2.1 History Structure

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/history/core.cljs`

History is a **tree of event snapshots**:

```clojure
{:history/main        [entry0 entry1 entry2 ...]  ; Main branch entries (priority events only)
 :history/forks       {fork-id-1 fork-data
                       fork-id-2 fork-data}        ; Map of all non-main branches
 :history/current-branch nil|fork-id               ; Which branch we're on
 :history/position    -1|N}                        ; Current index in effective-entries
```

**Fork Structure**:
```clojure
{:fork/id fork-id                    ; Random UUID
 :fork/name "Fork 1"                 ; User-friendly name
 :fork/branch-point position         ; Index in parent where fork occurred
 :fork/parent nil|parent-fork-id     ; Parent fork (nil = parent is main)
 :fork/entries [entry...]}           ; Entries accumulated on this fork
```

**Entry Structure** (`make-entry`, lines 12-20):
```clojure
{:entry/snapshot game-db             ; Immutable Datascript DB at this point
 :entry/event-type :keyword          ; Original event that led to this state (e.g., ::cast-spell)
 :entry/description "Cast Dark Ritual" ; Human-readable description
 :entry/turn N                        ; Turn number at this entry
 :entry/principal :player-1|:opponent|nil ; Who initiated the action
 }
```

### 2.2 What Creates History Entries?

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/history/interceptor.cljs:9-28`

**Priority Events** (main player actions only):
- `:fizzle.events.game/init-game`
- `:fizzle.events.game/cast-spell`
- `:fizzle.events.game/cast-and-yield`
- `:fizzle.events.game/cycle-card`
- `:fizzle.events.game/yield`
- `:fizzle.events.game/yield-all`
- `:fizzle.events.game/start-turn`
- `:fizzle.events.game/play-land`
- `:fizzle.events.abilities/activate-mana-ability`
- `:fizzle.events.abilities/activate-ability`

**Priority Selection Types** (pre-cast/ability confirmations):
- `:cast-time-targeting`
- `:x-mana-cost`
- `:exile-cards-cost`
- `:ability-targeting`

**Excluded**: Mid-resolution selections (discards, mana allocation, modal picks) do NOT create history entries. This ensures stepping back lands on a state where the player can act.

### 2.3 Fork/Replay Mechanics

**Auto-Fork** (`core.cljs:99-113`):
- When a player acts from a non-tip position, a new fork is automatically created
- Fork point is recorded; entries accumulate in fork-specific vector
- Current branch ID is updated

**Effective Entries** (`core.cljs:28-40`):
- Concatenates parent branch entries (up to branch point) + fork entries
- Tree structure supports nested forks (fork of a fork)
- Rebuild works recursively via `effective-entries-for-branch`

**Navigation**:
- `step-to(position)` — jump to any position in current branch's effective entries
- `step-back()` / `step-forward()` — move 1 position within branch
- `switch-branch(fork-id)` — change active branch, jump to its tip
- `pop-entry()` — undo at tip (removes entry and cascades delete of child forks)

---

## 3. Event History & Event Log

### 3.1 Event Capture

Each history entry records:
1. **Snapshot**: Full immutable Datascript DB at that point
2. **Event Type**: The keyword that caused this state (e.g., `:cast-spell`)
3. **Description**: Human-readable text (e.g., "Cast Dark Ritual")
4. **Turn**: Turn number (for filtering/display)
5. **Principal**: Which player initiated (for filtering history)

### 3.2 How Snapshots Are Created

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/history/interceptor.cljs:104-168`

The `history-interceptor` (global re-frame interceptor, lines 104-174):

1. **Before** event execution (lines 112-127):
   - Capture `:game/db` from app-db
   - Capture selection type (if pending)
   - Capture casting spell ID (for description fallback)
   - Capture principal (which player is acting)

2. **After** event execution (lines 128-168):
   - Check if event is a priority event
   - Check if game-db changed OR selection was created (peek/target state)
   - Build description from event metadata + post-game-db queries
   - Create entry with snapshot DB
   - Auto-fork if not at tip; append otherwise

Key insight: **Only Datascript DB is snaphotted.** The snapshot contains all card instances, player state, stack, etc. at that decision point.

### 3.3 What's NOT in Event History

- UI state (which menu is open, collapsed panels)
- Setup screen state (deck lists, presets)
- Current selections (mid-resolution choices)
- Time data (no timestamps recorded)

---

## 4. Current Serialization Infrastructure

### 4.1 Storage Layer

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/storage.cljs` (103 lines)

Current serialization is **localStorage-based only**:

```clojure
(save-presets! presets)           ; pr-str → localStorage["fizzle-presets"]
(load-presets)                    ; read-string ← localStorage
(save-imported-decks! decks)       ; pr-str → localStorage["fizzle-imported-decks"]
(load-imported-decks)              ; read-string ← localStorage
(save-stops! stops-map)            ; pr-str → localStorage["fizzle-stops"]
(load-stops)                       ; read-string ← localStorage
```

**What's serialized**:
- Deck presets (deck lists + deck metadata)
- Imported decks (parsed deck lists)
- Phase stops (priority settings)

**What's NOT serialized**:
- Game state snapshots
- History entries
- Event log

**Format**: EDN (Extensible Data Notation) — ClojureScript's `pr-str` / `reader/read-string`

### 4.2 No URL Routing

**Finding**: No URL/routing infrastructure exists in the codebase.

- No router library (re-frame-navigation, reitit, accountant, etc.)
- No hash-based navigation
- No query string handling
- No state-from-URL restoration

The app is a single-screen SPA with navigation via `:active-screen` re-frame app-db field:
```
:setup → :opening-hand → :game
```

---

## 5. Game Setup & Initialization

### 5.1 Setup Entry Point

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs`

When game starts, two major steps:

1. **Deck Selection** (`setup.cljs:start-game-handler`, lines 255-270):
   - Validate deck is 60+ cards
   - Create `:game/db` from deck config:
     ```
     {:main-deck [{:card/id :count} ...]
      :bot-archetype :goldfish|:burn
      :bot-deck (from bot/bot-deck multimethod)
      :must-contain {card-id count}  ; Sculpted opening hand
      :sideboard [{:card/id :count} ...]}
     ```
   - Dispatch `:init-game` with config

2. **Game State Creation** (`init.cljs:init-game-state`, lines 117-169):
   - Create Datascript conn with schema
   - Load all card definitions (from `engine/cards.cljs`)
   - Create player entities for :player-1 and :opponent
   - Shuffle deck, extract sculpted cards, deal opening hand
   - Create game object entities for each card instance
   - Initialize game singleton entity
   - Create turn-based triggers (lines 157-158)
   - Create history and UI state (lines 161-169)

### 5.2 What's Needed to Reconstruct Initial State

To recreate a game from snapshot:
1. **Deck composition**: `:setup/main-deck` (deck list)
2. **Bot archetype**: `:setup/bot-archetype`
3. **Sideboard**: `:setup/sideboard` (optional)
4. **Hand sculpting**: `:setup/must-contain` (optional, pre-mulligan)
5. **Position in sequence**: Which history entry to restore

The game initializer accepts all of these as options.

---

## 6. URL Routing Status

**Finding**: **No URL routing exists.**

The app uses simple screen navigation:
```clojure
{:active-screen :setup|:opening-hand|:game}
```

Navigation is handled via re-frame events:
```clojure
::setup/init-setup          ; → :setup screen
::setup/start-game          ; → :opening-hand screen
::events/set-active-screen  ; → any screen
::setup/restore-setup       ; → :setup with stashed config
```

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/ui.cljs` (minimal, ~20 lines)

No history API (`window.history.pushState`) or hash navigation used.

---

## 7. State Size Estimation

### 7.1 Datascript DB Size

A typical game state after a few turns includes:

**Entities**:
- ~50 card definitions (all loaded upfront)
- ~20-30 game objects (hand + library + graveyard + stack)
- 2 player entities
- 1 game state entity
- 0-10 stack items
- 0-5 trigger entities

**Typical entry snapshot size**:
- Card definitions (immutable): ~50KB (one-time load, not per snapshot)
- Game objects: ~2-5KB (object properties + references)
- Player/game state: ~0.5KB
- Stack items: ~0.5KB per item
- Triggers: ~0.2KB per trigger
- **Total per snapshot**: ~10-15KB (excluding card defs)

### 7.2 History Entry Vector Size

A typical mid-game history:
- **8-12 entries** (one per priority action: cast spell, yield, play land, etc.)
- **Entry overhead**: ~1KB per entry (snapshot + metadata + description)
- **Full history in-memory**: ~100-150KB + card definitions

### 7.3 App-DB Size

Full app-db with history and UI state:
- `:game/db` (one Datascript DB): ~10-15KB
- `:history/main` (8-12 entries): ~100KB
- `:history/forks` (0-5 forks with ~3 entries each): ~50KB
- Setup/UI state: ~5KB
- **Total**: ~165-170KB typical, scales to ~500KB for complex games with many forks

---

## 8. Key Data Flow References

### 8.1 Game State Creation Path
```
Setup screen → start-game-handler (events/setup.cljs:255)
  ↓
  init-game-state (events/init.cljs:117)
  ↓
  Create Datascript conn
  ↓
  Load cards, players, game objects, triggers
  ↓
  Return {:game/db @conn :history/main [] :active-screen :opening-hand ...}
  ↓
  Opening hand screen (mulligan)
  ↓
  Game screen (active game)
```

### 8.2 Snapshot Creation Path
```
Player dispatches re-frame event
  ↓
  history-interceptor :before (capture pre-state)
  ↓
  Event handler executes (modifies app-db)
  ↓
  history-interceptor :after
  ↓
  Check if priority event (lines 132-166)
  ↓
  Create entry: {:entry/snapshot (:game/db db-after) :entry/event-type ... :entry/description ...}
  ↓
  If at tip: append-entry; if not at tip: auto-fork
```

### 8.3 History Navigation Path
```
Player clicks undo/redo
  ↓
  history/step-back or history/step-forward event
  ↓
  history/step-to (lines 77-86)
  ↓
  Clear UI state (:game/pending-selection, etc.)
  ↓
  Restore :game/db to entry snapshot
  ↓
  UI re-renders with restored state
```

---

## 9. Serialization Format Characteristics

### 9.1 What CAN be Serialized

**Datascript DBs**:
- Fully serializable to EDN (ClojureScript's native format)
- Uses `pr-str`/`read-string` in storage.cljs
- No circular references; no mutable objects
- Entities are maps; refs are entity IDs (integers)

**History entries**:
- Snapshots: EDN-serializable (Datascript DB)
- Metadata: EDN-serializable (keywords, strings, integers)
- Fork tree: Maps with IDs and vectors; fully serializable

**Setup config**:
- Deck lists: vectors of maps with keywords
- Presets: maps; fully serializable
- Already using pr-str/read-string

### 9.2 What CAN'T be Easily Serialized

**Datascript connections** (`d.create-conn`):
- Not serializable; only the DB value (`@conn`) is
- Can't round-trip a connection; must rebuild from DB

**Subscriptions**:
- re-frame subscriptions are functions; not serializable
- Must be re-registered at runtime

**Event handlers**:
- re-frame event handlers are registered at init; not serialized
- Must exist in running app to dispatch

---

## 10. Current Limitations for URL Sharing

| Aspect | Status | Details |
|--------|--------|---------|
| Game state capture | ✓ AVAILABLE | Full Datascript DB snaphotted in history entries |
| Serialization format | ✓ AVAILABLE | EDN format used in storage.cljs |
| Event log reconstruction | ✓ AVAILABLE | Fork/replay system can replay from snapshots |
| URL routing | ✗ MISSING | No router; no hash or query param handling |
| URL encoding/compression | ✗ MISSING | No compression library; EDN is verbose |
| URL generation | ✗ MISSING | No ID assignment for game states |
| URL restoration | ✗ MISSING | No URL parser or state-from-URL loader |
| Persistent storage | ✗ MISSING | Game states not saved to server; only localStorage |
| Setup persistence | ✓ AVAILABLE | Deck/preset config saved to localStorage |

---

## Summary

**Game state is fully captured in Datascript DB snapshots**, recorded in history entries with turn/principal metadata. The fork/replay system maintains a tree structure of all branches. However, **no URL infrastructure exists** — there's no router, no URL encoding, and no persistent backend. Setup/preset config is already serialized to localStorage using EDN format.

To implement snapshot-to-URL sharing:

1. **Need to add**: Router (hash-based or query param), URL encoder/decoder, server storage (or base64-encoded URL)
2. **Can reuse**: Existing EDN serialization (pr-str/read-string), Datascript DB snapshots, history entry structure
3. **Can't reuse**: URL restoration would need a new flow (not part of current event dispatch)

**Size estimates**: Single snapshot ~10-15KB (Datascript DB), full history with forks ~150-500KB, all EDN-serializable.
