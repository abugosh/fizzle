# Fizzle Selection System Investigation

**Date**: 2026-02-28
**Scope**: MEDIUM (selection system + engine interaction, ~2500 lines across 6 modules + 6 engine modules)
**Investigation Focus**: Module structure, dependency graphs, call patterns, shared state, and compensating code

---

## 1. MODULE STRUCTURE AND BOUNDARIES

### Selection Modules (events/selection/)

**6 modules, 2,218 lines total**:

| Module | Lines | Responsibility |
|--------|-------|-----------------|
| **core.cljs** | 248 | Mechanism only: multimethod definitions (`execute-confirmed-selection`, `build-selection-for-effect`, `apply-continuation`), confirm/toggle wrappers, cleanup helpers |
| **library.cljs** | 750 | Tutor, scry, peek-and-select, pile-choice, order-bottom, peek-and-reorder builders + execution functions + multimethod registrations |
| **costs.cljs** | 532 | X-cost, exile-cards, return-land, discard-specific cost detection + builders + allocation logic + multimethod registrations |
| **targeting.cljs** | 239 | Cast-time targeting and player-target builders + cast flow helpers + multimethod registrations |
| **zone_ops.cljs** | 325 | Discard (unified), graveyard-return, hand-reveal-discard, chain-bounce, unless-pay builders + execution + multimethod registrations |
| **storm.cljs** | 124 | Storm split builder + execution + stepper event handlers |

**Plus wrapper**:
- **events/selection.cljs** (44 lines): Re-frame event handlers (::set-pending-selection, ::toggle-selection, ::confirm-selection, ::cancel-selection) — keyword namespace for view compatibility

### Engine Modules with Selection-Related Logic

**6 modules, 93 selection references**:

| Module | Role in Selection | Key Functions |
|--------|-------------------|---|
| **effects.cljs** | Effect execution returns tagged `{:db db :needs-selection effect}` for interactive effects | `execute-effect-impl` (multimethod), `reduce-effects` (pauses on interactive), `execute-effect-checked` (full tagged result) |
| **resolution.cljs** | Stack-item resolution detects interactive effects; returns `{:db db :needs-selection effect}` | `resolve-stack-item` (multimethod), `resolve-spell-effects`, pre-resolves targets |
| **validation.cljs** | Pure selection validation logic | `validate-selection` (data-driven rules), discard group matching |
| **rules.cljs** | Casting modes and constraints | References to selection-requiring costs |
| **costs.cljs** (engine) | Cost parsing | Mentions selection in documentation |
| **card_spec.cljs** | Card definition validation | Mentions selection in comments |

### Boundary Assessment

**Selection modules: CLEAR boundaries**
- `core.cljs` exports three multimethods only (mechanism, zero domain knowledge)
- Domain modules (`library.cljs`, `costs.cljs`, etc.) register methods on those multimethods
- No cross-domain imports (costs.cljs → library.cljs, etc.) — only imports core.cljs
- Exports are explicit: builders (fns) + execution defmethods

**Engine modules: FUZZY boundaries**
- `effects.cljs` and `resolution.cljs` don't import selection modules — they only RETURN tagged values
- Selection system detects these tagged returns and builds selections in response
- **No circular dependency risk** — selection imports engine, engine never imports selection
- Boundary is at data interface level (`:needs-selection` tag + selection state shape)

---

## 2. IMPORT/DEPENDENCY GRAPHS

### Selection Module Imports

**core.cljs**:
```
[fizzle.db.queries :as queries]
[fizzle.engine.effects :as effects]      ← reads effect execution signature
[fizzle.engine.resolution :as resolution] ← reads resolution behavior
[fizzle.engine.stack :as stack]          ← stack-item cleanup
[fizzle.engine.validation :as validation] ← selection validation
```

**library.cljs**:
```
[clojure.set :as set]
[datascript.core :as d]
[fizzle.db.queries :as queries]
[fizzle.engine.effects :as effects]      ← execute-effect (in execute-pile-choice)
[fizzle.engine.zones :as zones]
[fizzle.events.selection.core :as core]  ← multimethod registrations
[re-frame.core :as rf]                   ← event handlers
```

**costs.cljs**:
```
[datascript.core :as d]
[fizzle.db.queries :as queries]
[fizzle.engine.mana :as mana]            ← X-cost resolution
[fizzle.engine.rules :as rules]          ← cast-spell-mode
[fizzle.engine.stack :as stack]
[fizzle.engine.targeting :as targeting]
[fizzle.engine.zones :as zones]
[fizzle.events.selection.core :as core]  ← multimethod registrations
[re-frame.core :as rf]
```

