# Game.cljs Decomposition Investigation — Document Index

**Investigation date**: 2026-03-23
**Scope**: Complete structural analysis of `events/game.cljs` (903 lines)
**Decision basis**: ADR-016 (Accepted)
**Status**: ✅ Ready for implementation

---

## Quick Navigation

| Document | Purpose | Audience | Read Time |
|----------|---------|----------|-----------|
| **INVESTIGATION_SUMMARY.md** | Executive summary of findings | Everyone | 10 min |
| **DECOMPOSITION_QUICK_START.md** | Step-by-step implementation guide | Implementer | 30 min prep + 7h work |
| **GAME_CLJS_DECOMPOSITION_ANALYSIS.md** | Comprehensive structural reference | Reviewer/Deep dive | 20 min |
| **CONCERN_DEPENDENCIES.md** | Call graph and dependency verification | Implementer (during work) | 15 min |

---

## What You Need Based on Your Role

### "I want to understand what needs to be done" ← START HERE
1. Read **INVESTIGATION_SUMMARY.md** (10 min)
   - Gives you the executive summary
   - Shows key metrics and risk analysis
   - Explains the extraction pattern

### "I'm going to implement the decomposition" ← START HERE
1. Read **DECOMPOSITION_QUICK_START.md** (30 min prep)
   - Understand the 4 phases
   - Know line ranges to copy
   - Have template structures
2. Reference **CONCERN_DEPENDENCIES.md** (as needed during work)
   - Double-check which functions move together
   - Verify no calls were missed
   - Understand inter-module dependencies

### "I'm reviewing the investigation" ← START HERE
1. Read **INVESTIGATION_SUMMARY.md** (10 min)
   - Understand scope and findings
2. Skim **GAME_CLJS_DECOMPOSITION_ANALYSIS.md** (10 min)
   - Verify structural breakdown is correct
   - Check dependency analysis
3. Spot-check **CONCERN_DEPENDENCIES.md** (5 min)
   - Ensure call graph is accurate

### "I want to understand the full architecture" ← START HERE
1. Read **INVESTIGATION_SUMMARY.md** (10 min)
2. Read **GAME_CLJS_DECOMPOSITION_ANALYSIS.md** (20 min)
3. Study **CONCERN_DEPENDENCIES.md** (15 min)
4. Understand how this relates to existing modules:
   - `src/main/fizzle/events/phases.cljs`
   - `src/main/fizzle/events/cleanup.cljs`
   - `src/main/fizzle/events/lands.cljs`

---

## Document Summaries

### INVESTIGATION_SUMMARY.md
**The shortest path to understanding what was found and why.**

Key sections:
- What was investigated (scope)
- Key findings (4 sections: composition, dependencies, pattern, metrics)
- Reference documents overview
- Verification checklist
- Risk analysis
- ADR-016 context
- Next steps

**Use this for**: Briefing someone on the investigation results.

### DECOMPOSITION_QUICK_START.md
**The tactical guide for someone who will actually do the extraction.**

Key sections:
- Phase 1-4 step-by-step instructions
- Exact line ranges from game.cljs to copy
- New file templates
- Verification steps (make test)
- Commit message templates
- Checklist and common pitfalls

**Use this for**: Implementing the decomposition.

### GAME_CLJS_DECOMPOSITION_ANALYSIS.md
**The comprehensive reference for understanding the architecture.**

Key sections:
1. Current structure (903 lines, 4 regions)
2. Casting concern detail (~250 lines)
3. Resolution concern detail (~130 lines)
4. Priority flow concern detail (~420 lines)
5. Extraction pattern (precedent from phases/cleanup/lands)
6. Dependency graph visualization
7. Extraction order justification
8. Multimethod continuations mapping
9. What requires game.cljs (impact analysis)
10. Re-export facade template
11. Key decisions
12. Summary table

**Use this for**: Deep understanding of the structure and complete reference during planning.

### CONCERN_DEPENDENCIES.md
**The call graph reference for verification.**

Key sections:
- Casting concern call hierarchy
- Resolution concern call hierarchy
- Priority flow concern call hierarchy
- Current game.cljs call graph
- Extraction order verification
- Test coverage points

**Use this for**: Verifying that all related functions move together, and checking for missed dependencies.

---

## The Investigation Flow

Here's how the investigation was conducted (for transparency):

1. **Read game.cljs structure** (lines 1-100, then 300-900)
   - Identified major sections
   - Located re-exports and event handlers
   - Found def/defn/defmulti signatures

2. **Found ADR-016** (/doc/arch/adr-016.md)
   - Confirmed decision was already made
   - Used ADR as ground truth for scope

3. **Traced dependencies**
   - Grep for function calls within game.cljs
   - Identified which functions call which
   - Mapped external requires

4. **Analyzed extraction pattern**
   - Read phases.cljs (120 lines)
   - Read cleanup.cljs (90 lines)
   - Read lands.cljs (50 lines)
   - Confirmed pattern: extract → import → re-export

5. **Verified no call crosstalk**
   - Grep for each concern's functions called by other concerns
   - Confirmed casting has zero upward calls
   - Confirmed resolution only called by priority
   - Confirmed priority calls casting/resolution/phases

6. **Generated reference documents**
   - INVESTIGATION_SUMMARY.md (findings summary)
   - GAME_CLJS_DECOMPOSITION_ANALYSIS.md (comprehensive reference)
   - CONCERN_DEPENDENCIES.md (call graph verification)
   - DECOMPOSITION_QUICK_START.md (implementation guide)

---

## Key Takeaways

1. **The decomposition is straightforward**: Clear seams, proven pattern, no circular dependencies.

2. **Three independent modules will be created**:
   - `events/casting.cljs` (~250 lines) — spell casting pipeline
   - `events/resolution.cljs` (~130 lines) — stack resolution
   - `events/priority_flow.cljs` (~420 lines) — priority passing and phase advancement

3. **Extraction order is critical**: Casting → Resolution → Priority Flow (follows dependency order).

4. **Backward compatibility is guaranteed**: Re-export facade in game.cljs preserves all public API.

5. **Estimated effort**: 7 hours total (2h + 1.5h + 2.5h + 0.5h for phases 1-4).

6. **Risk is low**: Clear dependencies, proven pattern, comprehensive verification.

---

## File Locations

All investigation outputs are in the repository root:

```
/Users/abugosh/g/fizzle/
├── INVESTIGATION_SUMMARY.md                    ← Executive summary
├── DECOMPOSITION_QUICK_START.md                ← Implementation guide
├── GAME_CLJS_DECOMPOSITION_ANALYSIS.md         ← Comprehensive reference
├── CONCERN_DEPENDENCIES.md                     ← Call graph verification
├── DECOMPOSITION_DOCUMENTS_INDEX.md            ← This file
│
└── src/main/fizzle/events/
    ├── game.cljs                               ← File being decomposed (903 lines)
    ├── phases.cljs                             ← Pattern reference
    ├── cleanup.cljs                            ← Pattern reference
    └── lands.cljs                              ← Pattern reference

└── doc/arch/
    └── adr-016.md                              ← Decision document
```

---

## Next Steps

1. **Review**: Share INVESTIGATION_SUMMARY.md with team for consensus
2. **Prepare**: Read DECOMPOSITION_QUICK_START.md before starting
3. **Implement**: Follow phases 1-4 with reference documents
4. **Validate**: Run `make validate` after each phase
5. **Close**: Verify implementation matches ADR-016 decision

