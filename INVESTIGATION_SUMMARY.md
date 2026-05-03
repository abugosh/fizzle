# game.cljs Decomposition Investigation Summary

**Status**: ✅ Investigation complete. Ready for implementation per ADR-016.

**Date**: 2026-03-23

---

## What Was Investigated

Detailed structural analysis of `/Users/abugosh/g/fizzle/src/main/fizzle/events/game.cljs` (903 lines) to support the planned decomposition into three focused sub-modules per ADR-016.

---

## Key Findings

### 1. Structural Composition (903 lines)

The file cleanly separates into four regions:

| Region | Lines | Content |
|--------|-------|---------|
| **Re-exports** | 1-47 | Backward-compatibility facade for previously extracted modules (init, phases, cleanup, lands, ui) |
| **CASTING** | 49-296 + 825-855 | Spell casting pipeline (~250 lines): `:cast-spell`, `:select-casting-mode`, `:cancel-mode-selection` events; pre-cast pipeline with cost/targeting/mana selection |
| **RESOLUTION** | 304-430 | Stack resolution (~130 lines): `:resolve-top`, `:resolve-all` events; resolve-one-item function handling all interactive effect types |
| **PRIORITY FLOW** | 433-820 | Priority system (~420 lines): `:yield`, `:yield-all`, `:cast-and-yield` events; negotiate-priority, advance-with-stops, yield-impl functions |
| **UTILITY** | 858-903 | Cycling mechanic (~45 lines): `:cycle-card` event (low-churn, unrelated to three concerns) |

### 2. Dependency Analysis

**Casting Concern:**
- Zero dependencies on resolution or priority
- Standalone pipeline: modalspell → cost selection → targeting → mana allocation → cast
- Can be extracted first without risk

**Resolution Concern:**
- Only called by priority flow (`yield-resolve-stack` at line 491)
- No calls to casting or priority
- Can be extracted second

**Priority Flow Concern:**
- Orchestrates casting + resolution + phases + cleanup
- Calls `cast-spell-handler` (line 792 in `::cast-and-yield`)
- Calls `resolve-one-item` (line 491 in `yield-resolve-stack`)
- Must be extracted last

**game.cljs Post-extraction:**
- Becomes pure re-export facade (~140 lines)
- No logic, no state changes
- Preserves all existing require paths for backward compatibility

### 3. Extraction Pattern

Follows the precedent established by three already-extracted modules:
- **phases.cljs** (~100 lines) — Turn structure, phase advancement
- **cleanup.cljs** (~80 lines) — Cleanup step logic
- **lands.cljs** (~50 lines) — Land playing mechanics

**Pattern:**
1. Create new module with full namespace and imports
2. Move functions, defmultis, event handlers
3. Re-export via game.cljs facade
4. No downstream code changes needed (all event keywords preserved)

### 4. Key Metrics

| Metric | Value |
|--------|-------|
| Current game.cljs size | 903 lines |
| Total lines to extract | 763 lines (84%) |
| Post-extraction facade size | ~140 lines |
| Extraction order | Casting → Resolution → Priority Flow |
| Re-exported functions | 13 public functions + 1 utility |
| Event handlers | 8 total (:cast-spell, :select-casting-mode, :cancel-mode-selection, :resolve-top, :resolve-all, :yield, :yield-all, :cast-and-yield, :cast-and-yield-resolve, :cycle-card) |
| Multimethod registrations | 3 total (:spell-mode, :cast-after-spell-mode, :resolve-one-and-stop) |

### 5. Implementation Complexity

**Casting extraction**: 2 hours
- Copy ~250 lines of code
- 8 defmethod implementations + multimethod def
- 4 public handler functions
- 3 event registrations

**Resolution extraction**: 1.5 hours
- Copy ~130 lines of code
- 4 functions (1 public, 3 private)
- 2 event registrations

**Priority flow extraction**: 2.5 hours
- Copy ~420 lines of code
- 3 public functions, 6 private functions
- 4 event registrations
- 1 multimethod continuation

**Finalization**: 0.5 hours
- Clean up game.cljs facade
- Run full validation (lint + format + tests)

**Total: ~7 hours**

---

## Reference Documents Created

### 1. GAME_CLJS_DECOMPOSITION_ANALYSIS.md (comprehensive)
**Purpose**: Complete structural analysis for verification and review.