**targeting.cljs**:
```
[datascript.core :as d]
[fizzle.db.queries :as queries]
[fizzle.engine.effects :as effects]      ← execute-effect in player-target confirm
[fizzle.engine.rules :as rules]          ← cast-spell-mode, get-casting-modes
[fizzle.engine.targeting :as targeting]
[fizzle.events.selection.core :as core]  ← multimethod registrations
[fizzle.events.selection.costs :as sel-costs] ← only module importing peer!
```

**zone_ops.cljs**:
```
[datascript.core :as d]
[fizzle.db.queries :as queries]
[fizzle.engine.effects :as effects]
[fizzle.engine.grants :as grants]        ← expire grants for cleanup
[fizzle.engine.mana :as mana]            ← pay-mana
[fizzle.engine.stack :as stack]
[fizzle.engine.triggers :as triggers]    ← create-spell-copy
[fizzle.engine.zones :as zones]
[fizzle.events.selection.core :as core]  ← multimethod registrations
```

**storm.cljs**:
```
[fizzle.db.queries :as queries]
[fizzle.engine.stack :as stack]
[fizzle.engine.triggers :as triggers]    ← create-spell-copy
[fizzle.events.selection.core :as core]  ← multimethod registrations
[re-frame.core :as rf]
```

### Who Imports Selection Modules?

