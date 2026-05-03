# Hypergeometric Calculator — Quick Start Guide

**Status**: Ready to build. Full investigation complete.

---

## 1. The Ask

Build a hypergeometric probability calculator for Fizzle to show odds of drawing specific cards.

**Example use cases**:
- Pre-game: "What's my probability of opening with 2+ Dark Rituals?"
- In-game: "What's my probability of drawing a Ritual by turn 4?"

---

## 2. What Exists

| Component | Status | Location |
|-----------|--------|----------|
| Decklist format | ✓ | `:setup/main-deck` = `[{:card/id :kw :count N}...]` |
| Hand sculpting | ✓ | `:setup/must-contain` = `{:card-id -> count}` |
| Zone queries | ✓ | `queries/get-objects-in-zone(db, player-id, :library)` |
| UI space | ✓ | `core.cljs:88-89` "reserved for calculator panel" |
| **Probability code** | **✗** | **Doesn't exist — must build** |

---

## 3. What to Build

### Phase 1: Math Module (New File)

**Path**: `src/main/fizzle/math/hypergeometric.cljs`

```clojure
(ns fizzle.math.hypergeometric)

;; Core functions needed:
(defn combination [n k])              ; C(n, k)
(defn probability-exactly [total in-deck drawn exactly])  ; P(X = k)
(defn probability-at-least [total in-deck drawn min-count])  ; P(X >= k)
(defn probability-at-most [total in-deck drawn max-count])   ; P(X <= k)
(defn format-percentage [decimal])    ; "45.3%"
```

**Logic**: Hypergeometric distribution formula
- P(X = k) = [C(K, k) × C(N-K, n-k)] / C(N, n)
- Where: N=deck size, K=copies in deck, n=drawn, k=want

**Implementation notes**:
- Precompute factorials 0-60 (instant O(1) lookups)
- Cache C(n,k) results (avoid recomputation)
- Handle edge cases (k > K, n > N, etc.)

### Phase 2: View Component (New File)

**Path**: `src/main/fizzle/views/calculator.cljs`

```clojure
(ns fizzle.views.calculator
  (:require
    [fizzle.math.hypergeometric :as hg]
    [fizzle.subs.setup :as setup-subs]
    [re-frame.core :as rf]))

(defn setup-calculator []
  ;; Inputs:
  ;; - Card selector (dropdown from deck)
  ;; - "How many cards?" (1-10)
  ;; - "At least?" (1-4 copies)
  ;;
  ;; Output:
  ;; - Probability percentage
  ;; - Breakdown table (P(exactly 0), P(exactly 1), etc.)
  )
```

**For setup phase**:
- Use: `@(rf/subscribe [::setup-subs/current-main])`
- Static calculation (no game state needed)

**For game phase** (optional Phase 2):
- Use: `@(rf/subscribe [::subs/game-db])`
- Query: `queries/get-objects-in-zone(db, player-id, :library)`
- Accounts for: known cards (hand + graveyard already drawn)

---

## 4. Integration Points

### Setup Screen (Recommended First)

**File**: `src/main/fizzle/views/setup.cljs`

Add a tab or panel:
```clojure
[:div {:class "..."}
 [:h3 "Probability Calculator"]
 [setup-calculator]]
```

Access deck data:
```clojure
@(rf/subscribe [::setup-subs/current-main])  ;; Deck entries
@(rf/subscribe [::setup-subs/must-contain])  ;; Hand sculpting
```

### Game Screen (Optional Phase 2)

**File**: `src/main/fizzle/core.cljs:88-89` (reserved space)

Replace the comment with:
```clojure
[:div {:class "col-span-full"}
 [game-calculator]]  ;; New component for in-game odds
```

Access game state:
```clojure
@(rf/subscribe [::subs/player-zone-counts])  ;; Library count
@(rf/subscribe [::subs/game-db])              ;; Full state for queries
```

---

## 5. Data Shapes (Copy-Paste Reference)

### Deck Entry
```clojure
{:card/id :dark-ritual, :count 4}
```

### Setup Deck Access
```clojure
@(rf/subscribe [::setup-subs/current-main])
=> [{:card/id :dark-ritual :count 4}
    {:card/id :island :count 2}
    ;; ... 58+ more entries totaling 60
    ]
```

### Zone Counts (Game)
```clojure
@(rf/subscribe [::subs/player-zone-counts])
=> {:graveyard 7, :library 51, :exile 0, :threshold? true}
```

### Library Objects (Game)
```clojure
(queries/get-objects-in-zone @game-db :player-1 :library)
=> [{:object/id "uuid-123"
     :object/zone :library
     :object/card {:card/id :dark-ritual :card/name "Dark Ritual" ...}}
    ;; ... 50 more
    ]
```

