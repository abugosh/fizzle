---
name: Selection System Data Flows & Module Interactions
description: Module-level tracing of 5 entry points, implicit ordering, shared app-db communication, multimethod dependencies
type: reference
---

# Selection System Data Flows Investigation Complete

**Investigation date:** 2026-04-02
**Scope:** 5 entry points (cast-spell, confirm-selection, stack resolution, ability activation, toggle-selection), implicit module dependencies, ordering invariants, app-db communication channels

## Key Findings

### Entry Points Traced

1. **Cast-spell** (casting.cljs:241) → Pre-cast pipeline with step evaluation → selection system OR direct cast
2. **Confirm-selection** (selection.cljs:34) → Validation → execute-confirmed-selection → lifecycle routing (standard/finalized/chaining) → continuation draining → deferred-entry processing
3. **Stack resolution** (resolution.cljs:63) → engine-resolution → build-selection-from-result → selection/core:build-selection-for-effect
4. **Ability activation** (abilities.cljs) → build-ability-target-selection → chained targeting for multi-target → cost payment → stack-item
5. **Toggle-selection** (selection.cljs:27) → pure data-driven (no multimethods) → auto-confirm dispatch

### Implicit Module Dependencies (NOT in import graph)

**Multimethod registrations create runtime dependencies:**
- `execute-confirmed-selection`: 17+ defmethods across 8 modules (hierarchy-based dispatch on :selection/type)
- `build-selection-for-effect`: 5+ modules register builders via effect types
- `build-chain-selection`: Hierarchy + builder-declared-chain pattern
- `apply-continuation`: 2 registered continuations (:resolve-one-and-stop, :cast-after-spell-mode)

**Shared app-db keys as communication:**
- `:game/pending-selection` — central hub for all interactive selections (writers: casting, resolution, selection/core)
- `:history/deferred-entry` — backward signal from casting/priority_flow to selection/core (used for cast-and-yield semantics)
- `:history/pending-entry` — forward signal to history/interceptor (created by selection/core:process-deferred-entry)
- `:game/pending-mode-selection` — separate slot for modal spell UI (NOT integrated into standard selection system)

### Strict Ordering Invariants

**confirm-selection-impl enforces immutable sequence (lines 408-442):**
1. Validate selection (gate)
2. Read :selection/on-complete before dissoc
3. Call execute-confirmed-selection (game-db only)
4. Route based on :selection/lifecycle (exclusive case)
5. [If chaining] Call build-chain-selection → create next selection
6. [If not chaining] Apply remaining-effects OR finalize
7. Drain continuation chain via :then field
8. [Terminal] process-deferred-entry (ONLY when selection chain complete, nil pending-selection)

**Why ordering matters:**
- build-selection-for-effect always precedes execute-confirmed-selection (guaranteed by usage)
- continuation draining completes BEFORE history deferred-entry processing
- deferred-entry processing ONLY runs when entire selection chain is complete
- deferred-entry stores pre-state; describe-* is called with final state in process-deferred-entry

### Continuation Protocol (Implicit Chaining)

**Pattern:** `{:app-db updated-app-db :then next-continuation}` avoids re-frame events
- `drain-continuation-chain` loops until no `:then` (line 342-345)
- Continuations registered via multimethod on :continuation/type
- Two active continuations: :resolve-one-and-stop (priority_flow), :cast-after-spell-mode (casting)
- Risk: No max-depth guard (potential infinite loop if continuation returns itself)

### Design Asymmetries / Complection

1. **Three "modal" concepts** — modal spells, modal selections, spell-mode selections — use different app-db keys and separate event handlers
2. **Deferred entry backward signal** — `:history/deferred-entry` set before selection, processed after completion (inverse dependency flow)
3. **Lifecycle signaling** — Executors don't signal next action; builders declare lifecycle `:selection/lifecycle` field (data-driven)
4. **Two history keys** — deferred-entry + pending-entry created at different points, different owners

## Key Files

- **Core mechanism:** `src/main/fizzle/events/selection/core.cljs` (multimethods, confirm-selection-impl)
- **Entry points:** `src/main/fizzle/events/casting.cljs`, `selection.cljs`, `resolution.cljs`, `abilities.cljs`
- **Builders:** `selection/{costs,targeting,zone_ops,library,storm,combat}.cljs`
- **Continuations:** `priority_flow.cljs:116` (:resolve-one-and-stop), `casting.cljs:213` (:cast-after-spell-mode)
- **History integration:** `history/interceptor.cljs:15` reads :history/pending-entry

## Verification Reference

See **SELECTION_SYSTEM_DATA_FLOWS.md** section 6 for checklist when adding new selection types.