**Contents**:
- Current structure breakdown (lines, functions, dependencies)
- Detailed function inventory for each concern
- Dependency graph visualization
- Multimethod continuations mapping
- Complete extraction pattern specification
- What requires game.cljs analysis

**When to use**: Before starting implementation, to understand full scope and dependencies.

### 2. DECOMPOSITION_QUICK_START.md (tactical)
**Purpose**: Step-by-step implementation guide with copy-paste line ranges.

**Contents**:
- Phase 1-4 extraction instructions
- Exact line ranges to copy from game.cljs
- New file templates
- Import updates for each phase
- Verification steps (make test)
- Commit message templates
- Common pitfalls checklist

**When to use**: During implementation, as the main reference guide.

### 3. CONCERN_DEPENDENCIES.md (visual reference)
**Purpose**: Call graph and dependency tree for verification.

**Contents**:
- Detailed call hierarchy for each concern
- External calls mapping
- Which functions call which
- Extraction order verification
- Test coverage points

**When to use**: To understand exactly which functions need to move together, and to verify no calls were missed.

---

## Verification Checklist

- ✅ All three concerns have zero cross-dependencies within their own module
- ✅ Resolution only called by priority (single entry point)
- ✅ Casting only used by priority and directly dispatched
- ✅ Priority flow correctly orchestrates casting + resolution + phases
- ✅ Extraction order is correct (Casting → Resolution → Priority Flow)
- ✅ Re-export pattern preserves backward compatibility
- ✅ No tests require changes (events dispatched by keyword, not import)
- ✅ All multimethod registrations accounted for
- ✅ Utility function (cycle-card) identified for facade

---

## Risk Analysis

**Low risk factors:**
- Clear seams between concerns (no tangled dependencies)
- Extraction pattern already proven (phases, cleanup, lands)
- Re-export facade fully preserves public API
- Event keywords remain unchanged (subscribers unaffected)
- Multimethod registration side-effects handled by existing pattern

**Potential pitfalls:**
1. **Forgetting re-exports** → Test via `fizzle.events.game/function-name`
2. **Circular imports** → Verify dependency order: casting → resolution → priority
3. **Event handler namespace changes** → Event keywords become `:fizzle.events.casting/::cast-spell` instead of `:fizzle.events.game/::cast-spell` (but subscription code uses `:game/::cast-spell`, so no change visible)
4. **Multimethod defmethod load order** → All modules must be required before events fire; current pattern handles this

**Mitigation:**
- Run `make validate` after each phase
- Spot-check re-exports with simple REPL test: `(require '[fizzle.events.game :as game]) (game/resolve-one-item ...)`
- Verify event dispatch in UI/tests still works

---

## ADR-016 Context

**Decision**: Extract three concerns from game.cljs into focused sub-modules.

**Motivation**: game.cljs is 903 lines with 37% churn over 6 months (highest in codebase). Three independent concerns are braided together.

**Expected outcomes**:
- Reduced file churn (changes to casting logic don't touch resolution/priority)
- Clearer dependencies (each module has one job)
- Better test organization (test files can target specific concerns)
- Extensibility (new game event concerns add a new module, not more game.cljs bulk)

**Consequences**:
- More files under events/ (6 sub-modules + facade)
- Slightly more boilerplate per new module (namespace, imports, re-exports)
- Clearer architecture, lower coupling

---

## Next Steps

1. **Review**: Share reference documents with team
2. **Validate**: Ensure consensus on extraction order and re-export pattern
3. **Implement**: Follow DECOMPOSITION_QUICK_START.md phases 1-4
4. **Test**: `make validate` after each phase
5. **Commit**: One commit per phase (4 commits total)
6. **Close ADR**: Verify implementation matches decision

---

## Files Generated

All investigation outputs are in the repo root:

1. `/Users/abugosh/g/fizzle/GAME_CLJS_DECOMPOSITION_ANALYSIS.md` — Comprehensive analysis
2. `/Users/abugosh/g/fizzle/DECOMPOSITION_QUICK_START.md` — Implementation guide
3. `/Users/abugosh/g/fizzle/CONCERN_DEPENDENCIES.md` — Call graph reference
4. `/Users/abugosh/g/fizzle/INVESTIGATION_SUMMARY.md` — This summary

All documents are read-only reference material (not part of codebase build/test).

