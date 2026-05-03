# Selection System Data Flows: Module-Level Tracing

**Date:** 2026-04-02  
**Scope:** Module-level request paths, implicit ordering, shared app-db communication

---

## 1. Entry Points: Request Paths & Module Participation

### 1A. User Casts a Spell (Cast-spell event handler)

**Entry:** `::cast-spell` → `events/casting.cljs:cast-spell-handler` (line 241)

**Path A: Non-modal, single castable mode**
```
casting.cljs:cast-spell-handler
  → casting.cljs:initiate-cast-with-mode (line 141)
    → casting.cljs:evaluate-pre-cast-step loop (line 152)
      → selection/costs.cljs: has-exile-cards-x-cost?, build-exile-cards-selection (line 48-50)
      → selection/targeting.cljs: build-cast-time-target-selection (line 125)
      → selection/costs.cljs: build-mana-allocation-selection (line 134)
    → [If any step returns {:selection sel}]
      → app-db[:game/pending-selection] = selection (line 162)
      → app-db[:history/deferred-entry] = {:type :cast-spell, ...} (line 265-270)
    → [If all steps skip, cast immediately]
      → app-db[:game/db] = rules/cast-spell-mode result (line 156)
      → app-db[:history/pending-entry] = descriptions/build-pending-entry (line 299-303)
```

**Path B: Modal spell with :card/modes**
```
casting.cljs:cast-spell-handler
  → casting.cljs:get-valid-spell-modes (line 169)
  → casting.cljs:build-spell-mode-selection (line 184)
    → Returns selection with :selection/type :spell-mode
    → :selection/on-complete {:continuation/type :cast-after-spell-mode} (line 199)
  → app-db[:game/pending-selection] = spell-mode selection (line 263)
  → app-db[:history/deferred-entry] = {:type :cast-spell, ...} (line 265-270)
  → [User toggles mode selection via ::toggle-selection]
    → selection/core.cljs:confirm-selection-impl executes :spell-mode defmethod (line 203)
    → selection/core.cljs:finalized-path routes execution (line 379)
    → drain-continuation-chain calls apply-continuation for :cast-after-spell-mode (line 342)
    → casting.cljs:apply-continuation :cast-after-spell-mode (line 213)
      → casting.cljs:initiate-cast-with-mode (line 221)
      → [Mode selection is finalized, so clears :game/pending-selection]
      → [If pre-cast selections needed, chains them]
      → [If pre-cast selections NOT needed, sets :history/pending-entry now]
```

**Data shapes crossing boundaries:**
- `casting.cljs` → `selection/targeting.cljs`: spell object-id, player-id, target-req
- `selection/targeting.cljs` → returns: `{:selection/type :cast-time-targeting :selection/lifecycle :chaining ...}`
- `selection/costs.cljs` → returns: `{:selection/type :x-mana-cost :selection/lifecycle :finalized ...}`
- `casting.cljs` writes: `:game/pending-selection`, `:history/deferred-entry`

---

### 1B. User Confirms a Selection (::confirm-selection event)

**Entry:** `selection.cljs:::confirm-selection` → `selection/core.cljs:confirm-selection-handler` (line 508)