### Must-Contain (Hand Sculpting)
```clojure
@(rf/subscribe [::setup-subs/must-contain])
=> {:dark-ritual 1, :brain-freeze 2}
;; "I must have at least 1 Ritual + 2 Freezes in opening hand"
```

---

## 6. Testing Strategy

### Unit Tests (hypergeometric.cljs)
```clojure
;; Test known values
(test "combination" (is (= (combination 60 7) expected-value)))

;; Test hypergeometric known cases
;; E.g., 52-card deck, 4 aces, draw 5: P(2 aces) = 0.2995...
(test "probability-exactly" (is (≈ (prob 52 4 5 2) 0.2995 0.001)))

;; Test probabilities sum to 1
(test "all probabilities sum" (is (≈ (sum-all-pdfs) 1.0)))
```

### Integration Tests (calculator.cljs)
```clojure
;; Test setup calculator renders
;; Test probability updates when card changes
;; Test must-contain affects conditional probability
;; Test in-game calculator accounts for drawn cards
```

---

## 7. Effort Estimate

| Phase | Component | Hours | Notes |
|-------|-----------|-------|-------|
| 1 | Math module | 6-8 | Implement + unit tests |
| 1 | Setup view | 4-6 | Component + styling |
| **1 Total** | | **10-14** | Can ship as MVP |
| 2 | Game subscription | 1-2 | Query library objects |
| 2 | Game view | 6-8 | Live updates + layout |
| **2 Total** | | **7-10** | Adds real-time feature |
| **Grand Total** | | **~20** | Full feature (both phases) |

---

## 8. Success Criteria

### Phase 1 (Setup)
- [ ] Math module: All functions implemented, unit tested
- [ ] Calculator component: Renders on setup screen
- [ ] User can select card + inputs + see probability
- [ ] Probability updates live as inputs change
- [ ] Breakdown table shows P(exactly 0), P(exactly 1), etc.

### Phase 2 (In-Game)
- [ ] New subscription for library state
- [ ] Game calculator component: Accounts for drawn/graveyard cards
- [ ] Collapsible panel in core.cljs reserved space
- [ ] Probability updates as game progresses

---

## 9. Execution Checklist

### Setup

- [ ] Create `src/main/fizzle/math/hypergeometric.cljs`
  - [ ] Precomputed factorials 0-60
  - [ ] `combination(n, k)` with caching
  - [ ] `probability-exactly/at-least/at-most`
  - [ ] `format-percentage(decimal)`
  - [ ] Unit tests (test/fizzle/math/hypergeometric_test.cljs)

- [ ] Create `src/main/fizzle/views/calculator.cljs`
  - [ ] `setup-calculator` component
  - [ ] Card selector dropdown
  - [ ] Number inputs
  - [ ] Probability display
  - [ ] Breakdown table
  - [ ] Integration tests

- [ ] Update `src/main/fizzle/views/setup.cljs`
  - [ ] Add calculator panel/tab
  - [ ] Wire up subscriptions

### Game (Optional Phase 2)

- [ ] Add subscription to `src/main/fizzle/subs/game.cljs`
  - [ ] `::library-state-snapshot`

- [ ] Create game calculator view
  - [ ] Query library objects
  - [ ] Account for drawn cards
  - [ ] Collapsible layout

- [ ] Update `src/main/fizzle/core.cljs`
  - [ ] Replace reserved comment (line 88-89)

---

## 10. References

**Full documentation**:
- `HYPERGEOMETRIC_CALCULATOR_INVESTIGATION.md` — Comprehensive report
- `CALCULATOR_CODE_EXAMPLES.md` — Exact code + data shapes
- Memory: `hypergeometric_calc_readiness.md` — Quick reference

**Relevant source files**:
- Subscriptions: `src/main/fizzle/subs/setup.cljs:37-52` (deck access)
- Queries: `src/main/fizzle/db/queries.cljs:90-101` (zone objects)
- Setup view: `src/main/fizzle/views/setup.cljs` (where to integrate)
- Game layout: `src/main/fizzle/core.cljs:69-93` (where reserved panel is)

---

## 11. Why This Works

✓ Data is immutable (safe to read without side effects)
✓ Subscriptions are clean (no new events needed)
✓ UI space is reserved (no layout changes)
✓ Architecture is isolated (pure math, read-only)
✓ Performance is trivial (60-card deck is instant)

---

## Start Here

1. Read `CALCULATOR_CODE_EXAMPLES.md` (copy-paste data shapes)
2. Implement math module first (test-driven)
3. Implement setup calculator view
4. Test with Iggy Pop deck
5. (Optional) Add in-game calculator later

**Ready to build?** Start with `src/main/fizzle/math/hypergeometric.cljs`.
