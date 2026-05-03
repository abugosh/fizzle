# Hypergeometric Calculator Investigation — Document Index

**Investigation Date**: 2026-03-15
**Status**: COMPLETE — Ready to build
**Confidence**: High (all infrastructure verified)

---

## Quick Links

### For Quick Understanding
- **Start here**: [CALCULATOR_QUICK_START.md](CALCULATOR_QUICK_START.md) — 11 sections, action-oriented
- **Summary**: [Final Summary Table](#summary-table) (below)

### For Implementation
- **Code examples**: [CALCULATOR_CODE_EXAMPLES.md](CALCULATOR_CODE_EXAMPLES.md) — Copy-paste ready data shapes
- **Comprehensive report**: [HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md](HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md) — 8 sections, detailed

### For Future Reference
- **Memory (concise)**: `.claude/projects/.../memory/INVESTIGATION_SUMMARY.md`
- **Memory (detailed)**: `.claude/projects/.../memory/hypergeometric_calc_readiness.md`

---

## Summary Table

| Aspect | Finding | File Reference |
|--------|---------|-----------------|
| **Probability Code** | ✗ Does not exist | — |
| **Must Build** | Math module (~100 lines) | `CALCULATOR_CODE_EXAMPLES.md:7` |
| **Decklist Format** | `[{:card/id :kw :count N}...]` | `events/setup.cljs:39-74` |
| **Decklist Storage** | `:setup/main-deck` + `:setup/sideboard` | `subs/setup.cljs:37-52` |
| **Subscription Access** | `::setup-subs/current-main` | `CALCULATOR_CODE_EXAMPLES.md:2` |
| **Zone Queries** | `queries/get-objects-in-zone(...)` | `db/queries.cljs:90-101` |
| **UI Space Reserved** | `core.cljs:88-89` (comment) | `core.cljs:88-89` |
| **Hand Sculpting** | Must-contain system exists | `events/setup.cljs:207-233` |
| **Integration Paths** | 2 viable options | `INVESTIGATION.md:Section 7` |
| **Phase 1 Effort** | 10-14 hours | `QUICK_START.md:Section 7` |
| **Phase 2 Effort** | 7-10 hours (optional) | `QUICK_START.md:Section 7` |
| **Blockers** | NONE | `QUICK_START.md:Section 8` |

---

## Document Contents

### CALCULATOR_QUICK_START.md (11 sections)

1. **The Ask** — What you want to build
2. **What Exists** — Infrastructure table
3. **What to Build** — Phase 1 & 2, new files
4. **Integration Points** — Setup screen + game screen
5. **Data Shapes** — Copy-paste reference
6. **Testing Strategy** — Unit + integration tests
7. **Effort Estimate** — Breakdown by component
8. **Success Criteria** — Phase 1 & 2 checklist
9. **Execution Checklist** — Step-by-step
10. **References** — Links to full docs
11. **Why This Works** — Architecture rationale

**Use when**: Starting implementation, need checklist, unsure how to organize work

---

### CALCULATOR_CODE_EXAMPLES.md (11 sections)

1. **Deck Data: Setup Phase** — How decks are stored
2. **Card Definitions** — Name lookup + full card examples
3. **Game State: Library & Zones** — Zone subscriptions + queries
4. **Example: Build Calculator Input State** — Setup + game examples
5. **Example: Card Selector Input** — Dropdown pattern
6. **Module Skeleton** — Math functions needed
7. **Example: Complete Setup Calculator** — Full component skeleton
8. **Summary: Data Access Paths** — Reference table

**Use when**: Building code, need exact data shapes, unsure of API calls

---

### HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md (8 sections + summary)

1. **Existing Probability Code** — Grep results, status
2. **Decklist Data Structure** — Storage format, access points
3. **Current UI Structure** — Views directory layout, game layout
4. **Setup Event Flow** — Handlers, must-contain system
5. **Existing Stats/Analytics** — What already exists (zone counts, storm)
6. **Subscriptions for Deck/Zone Information** — Complete reference table
7. **Integration Points & Recommendations** — Option A, B, C (hybrid)
8. **Architecture Decisions** — Why this approach, performance, isolation
9. **Summary: Integration Readiness** — Status table

**Use when**: Need comprehensive understanding, want to verify all findings, making architectural decisions

---

## Key Findings at a Glance

### What Exists
- ✓ Clean decklist format: `[{:card/id :kw :count N}...]`
- ✓ Hand sculpting system: must-contain (max 7)
- ✓ Zone queries: library, graveyard, exile, hand
- ✓ UI space reserved: `core.cljs:88-89`
- ✓ Subscriptions for all needed data
- ✓ Collapsible UI patterns (graveyard, history)

### What's Missing
- ✗ No probability code
- ✗ No stats/analytics features
- ✗ Must implement hypergeometric from scratch

### Why It Works
- ✓ Data is immutable (safe to read)
- ✓ No events needed (read-only)
- ✓ UI layout allows insertion
- ✓ 60-card deck math is instant
- ✓ Architecture is isolated (no side effects)

---

## Implementation Paths

### Recommended: Phase 1 (Setup Calculator)

**What**: Pre-game calculator on setup screen
**Files**: 
- `src/main/fizzle/math/hypergeometric.cljs` (~100 lines)
- `src/main/fizzle/views/calculator.cljs` (~150 lines)
**Time**: 10-14 hours
**MVP**: Yes, can ship standalone

**Example**: "85% chance to open with 2+ Dark Rituals"

### Optional: Phase 2 (In-Game Calculator)

**What**: Real-time library odds during game
**Files**: 
- Extend calculator.cljs (~100 more lines)
- Optional: new subscription in subs/game.cljs (~20 lines)
**Time**: 7-10 hours additional
**Depends on**: Phase 1 complete

**Example**: "92% chance to draw Ritual by turn 4"

---

## Next Steps

### To Understand the Feature
1. Read [CALCULATOR_QUICK_START.md](CALCULATOR_QUICK_START.md) (15 min)
2. Skim [CALCULATOR_CODE_EXAMPLES.md](CALCULATOR_CODE_EXAMPLES.md) sections 1-2 (10 min)

### To Start Building
1. Read [CALCULATOR_CODE_EXAMPLES.md](CALCULATOR_CODE_EXAMPLES.md) sections 2-5 (data shapes)
2. Copy skeleton from section 6-7
3. Implement math module (test-driven)
4. Implement view component
5. Integrate with `views/setup.cljs`

### For Deep Dives
- Full investigation: [HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md](HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md)
- Architecture rationale: [HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md](HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md) Section 8

---

## File Locations (Reference)

### Source Code (Relevant to Calculator)

```
src/main/fizzle/
├── events/
│   └── setup.cljs                 — Decklist storage, must-contain
├── subs/
│   ├── setup.cljs                 — Deck subscriptions
│   └── game.cljs                  — Zone subscriptions
├── db/
│   └── queries.cljs               — Zone queries
├── views/
│   ├── setup.cljs                 — Where to integrate Phase 1
│   └── [TBD: calculator.cljs]     — New component
├── math/
│   └── [TBD: hypergeometric.cljs] — New math module
└── core.cljs                       — Reserved space line 88-89
```

### Investigation Documents (Project Root)

```
/Users/abugosh/g/fizzle/
├── CALCULATOR_QUICK_START.md              — Action-oriented guide
├── CALCULATOR_CODE_EXAMPLES.md            — Code + data shapes
├── HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md  — Comprehensive
└── INVESTIGATION_INDEX.md                 — This file
```

### Memory (For Future Sessions)

```
.claude/projects/.../memory/
├── INVESTIGATION_SUMMARY.md               — Quick reference
├── hypergeometric_calc_readiness.md       — Detailed readiness
└── MEMORY.md                              — Index (updated)
```

---

## Quick Reference: Data Shapes

**Deck entry**:
```clojure
{:card/id :dark-ritual :count 4}
```

**Setup access**:
```clojure
@(rf/subscribe [::setup-subs/current-main])
```

**Game zone counts**:
```clojure
{:graveyard 7 :library 51 :exile 0}
```

**Library objects**:
```clojure
(queries/get-objects-in-zone @game-db player-id :library)
```

**Hand sculpting**:
```clojure
{:dark-ritual 1 :brain-freeze 2}
```

For full examples, see [CALCULATOR_CODE_EXAMPLES.md](CALCULATOR_CODE_EXAMPLES.md) Section 5.

---

## Status

✓ Investigation complete
✓ All infrastructure verified
✓ No blockers identified
✓ Ready to build Phase 1

**Estimated effort**: Phase 1 in 1-2 sprints (10-14 hours)

**Confidence**: High (all findings backed by code references)

---

Generated: 2026-03-15 | Investigation Scope: Comprehensive | Ready to Build: YES