**Synchronous flow (no re-frame dispatch in the middle):**
```
selection/core.cljs:confirm-selection-handler (line 508)
  → selection/core.cljs:confirm-selection-impl (line 408)
    1. validation/validate-selection (line 426)
    2. Read :selection/on-complete from selection (line 428)
    3. Call execute-confirmed-selection multimethod (line 430)
       → Dispatches on :selection/type via hierarchy
       → Returns {:db updated-db} (line 79-81)
    4. Case on :selection/lifecycle (line 431)
       A. :standard (line 436)
          → Call standard-path (line 348)
            → effects/reduce-effects on :selection/remaining-effects (line 356)
            → [If result has :needs-selection]
              → build-selection-for-effect for next interactive effect (line 363)
              → app-db[:game/pending-selection] = next-sel (line 368)
            → [If no more interactive effects]
              → cleanup-selection-source (line 370)
              → app-db[:game/db] = db-final (line 372)
              → app-db dissoc :game/pending-selection (line 373)
              → [If on-complete continuation present]
                → drain-continuation-chain (line 375)
       B. :finalized (line 434)
          → Call finalized-path (line 379)
            → app-db[:game/db] = result db (line 385)
            → app-db dissoc :game/pending-selection (line 386)
            → [If on-complete continuation present]
              → drain-continuation-chain (line 388)
       C. :chaining (line 433)
          → Call chaining-path (line 393)
            → build-chain-selection calls next-selection builder (line 397)
            → [If returns non-nil]
              → app-db[:game/pending-selection] = chained-sel (line 403)
            → [If returns nil]
              → Fall through to standard-path (line 405)
    5. [If selection chain complete (nil :game/pending-selection)]
       → process-deferred-entry (line 440)
         → Read :history/deferred-entry from app-db (line 316)
         → Call descriptions/describe-* based on :type (line 318-322)
         → app-db[:history/pending-entry] = build-pending-entry result (line 326)
         → app-db dissoc :history/deferred-entry (line 324)
```

**Continuation draining (drain-continuation-chain, line 338):**
```
drain-continuation-chain (on-complete app-db)
  → Loop: apply-continuation(on-complete, app-db) → result
    → Read :then from result (line 343)
    → [If :then present]
      → recur with next-cont, result[:app-db]
    → [If no :then]
      → Return final app-db
```

**Data shapes crossing boundaries:**
- `selection/core.cljs` → multimethod: `{:selection/type :keyword :selection/lifecycle :standard|:finalized|:chaining ...}`
- `selection/core.cljs` → `effects/reduce-effects`: game-db, player-id, effects vector
- `selection/core.cljs` → `core/build-selection-for-effect`: effect, remaining-effects
- `selection/core.cljs` → `core/build-chain-selection`: current selection
- `selection/core.cljs` → `histories/descriptions`: game-db before/after, object-id
- `selection/core.cljs` writes: `:game/pending-selection`, `:history/pending-entry`, `:history/deferred-entry`, `:game/db`

---

### 1C. Stack Resolution Produces Interactive Effect (::resolve-top event)

**Entry:** `resolution.cljs:::resolve-top` → `resolution.cljs:resolve-one-item` (line 63)

**Path:**
```
resolution.cljs:resolve-one-item (line 63)
  → engine-resolution/resolve-stack-item (line 72)
    → [Returns one of: :needs-selection, :needs-storm-split, :needs-attackers, etc.]
  → [If :needs-selection]
    → resolution.cljs:build-selection-from-result (line 25)
      → selection/core.cljs:build-selection-for-effect (line 37)
        → Dispatches on :effect/type (targeting, library ops, zone ops, etc.)
        → Returns selection state map
      → [If non-spell stack item (ability)]
        → Adjust selection to use :selection/stack-item-eid instead of :selection/spell-id (line 44-48)
    → app-db[:game/pending-selection] = sel (line 118)
  → [If :needs-storm-split]
    → selection/storm.cljs:build-storm-split-selection (line 75)
    → app-db[:game/pending-selection] = storm-sel (line 76)
  → [If other special cases]
    → Create appropriate selection or process immediately
  → [No deferred-entry set by resolution path — selection is complete]
```

**Data shapes crossing boundaries:**
- `resolution.cljs` → `engine-resolution/resolve-stack-item`: game-db, stack-item
- `resolution.cljs` → `selection/core.cljs:build-selection-for-effect`: effect, remaining-effects
- `resolution.cljs` writes: `:game/pending-selection`, `:game/db`

---

### 1D. User Activates an Ability (non-mana, targeted)

**Entry:** `abilities.cljs:activate-ability` (implied via UI dispatch)

