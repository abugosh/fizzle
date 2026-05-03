# Selection Modal System Investigation

**Investigation Date:** 2026-02-23
**Scope:** View-layer selection modal system, focusing on render-selection-modal multimethod, ::selection-cards subscription, valid-targets filtering, and recently added selection types.

## Summary Findings

### 1. render-selection-modal Multimethod Location & Defmethods

**File:** `/Users/abugosh/g/fizzle/src/main/fizzle/views/modals.cljs` (lines 823-896)

**Dispatch Function:** `modal-dispatch-key` (lines 810-820)
- Special case: Targeting types (`:cast-time-targeting`, `:ability-targeting`) that target a player dispatch as `:targeting-player` to share single `player-target-modal` component
- Otherwise dispatches on raw `:selection/type` value

**Registered Defmethods (15 total):**
1. `:scry` → `scry-modal` (line 829)
2. `:pile-choice` → `pile-choice-modal` (line 833)
3. `:targeting-player` → `player-target-modal` (line 837) [SHARED for player-targeted targeting]
4. `:cast-time-targeting` → `object-target-modal` (line 841)
5. `:ability-targeting` → `object-target-modal` (line 850)
6. `:player-target` → `player-target-modal` (line 859)
7. `:graveyard-return` → `graveyard-selection-modal` (line 863)
8. `:exile-cards-cost` → `exile-cards-selection-modal` (line 867)
9. `:x-mana-cost` → `x-mana-selection-modal` (line 871)
10. `:peek-and-select` → `peek-selection-modal` (line 875)
11. `:order-bottom` → `order-bottom-modal` (line 879)
12. `:storm-split` → `storm-split-modal` (line 883)
13. `:hand-reveal-discard` → `hand-reveal-discard-modal` (line 887) [Added for Duress]
14. `:mana-allocation` → `nil` (line 891) [Renders nothing]
15. `:default` → `card-selection-modal` (line 895) [Fallback for `:discard`, `:tutor`, etc.]

### 2. ::selection-cards Subscription Location & Handlers

**File:** `/Users/abugosh/g/fizzle/src/main/fizzle/subs/game.cljs` (lines 379-422)

**Subscription registers:** `::selection-cards`

**Card-Source Values Handled (6 cases):**
1. `:candidates` - Maps `:selection/candidates` IDs to card objects
   - Used by: `:pile-choice`, `:exile-cards-cost`, `:peek-and-select`

2. `:valid-targets` - Maps `:selection/valid-targets` IDs to card objects
   - Used by: `:cast-time-targeting`, `:ability-targeting`, `:chain-bounce`, `:chain-bounce-target`

3. `:library` - Intersects `:selection/candidates` with player's library
   - Used by: `:tutor`

4. `:hand` - Queries player's hand directly
   - Used by: `:discard`

5. `:opponent-hand` - Queries target player's hand via `:selection/target-player`
   - Used by: `:hand-reveal-discard` (Duress)

6. `:zone` - Queries any zone from `:selection/zone`
   - Used by: `:graveyard-return` and others

7. **Fallback** (no `:card-source`) - Queries `:selection/zone` (default `:hand`)

### 3. valid-targets Filtering Mechanism

**Flow:**
1. **Targeting module:** `/Users/abugosh/g/fizzle/src/main/fizzle/engine/targeting.cljs`
   - `find-valid-targets(db, player-id, target-requirement)` - finds all valid object/player IDs
   - Filters using `queries/matches-criteria?` based on `target-requirement`

2. **Queries module:** `/Users/abugosh/g/fizzle/src/main/fizzle/db/queries.cljs` (lines 247-275)
   - `matches-criteria?(obj, criteria)` - Checks if object's card matches filtering criteria
   - Criteria map supports:
     - `:match/types` - Set of types (OR logic within category)
     - `:match/not-types` - Exclusion filter (object must NOT have any listed types)
     - `:match/colors` - Set of colors (OR logic)
     - `:match/subtypes` - Set of subtypes (OR logic)
   - **Logic:** AND between categories, OR within categories

3. **Selection builder example (hand-reveal):** `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/zone_ops.cljs` (lines 64-88)
   - `build-hand-reveal-discard-selection` filters hand cards via `queries/matches-criteria?`
   - Stores selectable IDs in `:selection/valid-targets`
   - View-layer filters displayed cards using this set