**Files importing from events/selection/**:
- `events/game.cljs` — imports all: core, costs, library, storm, targeting, zone-ops (coordinates resolution → selection)
- `events/abilities.cljs` — imports core, costs (ability activation with optional costs)
- `core.cljs` — imports events/selection (namespace loading)
- `views/modals.cljs` — imports events/selection (UI reads selection state, dispatches confirm/toggle)
- `views/mana_pool.cljs` — imports events/selection (mana allocation UI)

**Dependency direction**: `game.cljs` ← hub consuming all selection modules and engine modules

### Circular Dependencies

**NONE DETECTED**:
- No selection module imports from engine modules except to call pure functions (queries, zones, mana)
- Engine modules never import selection modules
- No transitive cycles

### Fan-In / Fan-Out Analysis

**High Fan-In**:
- `core.cljs` — 7 modules import it (all domain modules + game.cljs + abilities.cljs)

**High Fan-Out**:
- `game.cljs` — imports 6 selection modules + full engine suite + other event modules
- `targeting.cljs` — imports `sel-costs` (only cross-domain import within selection)

**Minimal Coupling**:
- Domain modules only import `core.cljs` — no horizontal coupling
- `targeting.cljs` ← `sel-costs` import justified by: "build-mana-allocation-selection" chaining

---

## 3. CO-CHANGE PATTERNS

### Git History (since 2025-12-01)

**131 total commits touching selection/ or engine/**
**28 commits specifically in selection/** (21.4% of related commits)

**Recent selection-specific commits** (ordered newest):
1. `f39dcfe` Fix Portent missing shuffle option and Stifle targeting dialog
2. `61bc46e` Implement peek-and-reorder selection effect (fizzle-d4w2.1)
3. `b878bbd` Fix Foil alternate cost accepting non-Island cards for discard
4. `5c7c30a` Add selection handlers for new cost types and unless-pay counters
5. `17a19c4` Add explicit continuation protocol for selections
6. `a487883` Architecture refactoring: UI atoms, query moves, selection validation, resolve-all
7. `fb2abb9` **Chain mana allocation into spell casting and ability activation flows** ← spans multiple modules

**Key Co-Change Commits**:

- **`fb2abb9`**: Modified costs.cljs (x-mana-cost chaining), targeting.cljs (cast-time targeting chaining), game.cljs (initiate-cast-with-mode)
  - Introduced chained selection pattern: `build-x-mana → build-mana-allocation → confirm-and-cast`

- **`17a19c4`**: Added `apply-continuation` protocol to core.cljs, registered in game.cljs
  - Decoupled post-selection cleanup from selection execution

- **`a487883`**: Refactored selection validation into engine/validation.cljs, updated core.cljs to use it

- **`5c7c30a`**: Added multiple cost types + unless-pay counter support (spans costs.cljs, zone_ops.cljs)

- **Extraction commits** (lines 78c5ab9...ec9b41b): Modularized from monolithic selection.cljs into 6 domain modules over 5 commits

**Cluster Analysis**:
- No circular fix patterns (no fixes undoing previous fixes)
- No workarounds added to suppress side effects
- Changes are additive: new selection types, new chaining patterns, new cost types
- Testing infrastructure changes parallel selection infrastructure changes

---

## 4. ACTUAL CALL PATTERNS AT RUNTIME

### Primary Flow: Spell Resolution → Interactive Effect → Selection

```
game/resolve-one-item (game.cljs)
  ↓
engine/resolve-stack-item (resolution.cljs) [multimethod]
  ↓
engine/resolve-spell-effects (resolution.cljs)
  ↓
effects/reduce-effects (effects.cljs)
  ↓
effect/execute-effect-impl (effects.cljs) [multimethod]
  ├─ returns {:db db} for non-interactive
  └─ returns {:db db :needs-selection effect} for interactive

game/build-selection-from-result (game.cljs)
  ↓
selection/build-selection-for-effect (selection/core.cljs) [multimethod]
  ├─ dispatches on (:effect/type effect) OR :player-target
  └─ domain modules register methods: library, costs, targeting, zone_ops, etc.
    ├─ library/build-tutor-selection
    ├─ library/build-scry-selection
    ├─ library/build-peek-selection
    ├─ library/build-peek-and-reorder-selection
    ├─ costs/build-x-mana-selection
    ├─ costs/build-mana-allocation-selection
    ├─ costs/build-exile-cards-selection
    ├─ costs/build-return-land-selection
    ├─ costs/build-discard-specific-selection
    ├─ targeting/build-player-target-selection
    ├─ targeting/build-cast-time-target-selection
    ├─ zone_ops/build-discard-selection
    ├─ zone_ops/build-graveyard-selection
    ├─ zone_ops/build-hand-reveal-discard-selection
    ├─ zone_ops/build-chain-bounce-selection
    ├─ zone_ops/build-unless-pay-selection
    └─ storm/build-storm-split-selection

→ Sets :game/pending-selection in app-db
```

### Secondary Flow: Selection Confirmation → Execution → Cleanup

```
views/modals.cljs [user clicks confirm]
  ↓
selection/confirm-selection (events/selection.cljs)
  ↓
selection/confirm-selection-handler (selection/core.cljs)
  │ (calls validation/validate-selection for data-driven validation)
  ├─ validates using :selection/validation (exact, at-most, at-least-one, exact-or-zero, always)
  └─ calls confirm-selection-impl (selection/core.cljs)

confirm-selection-impl (selection/core.cljs)
  ├─ reads :selection/on-complete continuation (if present)
  ├─ calls execute-confirmed-selection (selection/core.cljs) [multimethod]
  │   (dispatches on (:selection/type selection))
  │
  │   Domain modules register methods:
  │   ├─ library/execute-tutor-selection
  │   ├─ library/execute-peek-selection
  │   ├─ library/execute-order-bottom-selection
  │   ├─ library/execute-peek-and-reorder-selection
  │   ├─ library/execute-pile-choice
  │   ├─ costs/confirm-spell-mana-allocation
  │   ├─ costs/confirm-ability-mana-allocation
  │   ├─ targeting/confirm-cast-time-target
  │   ├─ zone_ops/execute-discard-selection
  │   ├─ zone_ops/execute-graveyard-selection
  │   ├─ zone_ops/execute-hand-reveal-discard
  │   ├─ zone_ops/execute-chain-bounce-selection
  │   ├─ zone_ops/execute-unless-pay-selection
  │   └─ storm/execute-storm-split
  │
  │   Returns one of:
  │   ├─ {:db db} — standard execution
  │   ├─ {:db db :pending-selection next-sel} — CHAINING
  │   └─ {:db db :finalized? true} — fully handled (pre-cast, ability, etc.)
  │
  ├─ If :pending-selection (chaining):
  │   Update selection and set :game/pending-selection to chained selection
  │
  ├─ If :finalized?:
  │   Dissoc :game/pending-selection
  │   Apply continuation if present
  │
  └─ Else (standard):
      Execute :selection/remaining-effects via effects/reduce-effects
      Call cleanup-selection-source
      Dissoc :game/pending-selection
      Apply continuation if present

cleanup-selection-source (selection/core.cljs)
  ├─ If :selection/source-type is :stack-item:
  │   Remove stack-item by EID
  │
  └─ Else (spell on stack):
      1. remove-spell-stack-item (find and remove stack-item)
      2. resolution/move-resolved-spell (zone transition)

apply-continuation (selection/core.cljs) [multimethod, dispatches on :continuation/type]
  ├─ game.cljs registers :resolve-one-and-stop continuation
  │   → Resumes stack resolution
  │
  └─ :default does nothing (identity)
```

### Chaining Pattern Example: Targeted Storm Spell Cast

```
cast-spell (game.cljs)
  ↓
cast-spell-with-targeting (targeting.cljs)
  ├─ Has :card/targeting? Yes
  └─ → Set :game/pending-selection = cast-time-targeting selection

User selects target
  ↓
execute-confirmed-selection :cast-time-targeting (targeting.cljs)
  ├─ Has generic mana? Yes
  ├─ build-mana-allocation-selection (costs.cljs)
  │   [pre-resolves targets in selection state]
  └─ → Set :game/pending-selection = mana-allocation selection
      [carries :selection/pending-targets {target-id value}]

User allocates mana
  ↓
execute-confirmed-selection :mana-allocation (costs.cljs)
  ├─ confirm-spell-mana-allocation (costs.cljs)
  │   ├─ cast-spell-mode-with-allocation (pays mana with explicit allocation)
  │   ├─ Stores :selection/pending-targets on stack-item
  │   └─ Returns {:finalized? true}
  │
  └─ Stack now has spell with targets stored
      → resolve-one-item → resolution succeeds with targets

On resolve:
  ↓
resolve-spell-effects (resolution.cljs)
  ├─ Reads :stack-item/targets
  ├─ Checks target legality
  └─ Executes effects with pre-resolved targets
```

### Multimethod Dispatch Registration Pattern

All domain modules follow same pattern:

```clojure
;; In library.cljs, costs.cljs, targeting.cljs, etc.

(defmethod core/build-selection-for-effect :tutor
  [db player-id object-id effect remaining]
  (build-tutor-selection db player-id object-id effect remaining))

(defmethod core/execute-confirmed-selection :tutor
  [game-db selection]
  (let [selected (:selection/selected selection)]
    {:db (execute-tutor-selection game-db selection)}))
```

**Registry is implicit**: Each module that requires `[fizzle.events.selection.core :as core]` automatically registers its methods when the namespace loads.

**Coupling point**: `game.cljs` imports all domain modules (+ wrapper) to ensure methods are registered:
```clojure
[fizzle.events.selection]          ← wrapper (re-frame events)
[fizzle.events.selection.core]     ← multimethods
[fizzle.events.selection.costs]    ← registers methods
[fizzle.events.selection.library]  ← registers methods
[fizzle.events.selection.storm]    ← registers methods
[fizzle.events.selection.targeting] ← registers methods
[fizzle.events.selection.zone-ops] ← registers methods
```

---

## 5. INTERFACE SURFACE AREA

### core.cljs Public API

**Multimethods** (dispatch points):
- `execute-confirmed-selection(game-db, selection) → {:db db} | {:db db :pending-selection sel} | {:db db :finalized? true}`
- `build-selection-for-effect(db, player-id, object-id, effect, remaining-effects) → selection-state`
- `apply-continuation(continuation, app-db) → app-db`

**Functions**:
- `toggle-selection-impl(app-db, id) → app-db`
- `confirm-selection-impl(app-db) → app-db`
- `confirm-selection-handler(app-db) → app-db`
- `remove-spell-stack-item(game-db, spell-id) → game-db`
- `cleanup-selection-source(game-db, selection) → game-db`

**Validation**:
- `engine/validation/validate-selection(selection) → bool`

### Domain Module Public API (e.g., library.cljs)

**Builders**:
- `build-tutor-selection(...) → selection`
- `build-scry-selection(...) → selection | nil`
- `build-peek-selection(...) → selection | nil`
- `build-pile-choice-selection(...) → selection`
- `build-peek-and-reorder-selection(...) → selection | nil`
- `build-order-bottom-selection(...) → selection`

**Executors**:
- `execute-tutor-selection(game-db, selection) → game-db`
- `execute-peek-selection(game-db, selection) → game-db`
- `execute-order-bottom-selection(game-db, selection) → game-db`
- `execute-peek-and-reorder-selection(game-db, selection) → game-db`
- `execute-pile-choice(game-db, selection) → game-db`

**Helper Functions**:
- `select-random-pile-choice(app-db) → app-db`
- `any-order-selection(selection) → selection`
- `order-card-in-selection(selection, object-id) → selection`
- `unorder-card-in-selection(selection, object-id) → selection`

**Event Handlers** (registered with re-frame):
- `:fizzle.events.selection/scry-assign-top`, `:scry-assign-bottom`, `:scry-unassign`
- `:fizzle.events.selection/order-card`, `:unorder-card`, `:any-order`
- `:fizzle.events.selection/select-random-pile-choice`
- `:fizzle.events.selection/shuffle-and-confirm`

### Interface Between events/selection/ and events/game.cljs

**game.cljs calls into selection**:
```clojure
(sel-core/build-selection-for-effect game-db player-id source-id effect remaining-effects)
  → Returns selection map or nil

(sel-storm/build-storm-split-selection game-db controller top)
  → Returns selection map or nil (detection of :needs-storm-split tag)

(sel-core/apply-continuation continuation updated-app-db)
  → Returns app-db (post-selection cleanup hook)
```

**game.cljs detects interactive effects**:
```clojure
(engine-resolution/resolve-stack-item game-db top)
  → If result has :needs-selection or :needs-storm-split tag,
    game.cljs converts to pending selection
```

### Interface Between events/selection/ and engine modules

**Selection → Engine** (one-way only):
- Calls pure functions: `queries/*`, `zones/move-to-zone`, `rules/cast-spell-mode`, `stack/create-stack-item`, etc.
- NO circular dependency: engine modules never call back to selection

**Engine → Selection** (indirectly via tags):
- `effects/execute-effect-impl` returns `{:db db :needs-selection effect}` for interactive effects
- `resolution/resolve-stack-item` returns `{:db db :needs-selection effect}` when effect execution needs input
- game.cljs detects tags and dispatches to `build-selection-for-effect`

### Broad vs Narrow Interfaces

**Broad**:
- `events/game.cljs` imports all 6 selection modules (to ensure method registration)
- Could be narrowed: `events/selection.cljs` could re-export all domain modules

**Narrow**:
- Individual domain modules export only their builders + execution functions
- Multimethods are extensible (no centralized registry)
- Validation is separated into engine/validation.cljs (no selection import needed)

**No Interface Issues Detected**:
- All interfaces are well-defined (EDN selection maps, multimethod dispatch)
- No implicit contracts (all selection keys documented in docstrings)
- Defensive: builders return `nil` when no selection needed (e.g., scry on empty library)

---

## 6. SHARED STATE

### Selection State in app-db

**Single entry point**: `:game/pending-selection` (optional map, dissoc'd when no selection active)

**Selection State Keys** (comprehensive):

**Universal** (all selections):
- `:selection/type` — keyword identifying selection type (tutor, discard, etc.)
- `:selection/player-id` — player making the selection
- `:selection/selected` — set of selected object-ids (or other identifiers)
- `:selection/validation` — data-driven validation rule (exact, at-most, etc.)
- `:selection/auto-confirm?` — bool, auto-confirm when selection complete

**Standard Card Selections** (most selections):
- `:selection/select-count` — count required
- `:selection/card-source` — where cards come from (hand, library, battlefield, etc.)
- `:selection/zone` — zone name
- `:selection/spell-id` — originating spell object-id
- `:selection/remaining-effects` — effects to execute after selection
- `:selection/exact?` — strict validation (deprecated, use :validation instead)

**Cast-Time Targeting**:
- `:selection/object-id` — spell being cast
- `:selection/mode` — casting mode being used
- `:selection/target-requirement` — targeting spec from card
- `:selection/valid-targets` — vector of valid target IDs
- `:selection/pending-targets` — carried through mana allocation chaining

**Library Operations**:
- `:selection/candidates` or `:selection/candidate-ids` — pool of choices
- `:selection/target-zone` — destination zone (tutor)
- `:selection/shuffle?` — whether to shuffle after (tutor)
- `:selection/allow-fail-to-find?` — tutor can return nothing
- `:selection/entries` (scry) — cards being reordered
- `:selection/top-pile` (scry) — cards going to top
- `:selection/bottom-pile` (scry) — cards going to bottom
- `:selection/hand-count` (pile-choice) — how many to hand
- `:selection/ordered` (order-bottom, peek-and-reorder) — user's click order
- `:selection/may-shuffle?` (peek-and-reorder) — Portent's shuffle option

**Cost Selections**:
- `:selection/selected-x` (x-mana-cost) — X value user chose (0..max-x)
- `:selection/max-x` (x-mana-cost) — maximum legal X
- `:selection/generic-remaining` (mana-allocation) — generic mana still to allocate
- `:selection/generic-total` (mana-allocation) — original generic cost
- `:selection/allocation` (mana-allocation) — map of mana color → count allocated
- `:selection/remaining-pool` (mana-allocation) — mana available after colored costs
- `:selection/original-remaining-pool` (mana-allocation) — for reset button
- `:selection/colored-cost` (mana-allocation) — non-generic mana requirement
- `:selection/original-cost` (mana-allocation) — resolved cost with X
- `:selection/mode` — casting mode (for mana allocation)
- `:selection/source-type` — :ability or nil (spell) for mana allocation

**Special Cases**:
- `:selection/cleanup?` (discard) — true for cleanup discard, false for normal discard
- `:selection/stack-item-eid` (ability selections) — entity ID of activated ability on stack
- `:selection/source-type` — :stack-item for ability selections
- `:selection/valid-targets` (hand-reveal-discard) — vector of selectable card IDs
- `:selection/target-player` (hand-reveal-discard) — opponent's player ID
- `:selection/chain-controller` (chain-bounce) — land controller
- `:selection/chain-target-id` (chain-bounce) — bounced permanent
- `:selection/chain-copy-object-id` (chain-bounce-target) — copy's object ID
- `:selection/chain-copy-stack-item-eid` (chain-bounce-target) — copy's stack-item EID
- `:selection/copy-count` (storm-split) — storm copy count
- `:selection/allocation` (storm-split) — map of target player-id → copy count
- `:selection/source-object-id` (storm-split) — spell being copied
- `:selection/controller-id` (storm-split) — storm controller

**On-Complete Hook**:
- `:selection/on-complete` — `{:continuation/type :keyword, ...}` (optional)

**Candidate Card Mapping**:
- `:selection/candidate-card-map` (discard-specific) — map of object-id → card-data for validation

### Shared State Between Selection Modules

**ZERO horizontal state sharing**:
- Each domain module (library, costs, targeting, zone_ops, storm) maintains its own state
- No cross-module state reading/writing
- All communication through `execute-confirmed-selection` return values

**State Transitions**:
1. **Chaining**: execution function returns `{:pending-selection next-sel}` → wrapper updates app-db
2. **Finalization**: execution function returns `{:finalized? true}` → wrapper cleans up and optionally applies continuation
3. **Standard**: execution function returns `{:db db}` → wrapper executes remaining effects and cleans up

### Mutable State Outside Selection

**NONE in selection system proper**, but:
- `app-db/:game/db` (Datascript) — game state mutations (immutable value, not mutable reference)
- `:game/selected-card` — optional UI state (which card player moused over)
- View-layer state (React component local state) — NOT game state

**Immutability guarantee**: All selection functions are pure, all return new maps/dbs, no atoms or refs used for selection state.

---

## 7. COMPENSATING CODE PATTERNS

### Pattern 1: Defensive Nil Builders

**Occurs in**: library.cljs, costs.cljs
**Purpose**: Handle edge cases where selection can't be built (empty zone, insufficient resources)

```clojure
(defn build-scry-selection [game-db player-id object-id scry-effect effects-after]
  (let [amount (or (:effect/amount scry-effect) 0)]
    (when (pos? amount)  ; ← Returns nil if amount is 0
      (let [library-cards (queries/get-top-n-library game-db player-id amount)]
        (when (seq library-cards)  ; ← Returns nil if library empty
          {...selection...})))))
```

**Caller responsibility**: `build-selection-from-result` (game.cljs) must handle nil:
```clojure
(if-let [sel (sel-storm/build-storm-split-selection game-db controller top)]
  {:db game-db :pending-selection sel}
  {:db (stack/remove-stack-item game-db (:db/id top))})  ; ← Fizzles spell if no selection
```

**Anti-pattern avoidance**: This is INTENTIONAL, not a workaround. Nil-returning builders are a design principle (fail-to-find, no library cards, etc.).

### Pattern 2: Mana Allocation Chaining Carries Targets

**Occurs in**: targeting.cljs, costs.cljs
**Purpose**: Preserve cast-time targeting information through mana allocation phase

```clojure
;; In execute-confirmed-selection :cast-time-targeting
(let [sel (sel-costs/build-mana-allocation-selection game-db player-id object-id mode cost)]
  (if sel
    {:db game-db
     :pending-selection (assoc sel :selection/pending-targets {target-id selected-target})}
    ...))

;; In confirm-spell-mana-allocation (costs.cljs)
(let [pending-targets (:selection/pending-targets selection)
      ...
      db-final (if pending-targets
                 (d/db-with db-after-cast
                            [[:db/add stack-item-eid :stack-item/targets pending-targets]])
                 db-after-cast)]
  {:db db-final :finalized? true})
```

**Compensating**: Without this, targets would be lost during mana allocation phase. The pattern threads targets through selection chaining explicitly.

**Not a workaround**: This is correct architecture — mana allocation is a separate selection type, targets belong on mana allocation state, not the allocation mechanism.

### Pattern 3: Discard Unification with Cleanup Flag

**Occurs in**: zone_ops.cljs, core.cljs
**Purpose**: Handle cleanup discard (end-of-turn) differently from normal discard (mid-resolution)

```clojure
;; build-cleanup-discard-selection (game.cljs)
{:selection/type :discard
 :selection/cleanup? true}  ; ← Marks this as cleanup discard

;; execute-confirmed-selection :discard (zone_ops.cljs)
(let [selected (:selection/selected selection)
      db-after-discard (reduce (fn [gdb obj-id]
                                 (zones/move-to-zone gdb obj-id :graveyard))
                               game-db selected)]
  (if (:selection/cleanup? selection)
    ;; Cleanup path: expire grants and return finalized
    (let [game-state (queries/get-game-state db-after-discard)
          current-turn (:game/turn game-state)
          db-final (grants/expire-grants db-after-discard current-turn :cleanup)]
      {:db db-final :finalized? true})
    ;; Standard path: wrapper handles remaining-effects
    {:db db-after-discard}))
```

**Rationale**: Without flag, cleanup discard would execute remaining-effects (none), then wrapper would try to expire grants (already done). Discard unification reduces code duplication while keeping paths distinct.

**Not ideal**: Could be separate `:cleanup-discard` type, but data-driven flag is lighter. Trade-off: code simplicity vs multimethod proliferation.

### Pattern 4: Belt-and-Suspenders in Library Ordering

**Occurs in**: library.cljs (execute-order-bottom, execute-peek-and-reorder)
**Purpose**: Ensure all cards stay ordered even if user doesn't order all of them

```clojure
(defn execute-order-bottom-selection [game-db selection]
  (let [ordered (:selection/ordered selection)
        candidates (:selection/candidates selection)
        ...
        ;; Belt and suspenders: include any candidates not yet in ordered
        ordered-set (set ordered)
        unordered (remove ordered-set candidates)
        final-ordered (into (vec ordered) (shuffle (vec unordered)))]  ; ← Shuffles unordered
    ...))
```

**Purpose**: User may not order all remainder cards (UI allows partial ordering). Don't leave unordered cards in arbitrary state.

**Not a workaround**: Explicit contract — user orders some, rest are randomized. This is intentional, documented in selection builder.

### Pattern 5: Defensive Target Lookups During Chain Bounce

**Occurs in**: zone_ops.cljs
**Purpose**: Handle case where copy creation fails (source spell disappeared)

```clojure
(defn- build-chain-bounce-target-selection [game-db chain-controller spell-id copy-object-id copy-stack-item-eid]
  (let [;; Find all nonland permanents on the battlefield (any controller)
        p1-permanents (or (queries/query-zone-by-criteria ...) [])
        ...
        all-permanents (concat p1-permanents p2-permanents)
        target-ids (set (mapv :object/id all-permanents))]
    {:selection/type :chain-bounce-target
     ...
     :selection/auto-confirm? (empty? target-ids)}))  ; ← Auto-confirms if no targets

;; In execute-confirmed-selection :chain-bounce
(if (nil? copy-stack-item)
  ;; Failed to create copy (source gone) — just return after sacrifice
  {:db db-after-sac}
  ;; Chain to target selection for the copy
  {:db db-with-copy :pending-selection target-sel})
```

**Defensiveness**: Chain of Vapor's chain mechanic can fail at multiple points (source gone, no permanents to target). Each failure point has explicit handling.

**Not a workaround**: Explicit guard clauses for failure modes. MTG rules allow chains to terminate.

### Pattern 6: Auto-Confirm with Data-Driven Flag

**Occurs in**: core.cljs, all domain modules
**Purpose**: Some selections can confirm themselves (single target with no choices)

```clojure
;; build-return-land-selection (costs.cljs)
{...
 :selection/auto-confirm? true}  ; ← Confirms immediately

;; toggle-selection-impl (core.cljs)
(if (and selected?
         (= select-count 1)
         (:selection/auto-confirm? selection))
  (confirm-selection-impl new-db)  ; ← Auto-confirms
  new-db)
```

**Data-driven**: Instead of hard-coding "return-land always auto-confirms", the builder sets a flag.

**Benefit**: New selection types don't need code changes in core — just set :auto-confirm? = true.

### Pattern 7: Validation Abstraction

**Occurs in**: core.cljs, engine/validation.cljs
**Purpose**: Data-driven validation rules instead of per-type logic

```clojure
;; core.cljs
(if (validation/validate-selection selection)
  (confirm-selection-impl app-db)
  app-db)

;; engine/validation.cljs
(case (:selection/validation selection)
  :exact (= count-selected select-count)
  :at-most (<= count-selected select-count)
  :at-least-one (pos? count-selected)
  :always true
  :exact-or-zero (or (zero? count-selected) (= count-selected select-count))
  false)  ; ← Safe default: reject
```

**Design**: Validation rules are EDN data in selection map, not hard-coded per type.

**Benefit**: New selection types just set `:selection/validation :exact` (or whatever), no code needed in validator.

---

## 8. FIX CLUSTERING AND WORKAROUNDS

### Recent Fix Clusters (2025-12-01 to 2026-02-28)

**Cluster 1: Cost System Hardening** (commits: b878bbd, 5c7c30a, 1253912)
- `b878bbd` Fix Foil (discard-specific cost validation)
- `5c7c30a` Add selection handlers for new cost types
- `1253912` Add return-land, discard-specific costs and unless-pay counter support

**Pattern**: New cost type added → validation bugfix → selection handler added
**Not cyclic**: Each fix moves system forward, no rework of previous logic

**Cluster 2: Targeting Hardening** (commits: f39dcfe, aacadae)
- `f39dcfe` Fix Portent missing shuffle option and Stifle targeting dialog
- `aacadae` Fix unless-pay modal to use inline non-blocking UI with reactive mana check

**Pattern**: New card with targeting → UI/UX fix
**Not a workaround**: UI and targeting interaction discovered through implementation

**Cluster 3: Selection Refactoring** (commits: 78c5ab9...ec9b41b, a487883, 9f5e75c)
- Modular extraction from monolithic selection.cljs
- Architecture refactoring: UI atoms, query moves, selection validation
- Complete test infrastructure epic

**Pattern**: Architecture improvement → testing infrastructure added → validation separated
**Clean refactoring**: No workarounds, improvements are structural

### No Workaround Patterns Detected

**What I looked for**:
- Conditional branches like "if selection-type is X, do special thing" scattered across modules
  - **Found only in game.cljs**: `resolve-one-item` checks `:needs-storm-split` separately (correct)
  - **Found in execution**: `:selection/cleanup?` flag (discard only) — data-driven, not scattered

- Code to bridge incompatible interfaces
  - **Not found**: All interfaces are well-defined

- Special cases to suppress or work around side effects
  - **Not found**: Each selection type handles its own side effects cleanly

- Circular dependency workarounds
  - **Not found**: Dependency is acyclic

### Fix Messages Referencing Selection Issues

```
f39dcfe Fix Portent missing shuffle option and Stifle targeting dialog
  → Feature implementation, not workaround

61bc46e Implement peek-and-reorder selection effect (fizzle-d4w2.1)
  → New feature, building on existing patterns

b878bbd Fix Foil alternate cost accepting non-Island cards for discard
  → Validation rule bug, legitimate fix

5c7c30a Add selection handlers for new cost types and unless-pay counters
  → Feature expansion, not workaround

17a19c4 Add explicit continuation protocol for selections
  → Architecture improvement, not workaround

3d86cd0 Fix cast-and-yield bugs: cascade resolution, mana allocation, lazy seq warnings
  → Real bugs (lazy sequences in db queries, cascade logic) — not workarounds
```

**Conclusion**: No defensive workarounds or band-aids detected. Selection system is clean.

---

## Summary of Key Findings

### Architecture Strengths

1. **Clear Separation of Concerns**
   - `core.cljs` = mechanism only (multimethods, wrappers, validation)
   - Domain modules = policy (what each selection type does)
   - Engine = effect execution (no selection knowledge)

2. **Data-Driven Design**
   - Selection validation: `:selection/validation` rules (exact, at-most, etc.)
   - Auto-confirm: `:selection/auto-confirm?` flag
   - Chaining: `:pending-selection` return value signals next selection

3. **No Circular Dependencies**
   - Selection imports engine (one-way)
   - Engine never imports selection (clean separation)
   - Tagged return values (`{:needs-selection effect}`) as interface

4. **Extensible via Multimethods**
   - New selection types register via defmethod (no central registry)
   - New effect types register via defmethod
   - All domain modules follow same pattern

### Architectural Risks / Tensions

1. **High Hub Coupling in game.cljs**
   - Imports all 6 selection modules + all engine modules
   - Necessary for multimethod registration (could be reduced with a loader module)
   - Not circular, but high cognitive load

2. **Continuation Protocol Weakness**
   - `apply-continuation` only used by `game.cljs` for `:resolve-one-and-stop`
   - Pattern exists but not widely used — unclear if scalable
   - Could become a dumping ground for post-selection behavior

3. **Implicit Method Registration**
   - Multimethods register when namespaces load (no explicit registry)
   - Requires `game.cljs` to import all domain modules
   - Would fail silently if a module is not imported

4. **Discard Unification with Flag**
   - Using `:cleanup?` flag instead of separate `:cleanup-discard` type
   - Works but breaks "each selection type is a domain module" model
   - Cleanup discard logic lives in zone_ops.cljs alongside normal discard

5. **Mana Allocation Threading**
   - Carries `:pending-targets` through mana-allocation selection
   - Correct but adds coupling between targeting and costs domains
   - `targeting.cljs` imports `sel-costs` (only inter-domain import)

### No Critical Issues

- No circular dependencies
- No mutable state for game logic
- No implicit state coupling between domain modules
- No silent failure modes (nil builders are explicit)
- No compensating code for broken abstractions (defensive code is intentional)