**Path:**
```
abilities.cljs:activate-ability-handler [if exists, or UI dispatches build-ability-target-selection]
  → abilities.cljs:build-ability-target-selection (line 41)
    → targeting/find-valid-targets (line 58)
    → Returns {:selection/type :ability-targeting :selection/lifecycle :chaining|:finalized ...} (line 62-76)
  → app-db[:game/pending-selection] = selection (implicit via event handler pattern)
  → [User confirms selection via ::confirm-selection]
    → selection/core.cljs:confirm-selection-impl
      → execute-confirmed-selection :ability-targeting defmethod [not visible in read, but registered]
      → [For multi-target abilities]
        → Standard-path chains to next target selection (remaining-target-reqs)
      → [For final target]
        → Calls abilities/pay-all-costs (line 127)
        → stack/create-stack-item (line 137)
```

**Data shapes crossing boundaries:**
- `abilities.cljs` → `targeting/find-valid-targets`: game-db, player-id, target-req
- `abilities.cljs` → returns: `{:selection/type :ability-targeting :selection/lifecycle :chaining ...}`
- `abilities.cljs` writes: `:game/pending-selection` (via selection system)

---

### 1E. User Toggles a Selection (::toggle-selection event)

**Entry:** `selection.cljs:::toggle-selection` → `selection/core.cljs:toggle-selection-impl` (line 449)

**Path (no multimethod dispatch, pure data-driven):**
```
selection/core.cljs:toggle-selection-impl (line 449)
  1. Read :game/pending-selection (line 460)
  2. Check :selection/valid-targets if present (line 468)
  3. Case on select-count and currently-selected? state (line 466-496)
     A. Already selected: disj from :selection/selected (line 473)
     B. Single-select (select-count=1): replace set (line 479)
     C. Unlimited (exact?=false): always add (line 485)
     D. Multi-select under limit: add (line 491)
     E. At limit: ignore (line 496)
  4. Check :selection/auto-confirm? (line 497-499)
     → If true AND selected? AND select-count=1
       → Return {:app-db :auto-confirm? true}
  5. Return {:app-db updated-app-db :auto-confirm? boolean} (line 500-501)
→ [In re-frame handler, if :auto-confirm? true]
  → Dispatch ::confirm-selection as fx effect (line 31)
```

**Data shapes crossing boundaries:**
- No explicit multi-module calls; all data-driven via selection map fields
- Reads: `:selection/selected`, `:selection/valid-targets`, `:selection/select-count`, `:selection/exact?`, `:selection/auto-confirm?`
- Writes: `:game/pending-selection :selection/selected`

---

## 2. Implicit Structural Relationships NOT Visible in Import Graph

### 2A. Shared App-DB Keys as Communication Channel

**`:game/pending-selection`** — The central hub:
- **Writers:** `casting.cljs`, `resolution.cljs`, `selection/core.cljs` (all lifecycle paths)
- **Readers:** `selection/core.cljs`, `priority_flow.cljs` (guards to prevent action during selection)
- **Semantics:** Only ONE selection exists at app-db level. Chaining creates NEW selection in same slot.
- **Protocol:** Lifecycle (`selection/lifecycle`) declares what happens next; executor doesn't signal back.

**`:history/deferred-entry`** — Backward-signaling from selection system to history system:
- **Writers:** `casting.cljs` (lines 265-270, 290-295), `priority_flow.cljs` (lines 137-144)
- **Readers:** `selection/core.cljs:process-deferred-entry` (line 316, terminal step)
- **Semantics:** Stores game-db state BEFORE spell cast for "cast-and-yield" semantics (need pre-cast db for description)
- **Critical detail:** Set BEFORE selection begins, used AFTER selection chain completes

**`:history/pending-entry`** — Forward signal to history system:
- **Writers:** `cycling.cljs`, `abilities.cljs`, `casting.cljs`, `priority_flow.cljs`, `selection/core.cljs:process-deferred-entry`
- **Readers:** `history/interceptor.cljs` (after interceptor runs, line 16)
- **Semantics:** Snapshot, event-type, description, turn, principal
- **Timing:** Set ONLY when selection chain is complete (process-deferred-entry terminal step)

**`:game/pending-mode-selection`** — Alternative modal selection (NOT standard selection system):
- **Writers:** `casting.cljs` (line 281)
- **Readers:** `casting.cljs:select-casting-mode-handler` (line 322)
- **Semantics:** Spell-mode selection UI state (different from standard selection system)
- **Lifecycle:** NOT part of selection/core.cljs chaining; cleared when mode selected

