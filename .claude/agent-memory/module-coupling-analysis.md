# Module-Level Data Flow and Coupling Analysis

## Overview
This document maps data shapes that cross module boundaries in Fizzle, organized by major coupling points. Each entry includes: source module, destination module, data shape crossing the boundary, entry points, and dependency character (import-time compile-time vs runtime dispatch).

---

## 1. DB/SCHEMA ← EVERYTHING (Anchor Point)

**Character:** Compile-time reference + runtime Datascript protocol

**Schema defines all entity types used across the codebase. All modules read/write to a shared Datascript db.**

### Data Shapes Flowing Through Schema

| Entity Type | Schema Keys | Source Module | Destination | Entry Point |
|---|---|---|---|---|
| **Card** | `:card/id` (unique), `:card/name`, `:card/cmc`, `:card/mana-cost`, `:card/colors`, `:card/types`, `:card/text`, `:card/effects`, `:card/abilities`, `:card/etb-effects`, `:card/triggers`, `:card/keywords` | cards/* | db.init, engine.rules, engine.effects, subs.game | Card definition transacted in init-game-state |
| **Object** | `:object/id` (unique), `:object/card` (ref to card), `:object/zone`, `:object/owner`, `:object/controller`, `:object/tapped`, `:object/counters`, `:object/position`, `:object/is-copy`, `:object/grants`, `:object/x-value`, `:object/cast-mode` | events.game | queries, subs, engine.* | Created in init-game-state, created/modified by cast-spell, play-land, etc. |
| **Player** | `:player/id` (unique), `:player/name`, `:player/life`, `:player/mana-pool` map, `:player/storm-count`, `:player/land-plays-left`, `:player/max-hand-size`, `:player/grants` | events.game | subs.game, engine.mana, engine.rules | Created in init-game-state, modified by cast-spell (mana), play-land, etc. |
| **Game** | `:game/id` (unique), `:game/turn`, `:game/phase`, `:game/step`, `:game/active-player` (ref), `:game/priority` (ref), `:game/winner`, `:game/loss-condition` | events.game | subs.game, engine.rules, engine.turn-based | Created in init-game-state, modified by advance-phase, start-turn |
| **Stack-Item** | `:stack-item/position`, `:stack-item/type` (enum: spell, storm-copy, activated-ability, etb, etc.), `:stack-item/controller` (ref), `:stack-item/source` (ref), `:stack-item/effects` (EDN vector), `:stack-item/targets` (map), `:stack-item/description`, `:stack-item/is-copy`, `:stack-item/cast-mode`, `:stack-item/object-ref` (ref) | engine.stack, engine.rules | engine.resolution, subs.game, events.game | Created by rules.cast-spell, engine.rules.create-mana-ability-stack-item |
| **Trigger** | `:trigger/event-type`, `:trigger/source` (ref), `:trigger/controller` (ref), `:trigger/filter` (EDN), `:trigger/effects` (EDN vector), `:trigger/uses-stack?`, `:trigger/always-active?` | engine.trigger-db | engine.trigger-dispatch, engine.turn-based, events.game | Created in trigger-db.register-trigger |

### Schema as Implicit Contract

The schema.cljs defines:
- **Entity types:** Card, Object, Player, Game, Stack-Item, Trigger
- **Relationship types:** `:object/card` (game object → card definition), `:object/owner`/`:object/controller` (→ player)
- **Cardinalities:** `:card/effects` is stored as EDN vector (not decomposed as refs), `:object/grants` is EDN vector
- **Query patterns:** All modules use `(d/q '[:find ... :where ...] db)` to read state

**Key insight:** Schema is the contract that prevents circular dependencies. Modules don't import each other's data shapes; they all speak Datascript.

---

## 2. CARDS → ENGINE/RULES (Card Definition Flow)

**Character:** Compile-time require + pure interpretation

**Cards define spells as EDN data; engine.rules interprets them.**

### Data Shape

Card definitions (map with keys `:card/id`, `:card/name`, `:card/effects`, `:card/targeting`, `:card/keywords`, etc.):

```clojure
{:card/id :dark-ritual
 :card/name "Dark Ritual"
 :card/mana-cost {:black 1}
 :card/types #{:instant}
 :card/effects [{:effect/type :add-mana :effect/mana {:black 3}}]
 :card/keywords #{:storm}}
```

### Coupling Points

| Source | Destination | Entry Point | Data Shape | Dependency |
|---|---|---|---|---|
| cards.iggy-pop | db.init | init-game-state (line 39) | Bare card maps (e.g., `dark-ritual`, `brain-freeze`) | Compile-time require |
| cards.* (any card def) | engine.rules | get-casting-modes (line 30-56) | Card map with `:card/mana-cost`, `:card/kicker`, `:card/alternate-costs`, `:card/types` | Runtime via db.queries.get-card |
| cards.* (any card def) | engine.effects | execute-effect-impl (multimethods for each `:effect/type`) | Card map's `:card/effects` (via object/effects extraction) | Runtime via object passed to execution |
| cards.* (any card def) | engine.targeting | get-targeting-requirements (line 14-22) | Card map with `:card/targeting` or ability with `:ability/targeting` | Runtime via db.queries.get-card |

### Data Flow Path

```
cards/iggy-pop.cljs (bare data)
  ↓ [transacted in init-game-state]
Datascript db [:card/id :dark-ritual ...]
  ↓ [read by queries/get-card]
engine/rules.cljs (interprets :card/effects, :card/targeting)
  ↓ [dispatches to effects/execute-effect-impl on :effect/type]
engine/effects.cljs (executes effect map)
```

**Key insight:** Cards are pure data in EDN. Engine modules import cards only through db.init setup; afterward, they read via Datascript queries. No circular coupling because cards don't import engine.

---

## 3. ENGINE/RULES → EVENTS/GAME (Rule Orchestration)

**Character:** Compile-time import, pure function calls

**engine.rules encapsulates all casting logic; events.game invokes it and handles re-frame side effects.**

### Data Shapes

**Input (from events.game to engine.rules):**
- `db` - Datascript database value
- `player-id` - Keyword (e.g., `:player-1`)
- `object-id` or `card-id` - UUID or keyword

**Output (from engine.rules back to events.game):**
- Modified `db` with new objects/stack-items/state

### Coupling Points

| events.game Function | Calls engine.rules Function | Input Shape | Output Shape |
|---|---|---|---|
| init-game-event (line 149) | none directly | — | `{:db db}` |
| cast-spell-event (line 200) | rules/cast-spell (line 166-296) | `db`, `:player-1`, `object-id`, `selected-mode`, `:game/pending-selection` | `{:db db}` or `{:db db :pending-selection {...}}` |
| play-land-event (line 378) | rules/play-land (line 343-360) | `db`, `:player-1`, `object-id` | `{:db db}` with new object in :battlefield zone |
| activate-ability-event (abilities.cljs) | rules/create-mana-ability-stack-item | `db`, `:player-1`, `object-id` | Stack-item entity map |

### Data Shape Propagation

```
events/game.cljs [cast-spell-event]
  ↓ input: db, player-id, object-id, mode map
engine/rules.cljs [cast-spell]
  ├─ calls costs/pay-mana(db, player-id, cost)
  ├─ calls zones/move-object(db, object-id, :stack)
  ├─ calls stack/create-stack-item(db, object-id)
  └─ returns db'
  ↓ output: db'
events/game.cljs [cast-spell-event]
  ├─ stores db' in app state as :game/db
  └─ may dispatch resolve-top or set pending-selection
```

**Key insight:** engine.rules is pure; all side effects (subscription updates, dispatch) happen in events.game.

---

## 4. ENGINE/EFFECTS ← ENGINE/RESOLUTION (Effect Execution)

**Character:** Compile-time import, multimethod dispatch

**engine.resolution applies stack-item effects via engine.effects dispatch.**

### Data Shapes

**Input to effects/execute-effect:**
```clojure
{:effect/type :draw
 :effect/amount 1
 :effect/target :any-player}  ; or nil if no targeting
```

**Output from effects/execute-effect:**
- Plain `db` (non-interactive effect)
- `{:db db :needs-selection effect}` (interactive, requires player choice)

### Coupling Points

| engine.resolution Code | Calls effects Function | Input | Output |
|---|---|---|---|
| resolve-stack-item :spell (line 89-157) | effects/reduce-effects | `db`, `player-id`, effects vector | `{:db db'}` or `{:db db' :needs-selection effect}` |
| resolve-stack-item :storm-copy (line 159-180) | effects/reduce-effects | `db`, `player-id`, effects vector | Same |
| resolve-stack-item :default (line 39-62) | effects/execute-effect | `db`, `player-id`, effect map | Plain `db'` |
| resolve-stack-item :activated-ability (line 182-206) | effects/execute-effect | `db`, `player-id`, effect map | Plain `db'` |

### Effect Types That Cross Boundary

Each `:effect/type` is a defmethod in engine.effects:
- `:add-mana` → adds to `:player/mana-pool`
- `:draw` → moves cards from :library to :hand
- `:mill` → moves cards from :library to :graveyard
- `:discard` → interactive (returns `{:db db :needs-selection effect}`)
- `:tutor` → interactive
- `:damage` / `:drain` → modifies `:player/life`
- `:return-from-graveyard` → moves from :graveyard to hand/battlefield
- etc.

**Key insight:** Effects are EDN data with `:effect/type` dispatch key. Interactive effects signal via `{:db :needs-selection}` to pause resolution.

---

## 5. EVENTS/SELECTION ← ENGINE/EFFECTS (Selection Pause Signal)

**Character:** Runtime dispatch from effect execution

**When an effect needs player input, engine.effects returns tagged `{:db :needs-selection effect}`. events.selection handles the choice flow.**

### Data Shape

```clojure
{:db db'
 :needs-selection {:effect/type :discard
                   :effect/amount 2}
 :remaining-effects [...]}
```

### Coupling Points

| Module | Function | Consumes | Produces |
|---|---|---|---|
| engine.effects | reduce-effects (line 108-145) | Effects vector | `{:needs-selection effect}` tag |
| engine.resolution | resolve-stack-item :spell | `{:needs-selection}` result | Passes through to event handler |
| events.game | resolve-top (line 399-439) | Result from engine.resolution | Dispatches `::selection/set-pending-selection` |
| events.selection.core | pending-selection handler | `{:selection/type :discard :selection/amount 2}` | Stores in `:game/pending-selection` |

### Flow

```
effects/reduce-effects detects :discard effect
  ↓
returns {:db db' :needs-selection effect}
  ↓
engine/resolution :spell method receives this
  ↓
events/game resolve-top receives it
  ↓
dispatches [::events/selection/set-pending-selection selection-state]
  ↓
events/selection.core stores in :game/pending-selection
  ↓
views/modals reads :game/pending-selection subscription
  ↓
user makes choice, dispatches ::selection/confirm-selection
```

**Key insight:** Interactive effects are discovered at runtime, not pre-scanned. Selection state flows from engine → events → views → user → events.selection.core → `:game/pending-selection`.

---

## 6. SUBS/GAME ← :GAME/DB (Subscription Bridge)

**Character:** Runtime subscription, pure query

**All subscriptions extract data from `:game/db` (the Datascript db value stored in re-frame app state).**

### Data Shapes

| Subscription | Input | Output |
|---|---|---|
| `::subs/game-db` | `(:game/db app-db)` | Datascript db value |
| `::subs/hand` | `::game-db` | Vector of objects with `:object/card` pulled |
| `::subs/stack` | `::game-db` | Vector of stack-items + spell objects, sorted LIFO |
| `::subs/mana-pool` | `::game-db` | `{:white 0 :blue 1 ...}` |
| `::subs/storm-count` | `::game-db` | Integer |
| `::subs/can-cast?` | `::game-db`, `::selected-card` | Boolean |
| `::subs/can-play-land?` | `::game-db`, `::selected-card` | Boolean |

### Entry Points

All subscriptions in subs/game.cljs read from `:game/db`:
- Line 13: `(fn [db _] (:game/db db))`
- Lines 20-24: `::hand` reads `(queries/get-hand game-db :player-1)`
- Lines 41-45: `::can-cast?` reads `(rules/can-cast? game-db :player-1 selected)`

### Data Flow Path

```
events/game [cast-spell] → (assoc db :game/db db')
  ↓
:game/db stored in re-frame app state
  ↓
subs/game [::game-db] → reads :game/db
  ↓
subs/game [::hand] → queries/get-hand(::game-db)
  ↓
views/hand [hand-view] → subscribes to [::subs/hand]
```

**Key insight:** The re-frame db key `:game/db` is the bridge. All game logic runs on pure Datascript `db`, then stored for views to query.

---

## 7. HISTORY/INTERCEPTOR ← EVENTS/* (State Snapshot Capture)

**Character:** Compile-time interceptor registration + runtime `:before/:after` hooks

**Global re-frame interceptor captures `:game/db` snapshots on priority events.**

### Data Shapes

**Captured in `:before` phase (line 59-70):**
```clojure
{:history/pre-game-db game-db  ; Full Datascript db value
 :history/selection-type selection-type  ; Keyword from pending-selection
 :history/had-pending? had-pending?
 :history/casting-spell-id casting-spell-id}
```

**Appended to history in `:after` phase (line 71-90):**
```clojure
{:entry/snapshot db'  ; Full Datascript snapshot (immutable copy)
 :entry/event-type event-id  ; Keyword like :fizzle.events.game/cast-spell
 :entry/description description-string
 :entry/turn turn-number}
```

### Coupling Points

| Module | Location | Event | Data |
|---|---|---|---|
| history.interceptor | line 9-20 | Priority events list | Set of event keywords: `:fizzle.events.game/init-game`, `:fizzle.events.game/cast-spell`, etc. |
| history.interceptor | line 23-26 | Priority selection types | Set of selection types: `:cast-time-targeting`, `:x-mana-cost`, `:exile-cards-cost`, `:ability-targeting` |
| history.core | (in history) | History structure | Snapshot stored as Datascript db value |
| history.descriptions | (descriptions) | Hardcoded mapping | Event keyword → string description |

### Flow

```
[::cast-spell ...] event dispatched
  ↓
history-interceptor :before
  └─ reads :game/db from app state
  └─ stores in coeffects
  ↓
[handler executes and returns new db]
  ↓
history-interceptor :after
  └─ compares pre/post :game/db
  └─ if changed and priority-event?, calls history/append-entry
  └─ history/append-entry calls descriptions/get-description for human-readable text
```

**Key insight:** Only priority events (cast, resolve, advance-phase, etc.) create entries. Selection/targeting choices don't. This prevents "stepping back" from landing in the middle of spell resolution.

---

## 8. VIEWS ← SUBS/* (Subscription Consumption)

**Character:** Runtime subscription, pure Reagent components

**Views subscribe to derived state and render UI.**

### Data Shapes

| View | Subscription | Shape |
|---|---|---|
| hand.cljs | `[::subs/hand]` | `[{:object/id uuid :object/card {:card/name "..." :card/types #{} :card/colors #{}}} ...]` |
| stack.cljs | `[::subs/stack]` | Same |
| mana-pool.cljs | `[::subs/mana-pool]` | `{:white 0 :blue 1 :black 0 ...}` |
| controls.cljs | `[::subs/can-cast?]` | Boolean |
| controls.cljs | `[::subs/can-play-land?]` | Boolean |
| modals.cljs | `[::subs/pending-selection]` | `{:selection/type :discard :selection/amount 2 :selection/selected #{id1 id2}}` |
| history.cljs | `[::subs/entries]`, `[::subs/position]` | Vectors of history entries |

### Entry Points

Each view component calls `@(rf/subscribe [...])` to get data:
- hand.cljs line 28: `@(rf/subscribe [::subs/hand])`
- controls.cljs line 40: `@(rf/subscribe [::subs/can-cast?])`
- modals.cljs: `@(rf/subscribe [::subs/pending-selection])`

### Dispatch Points

Views dispatch events when user interacts:
- hand.cljs line 22: `rf/dispatch [::events/select-card object-id]`
- controls.cljs: `rf/dispatch [::events/cast-spell]`
- modals.cljs: `rf/dispatch [::selection/confirm-selection]`

**Key insight:** Views are pure (no state) and unidirectional (subscribe → render → dispatch). They never import events directly; they use `rf/dispatch`.

---

## 9. CARDS → SUBS/SETUP (Deck List Flow)

**Character:** Compile-time require + runtime query

**Available decks are fetched from card definitions (currently hardcoded, card import coming later).**

### Data Shapes

**Card deck structure (from cards/iggy-pop.cljs):**
```clojure
{:deck/id :iggy-pop
 :deck/name "Iggy Pop"
 :deck/main [{:card/id :dark-ritual :count 4}
             {:card/id :brain-freeze :count 1}]
 :deck/side []}
```

**Decks subscription (subs/setup.cljs line 7):**
```clojure
[{:deck/id :iggy-pop :deck/name "Iggy Pop"}]
```

### Coupling Points

| Module | Location | Data | Dependency |
|---|---|---|---|
| cards.iggy-pop | exported `all-cards`, `iggy-pop-decklist` | Vector of card defs + deck structure | Bare exports |
| subs.setup | line 7-8 (hardcoded) | Hardcoded deck list | Compile-time; will be dynamic |
| events.setup | setup handlers | Deck selection stored in `:setup/selected-deck` | Runtime via sub query |

### Flow

```
subs/setup [::available-decks]
  └─ returns hardcoded [iggy-pop deck]
     (future: will import from cards/*)

events/setup [::select-deck deck-id]
  ├─ reads cards/iggy-pop.iggy-pop-decklist
  └─ stores in :setup/main-deck, :setup/sideboard

views/setup [setup-screen]
  └─ subscribes to [::subs/available-decks]
```

**Key insight:** Card definitions flow into setup as configuration data, not game entities. Once game starts, only card refs are in db.

---

## 10. ENGINE/TRIGGER-DB ← ENGINE/TRIGGERS (Trigger Registration)

**Character:** Compile-time import, function calls

**Triggers are registered from cards; trigger-db stores them for dispatch.**

### Data Shapes

**Trigger definition (from card `:card/triggers`):**
```clojure
{:trigger/type :becomes-tapped
 :trigger/effects [{:effect/type :draw :effect/amount 1}]}
```

**Registered trigger (in trigger-db):**
```clojure
{:trigger/event-type :becomes-tapped
 :trigger/source source-eid
 :trigger/controller controller-eid
 :trigger/filter {}
 :trigger/effects effects-vector
 :trigger/uses-stack? true}
```

### Coupling Points

| Module | Function | Data Shape | Entry Point |
|---|---|---|---|
| engine.rules | maybe-register-triggers (line 239-255) | Card's `:card/triggers` vector | Called after spell enters stack |
| engine.trigger-db | register-trigger | Trigger map | Called by rules.maybe-register-triggers |
| engine.trigger-dispatch | dispatch-trigger-event | Stored trigger entity | Called when event fires (e.g., object tapped) |

**Key insight:** Triggers are stored in Datascript like any entity, keyed by `:trigger/event-type`. This allows dynamic query: "what triggers fire on tapped?"

---

## Summary: Module Dependency Character

| Dependency Type | Example | Direction | Coupling |
|---|---|---|---|
| **Compile-time require** | cards → db.init, engine.rules → events.game | Unidirectional | High (module must exist at build time) |
| **Datascript schema contract** | All modules ← db.schema | Radial (all point to center) | Low (implicit via schema, no direct imports) |
| **Runtime dispatch** | events.game → engine.rules → effects | Unidirectional | Low (engine is pure, can be tested independently) |
| **Re-frame subscription** | views ← subs.game ← :game/db | Unidirectional | Low (subs are pure queries) |
| **Re-frame event dispatch** | views → events.game | Unidirectional | Low (decoupled via event bus) |
| **Tagged return values** | effects → events.selection | Unidirectional | Medium (requires agreement on `{:db :needs-selection}` shape) |
| **Interceptor hooks** | history.interceptor ← all events | Global, selective | Medium (whitelist of priority events) |

---

## Key Insights

1. **Datascript db is the central hub.** All modules read/write through a shared schema, preventing circular imports.

2. **Data flows unidirectionally from cards → engine → events → views → user → events → db.** Reverse flows use `rf/dispatch` (runtime), not requires (compile-time).

3. **Interactive effects are discovered at runtime, not pre-scanned.** When an effect needs player input, it returns `{:db :needs-selection}` to pause resolution.

4. **Subscriptions are the view contract.** Views never read `:game/db` directly; they go through subs/, which provides a stable API.

5. **History is opt-in per event.** Only priority events (cast, resolve) create entries. This prevents forking from landing mid-resolution.

6. **Triggers are stored data, not code.** Trigger registration (from `:card/triggers`) creates DB entities that are queried at dispatch time.

7. **No circular coupling exists.** The module DAG is acyclic:
   - cards (0 deps) → db.init → (all modules)
   - engine (db deps only) → events, subs
   - events ← views, history
   - views ← subs ← engine, db

---

## Diagram: Data Flow at Event Dispatch

```
User clicks "Cast" button
  ↓
views/hand [card-view :on-click]
  └─ rf/dispatch [::events/cast-spell object-id]
  ↓
events/game [::cast-spell-event handler]
  ├─ validates with engine/rules.can-cast?
  ├─ gets card via queries/get-card(db, object-id)
  ├─ calls engine/rules.cast-spell(db, player-id, object-id, mode)
  │  ├─ checks cost via engine/costs.get-total-mana-cost
  │  ├─ pays mana via engine/mana.pay-mana
  │  ├─ moves object via engine/zones.move-object
  │  ├─ creates stack-item via engine/stack.create-stack-item
  │  └─ returns db'
  ├─ stores db' in app state: (assoc db :game/db db')
  ├─ calls engine/effects.reduce-effects on card.effects
  │  ├─ executes each effect
  │  └─ if interactive, returns {:db db :needs-selection effect}
  ├─ if pending-selection, dispatches [::selection/set-pending-selection state]
  └─ returns {:db db'}
  ↓
history-interceptor :after
  ├─ detects priority event
  ├─ calls history/append-entry with snapshot
  └─ updates :history/entries
  ↓
Subscriptions update
  ├─ [::subs/game-db] → reads :game/db
  ├─ [::subs/hand] → queries/get-hand(game-db)
  ├─ [::subs/stack] → queries/get-objects-in-zone(game-db, :stack)
  └─ [::subs/can-cast?] → rules/can-cast?(game-db)
  ↓
Views re-render
  ├─ hand-view removes card from hand
  ├─ stack-view adds card to stack
  ├─ mana-pool-view updates mana
  └─ if pending-selection, modals-view shows selector
```

---

## Diagram: Module Dependency Graph

```
                        cards/*
                           ↓
                      db/schema ← [ALL MODULES READ/WRITE]
                           ↑
      ┌─────────────────────┼─────────────────────┐
      ↓                     ↓                     ↓
   db/init             db/queries            db/schema
      ↓                     ↑                     ↑
   events/game          engine/*    ←────→    subs/*
      ↑                     ↓                     ↓
      ├─────────────────────┤                 views/*
      ↓                     ↓
history/interceptor   events/selection
      ↓
history/core, descriptions
```

**DAG structure:**
- cards (0 deps)
- db (1 dep: cards)
- engine (1 dep: db)
- events (2 deps: engine, db)
- subs (2 deps: engine, db)
- views (2 deps: subs, events)
- history (2 deps: db, events)
- storage (0 deps)

**No cycles.** Every import path goes toward the center (db/schema).
