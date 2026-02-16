# Fizzle Architecture Overview

This document consolidates the architecture work tracked in the `fizzle-gz0f` epic: volatility-based decomposition, ADRs, data flow analysis, completed refactors, and open work items.

## System Decomposition

Fizzle is decomposed into 6 components organized by **volatility axis** (Lowy method) — grouping code by what changes together and why, not by technical layer.

### Component Map

```
┌─────────────────────────────────────────────────────────┐
│  BOUNDARY                                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │  Presentation                                     │  │
│  │  views/*.cljs, subs/*.cljs                        │  │
│  │  Volatility: UX/UI changes (stabilizing)          │  │
│  └───────────────┬───────────────────────┬───────────┘  │
│                  │                       │              │
│  MANAGER         ▼                       │              │
│  ┌───────────────────────┐               │              │
│  │  Game Orchestration   │               │              │
│  │  events/*.cljs        │               │              │
│  │  Volatility: flow     │               │              │
│  │  coordination         │               │              │
│  │  (stabilizing)        │               │              │
│  └───────────┬───────────┘               │              │
│              │                           │              │
│  ┌───────────────────────┐               │              │
│  │  Practice Platform    │               │              │
│  │  history/, tactics/,  │               │              │
│  │  bots/, storage/      │               │              │
│  │  Volatility: practice │               │              │
│  │  features (stable)    │               │              │
│  └───────────┘           │               │              │
│                          │               │              │
│  ENGINE                  ▼               ▼              │
│  ┌───────────────────────────────────────────────────┐  │
│  │  Interpretation Core                              │  │
│  │  engine/*.cljs, events/selection/*.cljs (pure)    │  │
│  │  Volatility: new effect types (medium-rare)       │  │
│  │  *** MUST REMAIN PURE — ZERO RE-FRAME DEPS ***   │  │
│  └───────────────────────┬───────────────────────────┘  │
│                          │                              │
│  RESOURCE ACCESSOR       ▼                              │
│  ┌─────────────────┐  ┌─────────────────────────────┐  │
│  │  Card Pool      │  │  Data Foundation             │  │
│  │  cards/*.cljs   │  │  db/schema, db/queries,      │  │
│  │  Volatility:    │  │  db/init                     │  │
│  │  highest (new   │  │  Volatility: lowest          │  │
│  │  cards often)   │  │  (most stable)               │  │
│  └─────────────────┘  └─────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### Component Details

#### 1. Card Pool (Resource Accessor)
- **Modules**: `src/fizzle/cards/*.cljs` (~58 card definitions across 9 files)
- **Volatility**: Highest — new card definitions added most frequently
- **Responsibility**: Pure EDN card data. No business logic. If you're writing a `case` on a card name, you've gone wrong.
- **Interface**: Card entity maps consumed by Interpretation Core via multimethods and db queries

#### 2. Interpretation Core (Engine)
- **Modules**: `src/fizzle/engine/*.cljs` (20 files), `src/fizzle/events/selection/*.cljs` (pure logic portions)
- **Volatility**: Medium-rare — new effect handlers are just defmethods (variability), but new interaction patterns require structural changes (true volatility)
- **Responsibility**: Pure-function game rules. Interprets card EDN into state transitions. Stack resolution, mana, zones, targeting, combat, effects, selection mechanism.
- **Key invariant**: Zero re-frame dependencies (ADR-001). Purity enables testability, reuse by bots/tactics, and reasoning about rules as pure state transformations.
- **Resolution architecture**: Single `resolve-stack-item` multimethod dispatching on `:stack-item/type` (`:spell`, `:storm-copy`, `:activated-ability`, `:storm`, `:default`)
- **Tagged return values**: Interactive effects return `{:db db :needs-selection effect}` instead of bare `db`. `reduce-effects` executes sequentially, pausing at interactive effects.
- **Depends on**: Card Pool, Data Foundation

#### 3. Game Orchestration (Manager)
- **Modules**: `events/game.cljs` (~670 lines), `events/selection.cljs` (~1437 lines), `events/abilities.cljs` (~273 lines), `core.cljs`
- **Volatility**: Medium, stabilizing during active development
- **Responsibility**: Re-frame event coordination. Delegates all game rule computation to Interpretation Core. Manages event dispatch, effect handling, game state lifecycle.
- **Pattern**: Selection system uses builder -> `::toggle-selection` -> `::confirm-selection` -> `execute-confirmed-selection` multimethod
- **Depends on**: Interpretation Core

#### 4. Practice Platform (Manager)
- **Modules**: `history/*.cljs` (4 files), `tactics/*.cljs`, `bots/*.cljs`, `storage/*.cljs`
- **Volatility**: Stabilizing after initial build
- **Responsibility**: Fork/replay, history tracking, tactic/puzzle save/load, bot AI. Each subsystem owns its data AND interpretation logic (domain-split, ADR-002).
- **History architecture**: Pure value replacement — never calls engine. Enables free fork/replay via ClojureScript structural sharing.
- **Depends on**: Game Orchestration

#### 5. Presentation (Boundary)
- **Modules**: `views/*.cljs` (17 files), `subs/*.cljs` (4 files)
- **Volatility**: Stabilizing after core UX established
- **Responsibility**: Renders game UI, collects player input, dispatches events. No game logic.
- **Subscription pattern**: Subs import directly from Interpretation Core for derived state (ADR-003) — `can-cast?`, `sort-cards`, etc.
- **Depends on**: Interpretation Core (derived state), Data Foundation (raw state)

#### 6. Data Foundation (Resource Accessor)
- **Modules**: `db/schema.cljs`, `db/queries.cljs`, `db/init.cljs`
- **Volatility**: Lowest — changes here ripple everywhere
- **Responsibility**: Datascript schema (13+ entity types), query functions. No business logic — pure data definition and access.
- **Interface**: Schema consumed by all components; query functions called by Interpretation Core, Game Orchestration, Presentation

### Dependency Rules

- **No upward dependencies**: Engine cannot call Manager, Resource Accessor cannot contain business logic
- **Data-driven extension**: New cards/bots/tactics are EDN data interpreted by their respective engines
- **Domain-split ownership**: Bot and tactic data live with their interpreters (Practice Platform), not with Card Pool
- **Subscriptions at the join point**: Subs can import from both Data Foundation and Interpretation Core without creating cycles (ADR-003)

## Architecture Decision Records

Five ADRs were created to capture the rationale behind architecturally significant decisions. All are accepted.

### ADR-001: Pure engine boundary as architectural invariant
The boundary between Interpretation Core (`engine/`) and Game Orchestration (`events/`) is an explicit architectural invariant. Engine must remain pure functions with zero re-frame dependencies. This preserves testability, reuse, and reasoning about game rules as pure state transformations.

### ADR-002: Domain-split data ownership
Data definitions are co-located with their interpretation engines. Card definitions live in Card Pool; bot and tactic definitions live in Practice Platform alongside their interpretation logic. Each domain owns its data and interpreter as a single volatility axis. If data loading moves external, a Data Gateway is deferred until the force materializes.

### ADR-003: Subscriptions call Interpretation Core directly
Subscriptions import directly from Interpretation Core for derived state (`can-cast?`, `sort-cards`, `land-card?`). This avoids a circular dependency that would arise from routing through Data Foundation. Pure Datascript queries that lived in engine modules (`get-all-stack-items`, `get-grants`) were relocated to `db/queries` so Data Foundation remains the single interface for raw data access.

### ADR-004: Decouple Game Orchestration from history management
Game Orchestration had two sources of direct history coupling: (1) manual history entries in `start-turn` for opponent draw, and (2) batch history entries in `resolve-all-handler`. Both were fixed by addressing root causes: resolve-all was converted to recursive dispatch (completed), and opponent turns should be modeled as real turns (open work). After both changes, `events/game.cljs` will have zero imports from `history/`.

### ADR-005: All application state lives in re-frame app-db
No mutable state (atoms, vars) should exist outside re-frame app-db for shared application concerns. Three Reagent atoms for UI panel collapse state were moved into app-db under `:ui/` keys. Component-local atoms for truly local ephemeral state remain acceptable.

## Data Flow Analysis

A comprehensive data flow trace was performed covering 7 primary action paths and identifying implicit ordering constraints. Full trace preserved in `DATA_FLOW_TRACE.md`.

### Primary Action Paths

1. **Casting a Spell**: Presentation -> Game Orchestration -> Interpretation Core (5+ rules checks) -> selection chain -> history capture
2. **Resolving Stack (Single Item)**: Yield button -> `resolve-one-item` -> multimethod dispatch -> optional selection chain -> cleanup
3. **Resolve All (Recursive Dispatch)**: Recursive `reg-event-fx` resolving one item per dispatch, tracking `initial-ids` to detect storm-created items
4. **Player Selection (e.g., Tutoring)**: Engine returns `{:needs-selection effect}` -> selection builder multimethod -> UI modal -> confirm -> execute
5. **Starting a Game / Setup**: Deck config -> Datascript init -> opening hand -> mulligan/sculpt -> first turn
6. **Fork/Replay**: Rewind to entry -> restore snapshot -> take different action -> auto-fork detection
7. **Opening Hand / Mulligan**: Return hand -> shuffle -> extract sculpted -> draw remaining

### Undeclared Flows Found

Four data flows existed in code but were not in the original architecture graph:

| Flow | Status | Assessment |
|------|--------|------------|
| Subs -> Interpretation Core | Declared edge exists, but more pervasive than described | Accepted per ADR-003 |
| Views -> Interpretation Core | Declared edge exists, but direct imports (not via subs) | Pragmatically acceptable; read-only queries |
| History descriptions -> engine/stack | Undeclared Practice Platform -> Interpretation Core | Should be declared; read-only |
| Selection submodules -> engine/* | Declared; expected and extensive | Per ADR-001 |

**Key finding**: All actual data flows follow declared graph edges or are minimal read-only dependencies. No circular dependencies. No undeclared backward edges.

### Implicit Ordering Constraints

Six ordering constraints are architecturally significant but implicit in the code:

1. **History snapshot atomicity**: Interceptor must fire after event handler completes (guaranteed by re-frame)
2. **Resolve-all stack tracking**: Must track `initial-ids` to prevent infinite loops during storm
3. **Selection remaining-effects**: Must execute remaining effects before cleanup
4. **Setup phase order**: Deck config -> init -> opening hand -> game start
5. **Mulligan shuffle order**: Return to library -> shuffle -> extract sculpted -> draw random
6. **Fork creation idempotency**: Multiple actions from same rewound position append to same fork

## Completed Work

Three refactoring tasks were completed as part of this epic:

### Move pure query functions from engine/ to db/queries (fizzle-gz0f.7)
Relocated `stack/get-all-stack-items` and `grants/get-grants` from engine modules to `db/queries`. Updated all callers (~24 test files). Data Foundation is now the single interface for raw data access per ADR-003.

### Refactor resolve-all as recursive dispatch (fizzle-gz0f.8)
Replaced the internal loop in `resolve-all-handler` with a recursive `reg-event-fx` that resolves one stack item per dispatch, then re-dispatches itself. Eliminated `make-resolve-entry`, `apply-history-entries`, and `resolve-all-handler`. Each resolution now goes through the history interceptor automatically, removing duplicated fork/append logic. This was one of two fixes needed for ADR-004.

### Move UI collapse atoms into re-frame app-db (fizzle-gz0f.9)
Removed three `defonce` Reagent atoms from `core.cljs` (`stack-collapsed?`, `gy-collapsed?`, `history-collapsed?`). Added `:ui/` keys to app-db, registered toggle events and subscriptions. Implements ADR-005.

## Open Work

### Refactor opponent turn (fizzle-gvs7) — P3
Model opponent turns as real turns with active-player switching. Currently the opponent's turn is a hack: a bare `opponent-draw` call in the player's `::start-turn` handler. This is the second fix needed for ADR-004 (decouple Game Orchestration from history). It's also a prerequisite for the bot system (Phase 5 in roadmap).

Key decisions needed:
- Turn numbering: increment for both players (MTG-correct) or per-player?
- Goldfish turn phases: draw-only or untap+draw minimum?
- UI signaling for turn transitions
- Bot integration point: called during phases or subscribes to events?

### Component stability assessments
All 6 components were tagged `stability:exploring` during initial decomposition. Stability assessments remain open — the components need further audit to determine whether they have converged or still have structural volatility.

## Global Constraints

These constraints apply across all components:

1. Engine layer (Interpretation Core) must remain pure functions with zero re-frame dependencies
2. No upward dependencies: Engine cannot call Manager, Resource Accessor cannot contain business logic
3. Data-driven extension model: new cards/bots/tactics are EDN data interpreted by their respective engines
4. Bot and tactic data live with their engines (Practice Platform), not with Card Pool
5. Future axis noted: if data loading moves external, a Data Gateway component at Resource Accessor layer would sit beneath Card Pool and Practice Platform