### 2B. Multimethod Registration Creates Runtime Dependencies Invisible in File Structure

**`execute-confirmed-selection` multimethod (line 71 in selection/core.cljs):**
- **Hierarchy dispatch key:** `:selection/type` (via `selection-hierarchy`, line 31-64)
- **Registrations by module:**
  - `selection/core.cljs`: `:zone-pick` (line 181)
  - `targeting.cljs`: `:cast-time-targeting`, `:player-target`, `:ability-cast-targeting`
  - `zone_ops.cljs`: `:discard`, `:hand-reveal-discard`, `:chain-bounce`, `:chain-bounce-target`
  - `library.cljs`: `:tutor`, `:scry`, `:peek-and-reorder`, `:order-bottom`, `:pile-choice`
  - `costs.cljs`: `:x-mana-cost`, `:mana-allocation`, `:discard-specific-cost`, `:return-land-cost`, `:pay-x-life-cost`
  - `casting.cljs`: `:spell-mode` (line 203)
  - `storm.cljs`: `:storm-split` (line 64)
  - `combat.cljs`: `:attacker-selection`, `:blocker-selection`
- **Dependency:** None of these modules import each other or selection/core.cljs; they only import the namespace to register defmethods

**`build-selection-for-effect` multimethod (line 86 in selection/core.cljs):**
- **Dispatch key:** `:effect/type` (hierarchy-based, line 90)
- **Default behavior:** Generic zone-pick builder (line 148)
- **Registrations:**
  - `zone_ops.cljs`: `:discard-from-revealed-hand` (line 57)
  - `targeting.cljs`: `:player-target` (implied)
  - `costs.cljs`: Cost-type builders (implicit via pipeline)
- **Dependency:** Inverse: engine modules define effect types, selection modules register builders for them

**`build-chain-selection` multimethod (line 221 in selection/core.cljs):**
- **Dispatch key:** `:selection/type`
- **Generic handler:** `:builder-declared-chain` (line 238) — reads `:selection/chain-builder` fn on selection map
- **Registrations:**
  - `library.cljs`: implicit via `:builder-declared-chain` (line 66-74)
  - `costs.cljs`: implicit via `:builder-declared-chain`
  - Other modules likely use `:builder-declared-chain` or no chaining

**`apply-continuation` multimethod (line 195 in selection/core.cljs):**
- **Dispatch key:** `:continuation/type`
- **Registrations:**
  - `priority_flow.cljs`: `:resolve-one-and-stop` (line 116)
  - `casting.cljs`: `:cast-after-spell-mode` (line 213)
- **Dependency:** Continuations are stored on `:selection/on-complete` key; flow is: confirm-selection → drain-continuation-chain → apply-continuation → :then chain
- **Calling convention:** Return `{:app-db updated-app-db}` or `{:app-db updated-app-db :then next-continuation}`

### 2C. Re-Frame Event Handlers that Sequence Selection Operations

**Event handler dependency order (NOT explicit imports, order matters):**

1. `::cast-spell` (casting.cljs, line 308) — Sets `:game/pending-selection` and `:history/deferred-entry`
2. `::toggle-selection` (selection.cljs, line 27) — Mutates `:game/pending-selection`, may dispatch `::confirm-selection`
3. `::confirm-selection` (selection.cljs, line 34) — Consumes `:game/pending-selection`, sets `:game/db` and possibly new `:game/pending-selection`
4. `::resolve-top` (resolution.cljs, line 111) — May set `:game/pending-selection` (selection from effect resolution)

**Implicit sequencing (enforced by UI/user, not code):**
- User can't dispatch `::confirm-selection` without `:game/pending-selection` present (validation layer prevents silently succeeding)
- User can't dispatch `::resolve-top` while `:game/pending-selection` is present (UI guard in priority_flow.cljs:104-105)

---

## 3. Implicit Ordering: Module-Level Preconditions & Invariants

### 3A. Build-Selection-for-Effect Always Precedes Execute-Confirmed-Selection

