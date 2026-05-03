# Snapshot Sharing - Quick Reference

## Critical File Paths

### Core Game State
- Schema: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs`
- Init: `/Users/abugosh/g/fizzle/src/main/fizzle/db/init.cljs`
- Queries: `/Users/abugosh/g/fizzle/src/main/fizzle/db/queries.cljs`

### History/Fork System
- Core: `/Users/abugosh/g/fizzle/src/main/fizzle/history/core.cljs`
- Events: `/Users/abugosh/g/fizzle/src/main/fizzle/history/events.cljs`
- Views: `/Users/abugosh/g/fizzle/src/main/fizzle/views/history.cljs`

### Card Registry
- Registry: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs`
- Example cards: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/black/dark_ritual.cljs`

### Entry Points
- Core: `/Users/abugosh/g/fizzle/src/main/fizzle/core.cljs`
- HTML: `/Users/abugosh/g/fizzle/resources/public/index.html`

### UI Views
- Controls: `/Users/abugosh/g/fizzle/src/main/fizzle/views/controls.cljs`
- History: `/Users/abugosh/g/fizzle/src/main/fizzle/views/history.cljs`

## Data Serialization Points

### What to Serialize

```clojure
;; Game state snapshot
{:game/db <datascript-db>           ; Immutable DB value
 :history/main [...]                ; Entry vectors
 :history/forks {...}               ; Fork map
 :history/current-branch nil         ; Current branch ID
 :history/position -1                ; Current position
 :setup/stashed-config {...}}        ; Setup config for restore
```

### Core Entities to Include

```
Players:        :player/id :player/life :player/mana-pool :player/storm-count :player/grants
Objects:        :object/id :object/zone :object/tapped :object/counters :object/grants
Game:           :game/id :game/turn :game/phase :game/active-player :game/priority
Stack Items:    :stack-item/position :stack-item/effects :stack-item/targets :stack-item/type
```

## API Entry Points

### History Operations
- `init-history()` → Initialize empty history (core.cljs line 4)
- `append-entry(db, entry)` → Add action (core.cljs line 56)
- `step-to(db, position)` → Jump to position (core.cljs line 77)
- `auto-fork(db, entry)` → Create fork (core.cljs line 99)
- `switch-branch(db, fork-id)` → Change branch (core.cljs line 129)
- `effective-entries(db)` → Get current branch entries (core.cljs line 38)

### Game Init
- `init-game-state()` → Create fresh game (db/init.cljs line 25)
- Creates player, card defs, objects, game state
- Returns immutable Datascript db value

### Queries
- `get-game-state(db)` → Game entity
- `get-hand(db, player-id)` → Hand objects
- `get-all-stack-items(db)` → Stack items sorted LIFO
- `get-mana-pool(db, player-id)` → Mana pool map

## Hash Fragment Implementation

**Where**: `core.cljs:init` function (line 125)

```clojure
(defn init []
  ;; ADD HERE: Parse hash fragment
  (let [snapshot-data (parse-hash)]
    (when snapshot-data
      (rf/dispatch [::restore-snapshot snapshot-data])))

  ;; EXISTING:
  (history-interceptor/register!)
  (bot-interceptor/register!)
  (sba-interceptor/register!)
  (rf/dispatch-sync [::setup/init-setup])
  (mount-root))
```

## Button Placement Options

1. **Header** (core.cljs line 100-111):
   - Alongside Setup/Game/New Game buttons
   - Visible in both setup and game screens

2. **Controls** (views/controls.cljs line 54-82):
   - Alongside Play/Cast/Yield buttons
   - Game screen only

3. **History Sidebar** (views/history.cljs line 100-104):
   - Alongside fork controls
   - Game screen only

## Key Datascript API

```clojure
(d/create-conn schema)              ; Create new connection
(d/transact! conn [entity])         ; Add entity
(d/q '[:find ...] db)               ; Query
(d/db-with db [[:db/add eid ...]])  ; Transact on db value
@conn                               ; Get immutable db from connection
(d/db->datoms db)                   ; Serialize to datoms
```

## Setup Config Structure

**Stashed in game-db** (events/setup.cljs line 244-252):
```clojure
:setup/selected-deck           ; Deck ID keyword
:setup/main-deck              ; Vector of {:card/id ... :count ...}
:setup/sideboard              ; Vector of card entries
:setup/bot-archetype          ; :goldfish | :burn | nil
:setup/must-contain           ; Map {:card/id count}
:setup/presets                ; Map of presets
:setup/last-preset            ; MRU preset name
:setup/imported-decks         ; Map of imported decks
```

## Mana Pool Format

```clojure
{:white 0
 :blue 0
 :black 0
 :red 0
 :green 0
 :colorless 0
 :any 0}  ; For any-color mana
```

## Phase Order

```clojure
[:untap :upkeep :draw :main1 :combat :main2 :end :cleanup]
```

## Grant Expiration

```clojure
{:grant/expires {:expires/turn 1 :expires/phase :cleanup}}  ; Expires end of turn 1
{:grant/expires {:expires/permanent true}}                  ; Never expires
```

## Object Zones

```
:hand :stack :graveyard :battlefield :library :exile :removed-from-game
```

## Card ID Keywords (Sample)

```
:dark-ritual :brain-freeze :city-of-brass :deep-analysis :chain-of-vapor
:lotus-petal :lions-eye-diamond :storm-copies (storm mechanic)
```

## No Existing URL Handling

- No `window.location.hash` parsing
- No location watcher
- No URL query parameters
- Clean slate for implementation

## Storage Location

**Imported decks**: localStorage via `db/storage.cljs`
- Could reuse for snapshot sharing (encode snapshot as URL-safe string)
- Or store snapshots on server with UUID

---

All file paths are absolute and verified. Line numbers reference the exact location of implementations.