4. **View rendering:** `/Users/abugosh/g/fizzle/src/main/fizzle/views/modals.cljs` (lines 252-306)
   - `hand-reveal-card-view` checks `(contains? valid-targets (:object/id obj))`
   - Selectable cards render normally; non-matching cards are dimmed/inert

### 4. Selection Types in Codebase (16 total)

**Engine-Layer Types** (event builders):
- `:discard` - Select exact count from hand
- `:hand-reveal-discard` - NEW (Duress): show full opponent hand, select noncreature/nonland
- `:return-from-graveyard` - Select 0-N from graveyard
- `:player-target` - Choose which player to target
- `:cast-time-targeting` - Target before casting (object or player)
- `:ability-targeting` - Target for ability (object or player)
- `:chain-bounce` - NEW (Chain of Vapor): controller's lands, exact-or-zero for chain mechanic
- `:chain-bounce-target` - NEW (Chain of Vapor): new target for copied spell
- `:tutor` - Search library
- `:scry` - Order top N cards (top/bottom piles)
- `:pile-choice` - Choose cards for hand (Intuition-style)
- `:peek-and-select` - Look at N cards, select up to M
- `:order-bottom` - Order cards going to library bottom
- `:x-mana-cost` - Select X value for variable mana cost
- `:exile-cards-cost` - Select cards to exile for flashback costs
- `:mana-allocation` - Allocate mana (renders as nil, no modal)

## Critical Gap: Missing View Support

### Problem: Two Selection Types Ship Without View Defmethods

**1. `:chain-bounce` & `:chain-bounce-target`**
- **Cards:** Chain of Vapor (Instant, bounce + chain mechanic)
- **When triggered:** After bouncing nonland permanent, controller chooses whether to sacrifice a land
- **Builder file:** `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/zone_ops.cljs` (lines 157-186)
- **Missing defmethod:** `(defmethod render-selection-modal :chain-bounce [...])`
- **Missing defmethod:** `(defmethod render-selection-modal :chain-bounce-target [...])`
- **Fallback behavior:** Defaults to `card-selection-modal` (generic card grid)
- **Issue:** Generic modal doesn't match `:valid-targets` card-source + object filtering semantics

**2. `:hand-reveal-discard` (Duress)**
- **Card:** Duress (Sorcery, target opponent, show hand, choose noncreature/nonland to discard)
- **Status:** ✓ HAS dedicated defmethod (line 887) - `hand-reveal-discard-modal`
- **Status:** ✓ HAS ::selection-cards handler (`:opponent-hand` card-source, line 413)
- **Status:** ✓ WORKS correctly - shows all opponent cards, only allows selecting matching types

### Why the Gap Exists

- **Duress:** commit 98029e1 added both card definition + modal component together
- **Chain of Vapor:**
  - commit 5dae8c1 added basic card + bounce effect
  - commit 57be302 added chain mechanic (`:chain-bounce` effect + selection types)
  - BUT: No accompanying view layer defmethod was added
  - Result: Selection UI uses wrong modal component

## File Locations Summary

| Component | File | Lines |
|-----------|------|-------|
| render-selection-modal multimethod | `src/main/fizzle/views/modals.cljs` | 823-896 |
| ::selection-cards subscription | `src/main/fizzle/subs/game.cljs` | 379-422 |
| hand-reveal-discard-modal component | `src/main/fizzle/views/modals.cljs` | 274-306 |
| valid-targets filtering | `src/main/fizzle/engine/targeting.cljs` | 25-68 |
| matches-criteria? function | `src/main/fizzle/db/queries.cljs` | 247-275 |
| Zone ops builders | `src/main/fizzle/events/selection/zone_ops.cljs` | 1-275 |
| Targeting builders | `src/main/fizzle/events/selection/targeting.cljs` | 1-150+ |
| Library builders | `src/main/fizzle/events/selection/library.cljs` | (full file) |
| Duress card | `src/main/fizzle/cards/black/duress.cljs` | 1-34 |
| Chain of Vapor card | `src/main/fizzle/cards/blue/chain_of_vapor.cljs` | 1-39 |

## Validation Status

✓ `::selection-cards` subscription correctly handles all 6 card-source types
✓ `hand-reveal-discard` selection type and view are complete and working
✓ `valid-targets` filtering via `matches-criteria?` is complete and working
✓ `render-selection-modal` multimethod has 15 working defmethods + 1 fallback
✗ `:chain-bounce` and `:chain-bounce-target` lack dedicated view defmethods (use fallback)