**Structural guarantee (not explicit in code, guaranteed by usage):**
```
Effect triggers → build-selection-for-effect (creates selection state) →
User input → execute-confirmed-selection (processes selection) → 
next effect or cleanup
```

**Where this is enforced:**
1. **Pre-cast pipeline (casting.cljs:152-166):** Each step either skips, returns `{:selection sel}`, or `{:db final-db}`
   - Steps NEVER call execute-confirmed-selection; they build and return
   - Caller sets `:game/pending-selection` and waits for `::confirm-selection` event
   - Line 162: `(assoc :game/pending-selection (:selection result))`

2. **Resolution (resolution.cljs:37):** Effect resolution returns `{:needs-selection effect}`
   - build-selection-from-result calls build-selection-for-effect (line 37)
   - Result stored in `:game/pending-selection` (line 118)
   - confirm-selection-impl called later via event dispatch

3. **Ability activation (abilities.cljs:79-142):** Target selection → confirm → cost payment → stack-item creation
   - Multi-target chain calls build-ability-target-selection recursively (line 112)
   - Each target is built before confirm, not during confirm

**Why this matters:** If execute-confirmed-selection were called WITHOUT prior build, it would have no context (missing `:selection/type`, `:selection/lifecycle`, `:selection/spell-id`, etc.). The hierarchy dispatch on `:selection/type` would fail.

### 3B. Confirm-Selection-Impl Enforces Strict Ordering: Validate → Execute → Route → Apply-Continuation → Process-Deferred

**Line 408-442 sequence (IMMUTABLE):**

```
confirm-selection-impl (app-db)
  ├─ Validate selection (line 426)
  │  └─ [If invalid, return app-db unchanged — NO side effects]
  │
  ├─ Read :selection/on-complete BEFORE dissoc (line 428)
  │  └─ [on-complete is stored on selection, needed in next steps]
  │
  ├─ Call execute-confirmed-selection (line 430)
  │  └─ [Modifies game-db only]
  │
  ├─ Route based on :selection/lifecycle (line 431)
  │  ├─ :standard (line 348-376)
  │  │  ├─ Execute remaining-effects (line 356)
  │  │  ├─ [If interactive effect found, build next selection]
  │  │  └─ [Else cleanup source and potentially drain continuation]
  │  ├─ :finalized (line 379-390)
  │  │  ├─ Clear selection from app-db
  │  │  └─ [Potentially drain continuation]
  │  └─ :chaining (line 393-405)
  │     ├─ Call build-chain-selection (line 397)
  │     ├─ [If returns selection, set as new pending-selection]
  │     └─ [Else fall through to standard path]
  │
  ├─ [After routing, check if selection chain complete]
  │  └─ (nil? (:game/pending-selection routed)) (line 440)
  │
  └─ [If complete, process deferred entry UNCONDITIONALLY (line 440-441)]
     └─ process-deferred-entry (TERMINAL STEP)
        ├─ Read :history/deferred-entry (line 316)
        ├─ Describe action based on :type (line 318-322)
        ├─ Build :history/pending-entry (line 326)
        └─ Clear :history/deferred-entry (line 324)
```

**Critical invariants:**
1. **Validation gate (line 426):** No mutations if selection is invalid. Invalid selections are silently skipped.
2. **On-complete read timing (line 428):** Must read before dissoc'ing `:game/pending-selection` (line 373, 386)
3. **Routing is exclusive (line 432-436):** case statement ensures only ONE path executes
4. **Deferred entry terminal step (line 440):** process-deferred-entry ONLY runs when selection chain is COMPLETE (nil pending-selection)
   - If selection chain creates new pending-selection (chaining), deferred-entry stays on app-db
   - process-deferred-entry is idempotent on app-db without deferred-entry (line 316 guard)

### 3C. Continuation Draining Completes BEFORE History Processing

**Ordering (line 375, 388, 342-345):**

```
standard-path or finalized-path
  → [If on-complete present]
    → drain-continuation-chain (on-complete updated) (line 342)
      → loop: apply-continuation(cont, app-db) → result
         → [If :then in result]
           → recur(next-cont, result[:app-db])
         → [Return final app-db]
      → [Returns app-db AFTER all continuations applied]
    → [Back to confirm-selection-impl]
      → [Check if pending-selection nil]
      → [If nil, process-deferred-entry]
```

**Why ordering matters:**
- Continuations (`:resolve-one-and-stop`, `:cast-after-spell-mode`) may modify game-db
- History entry must describe FINAL game state, not intermediate state after selection but before continuation
- Continuations return `{:app-db updated}` or `{:app-db updated :then next-continuation}`
- The `:then` field creates implicit sequencing without re-frame events

### 3D. Selection Chain Completion Triggers Deferred Entry Processing

**The sequence (lines 316-331, 437-442):**

```
[Selection chain progresses]
  confirm-selection-impl (existing selection)
    → standard-path or finalized-path
      → [If remaining-effects produce new interactive effect]
        → build-selection-for-effect
        → app-db[:game/pending-selection] = next-sel
        → Return app-db with new pending-selection
    → [End of case routing, check (nil? (:game/pending-selection routed))]
      → [If true (chain complete)]
        → process-deferred-entry (line 440)
      → [If false (new selection created)]
        → Return without processing deferred-entry
```

**Historical contract (lines 265-270, 290-295):**
- `casting.cljs` sets `:history/deferred-entry` with `:cast-spell` type BEFORE selection
- `priority_flow.cljs` sets `:history/deferred-entry` with `:cast-and-yield` type BEFORE selection
- `selection/core.cljs:process-deferred-entry` reads `:type` and calls appropriate describer with correct game-db state

**Why deferred entry is needed:**
- `describe-cast-spell` needs final game-db state (after selection, after cost payment, after stack-item creation)
- `describe-cast-and-yield` needs BOTH pre-game-db (before cast) and final game-db (after resolve)
- If entry were created immediately, description would be incomplete/wrong
- Deferred entry stores pre-state; process-deferred-entry creates entry with final state

---

## 4. Data Flows Summary Table

| Module | Writes | Reads | Multimethod Registrations | Re-frame Events |
|--------|--------|-------|---------------------------|-----------------|
| **casting.cljs** | `:game/pending-selection`, `:history/deferred-entry`, `:game/db` | `:game/db`, `:game/selected-card`, `:card/modes`, `:card/targeting` | `execute-confirmed-selection :spell-mode`, `apply-continuation :cast-after-spell-mode` | `::cast-spell`, `::select-casting-mode` |
| **resolution.cljs** | `:game/pending-selection`, `:game/db` | `:game/db`, stack items | `build-selection-from-result` (not multimethod) | `::resolve-top`, `::resolve-all` |
| **selection/core.cljs** | `:game/pending-selection`, `:game/db`, `:history/pending-entry`, `:history/deferred-entry` | All selection fields, `:selection/lifecycle`, `:selection/on-complete` | `execute-confirmed-selection` (defines), `build-selection-for-effect` (defines), `build-chain-selection` (defines), `apply-continuation` (defines) | `::toggle-selection`, `::confirm-selection`, `::cancel-selection` |
| **selection/costs.cljs** | None (builders only) | Mode/effect data | `execute-confirmed-selection :x-mana-cost`, `:mana-allocation`, `:discard-specific-cost` | `::adjust-x-mana`, `::set-mana-allocation` |
| **selection/targeting.cljs** | None (builders only) | Game-db for valid targets | `execute-confirmed-selection :cast-time-targeting`, `:player-target` | None (pure function) |
| **selection/zone_ops.cljs** | None (builders only) | Game-db for zones | `execute-confirmed-selection :discard`, `:hand-reveal-discard` | None |
| **selection/library.cljs** | None (builders only) | Game-db library | `execute-confirmed-selection :tutor`, `:scry`, `:pile-choice` | `::select-random-pile-choice` |
| **selection/storm.cljs** | None (builders only) | Game-db for targets, stack items | `execute-confirmed-selection :storm-split` | `::adjust-storm-split`, `::reset-storm-split` |
| **selection/combat.cljs** | None (builders only) | Game-db for eligible | `execute-confirmed-selection :attacker-selection`, `:blocker-selection` | None |
| **abilities.cljs** | `:game/pending-selection` (via build_ability_target_selection) | `:card/abilities`, targeting info | None (builders only) | `::activate-mana-ability`, implied `::activate-ability` |
| **priority_flow.cljs** | `:history/deferred-entry`, `:game/db` | `:game/db`, `:game/pending-selection`, stops | `apply-continuation :resolve-one-and-stop` | `::yield`, `::yield-all`, `::cast-and-yield` |
| **cycling.cljs** | `:history/pending-entry`, `:game/db` | `:game/db`, card data | None | `::cycle-card` |

---

## 5. Architectural Insights: Complection & Design Asymmetries

### 5A. Three Separate "Modal" Concepts (Risk of Confusion)

1. **Modal spells (`:card/modes`, `:game/pending-mode-selection`)**
   - User selects casting mode BEFORE pre-cast pipeline
   - Separate from standard selection system
   - Uses `:game/pending-mode-selection` (not `:game/pending-selection`)
   - Handler: `select-casting-mode-handler` (casting.cljs:317)

2. **Modal selections (`:selection/mode` on cast-time-targeting)**
   - Part of pre-cast pipeline
   - User selects target mode DURING casting
   - Integrated into standard selection system
   - Part of `:game/pending-selection`

3. **Spell-mode selections (`:selection/type :spell-mode`)**
   - Selection system wrapper around modal spells
   - Bridges :game/pending-mode-selection into standard selection system
   - Uses continuation to trigger post-mode-selection pipeline
   - Lifecycle `:finalized`

**Complection:** `:game/pending-mode-selection` and `:game/pending-selection` are separate app-db slots, but conceptually similar. No shared handler; separate event dispatches.

### 5B. History Deferred Entry: Backward Signaling Anti-Pattern

**Problem:** Effects are discovered DURING selection execution (interactive effects), but history entries must describe the FINAL state AFTER selection completes. Standard forward-signaling (effect returns description) doesn't work.

**Solution (current):** Deferred entry stores pre-state; process-deferred-entry stores final state.

**Complection:** `:history/deferred-entry` and `:history/pending-entry` are both on app-db, created at different points, used by different modules (casting vs selection/core vs history/interceptor). No clear single owner.

**Risk:** If a code path sets `:history/deferred-entry` but selection chain never completes (e.g., validation error), entry is leaked. Mitigation: process-deferred-entry is idempotent (guard on line 316).

### 5C. Continuation Protocol: Implicit Chaining via :Then Field

**Design:** Instead of `(dispatch [:next-event ...])`, continuations use `{:app-db updated :then next-continuation}` to chain without re-frame events.

**Benefit:** Type-safe chaining (continuations are data, not event keywords); avoids re-frame event dispatch overhead.

**Risk:** drain-continuation-chain is a loop with no limit. If a continuation returns infinite `:then` chain, loop never exits. No guard against this.

**Mitigation needed:** Max-continuation-depth guard similar to max-director-steps (line 38 in director.cljs).

---

## 6. Verification Checklist for Future Changes

When adding a new interactive effect type or selection handler:

- [ ] **Multimethod registration:** Does executor register on `execute-confirmed-selection :new-type`?
- [ ] **Builder registration:** Does builder register on `build-selection-for-effect` (if effect type)? Or is it builder-declared-chain?
- [ ] **Lifecycle declaration:** Is `:selection/lifecycle` set correctly (`:standard`, `:finalized`, `:chaining`)?
- [ ] **Validation:** Does selection include `:selection/validation` field?
- [ ] **Auto-confirm:** Is `:selection/auto-confirm?` set appropriately?
- [ ] **Cleanup ownership:** If executor modifies zones/entities, is `:selection/source-type` handled correctly?
- [ ] **Chaining:** If lifecycle is `:chaining`, is `:selection/chain-builder` fn provided?
- [ ] **Continuation:** If on-complete continuation is needed, does apply-continuation have a defmethod?
- [ ] **History:** Is `:history/pending-entry` or `:history/deferred-entry` set correctly?
- [ ] **No side effects in builders:** Are builders pure functions (no dispatch, no mutation)?
