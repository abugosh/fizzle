# Hypergeometric Calculator Feature Investigation

**Investigation Date**: 2026-03-15
**Scope**: Integration feasibility for hypergeometric probability calculations

## 1. Existing Probability Code

**Finding**: No hypergeometric or statistical probability code exists in the codebase.

- Grep searches for "hypergeometric", "probability", "stats", "calculator" return only:
  - Comments in combat math preview context (`views/selection/common.cljs:54`)
  - Combat math display (power vs toughness comparisons)
  - No calculation engine, no formula implementations

**Implication**: Hypergeometric calculator would be a new subsystem — no existing utilities to build on.

---

## 2. Decklist Data Structure

### Storage Format: Card Entry Vector

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/setup.cljs:39-74`

```clojure
{:card/id :dark-ritual :count 4}
{:card/id :brain-freeze :count 4}
{:card/id :island :count 2}
;; ... etc
```

**Data Shape**:
- Main deck: vector of `{:card/id :keyword :count N}` maps
- Sideboard: same format, stored separately
- Total: 60-card main + 15-card side
- Stored in app-db under: `:setup/main-deck` and `:setup/sideboard`

### Named Card Lookup

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/deck-parser.cljs:10-14`

```clojure
(def name-lookup
  (into {}
        (map (fn [card]
               [(str/lower-case (:card/name card)) (:card/id card)]))
        cards/all-cards))
```

- Decklist parser converts card names (text format) → `:card/id` keywords
- Engine has `cards/all-cards` vector with full card definitions
- Card definitions include: `:card/name`, `:card/cmc`, `:card/types`, `:card/colors`, etc.

### Access Points

1. **Setup Screen (Pre-game)**:
   - Subscriptions: `::setup-subs/current-main`, `::setup-subs/current-side`
   - Both return vector of `{:card/id :keyword :count N}` entries
   - See: `/Users/abugosh/g/fizzle/src/main/fizzle/subs/setup.cljs:37-52`

2. **During Game (In-game Library)**:
   - Query: `queries/get-objects-in-zone(db, player-id, :library)`
   - Returns vector of game objects (instances with `:object/id`, `:object/zone`, card reference)
   - See: `/Users/abugosh/g/fizzle/src/main/fizzle/db/queries.cljs:90-101`

3. **Stashed Config**:
   - When game starts, setup config is stashed in game state
   - Key: `:setup/main-deck`, `:setup/sideboard`
   - See: `/Users/abugosh/g/fizzle/src/main/fizzle/events/setup.cljs:242-249`

---

## 3. Current UI Structure

### Views Directory Layout

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/views/`

```
opponent.cljs          — Opponent display panel
common.cljs            — Shared styling/components
import_modal.cljs      — Deck import dialog
game_over.cljs         — Game end screen
zone_counts.cljs       — GY/Library/Exile counts (right-aligned)
card_styles.cljs       — Card rendering utilities
hand.cljs              — Player hand cards
opening_hand.cljs      — Opening hand mulligan screen
phase_bar.cljs         — Phase/priority indicator
setup.cljs             — Deck selection, main/side deck lists
stack.cljs             — Stack of spells/abilities
graveyard.cljs         — Graveyard zone view
mana_pool.cljs         — Mana pool display
modals.cljs            — Selection/modal dialogs
controls.cljs          — Cast/activate/pass controls
history.cljs           — Fork/replay history sidebar
battlefield.cljs       — Board permanents (currently empty for creatures)
```

### Main Game Layout

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/core.cljs:69-93`

```
┌─────────────────────────────────────────┐
│ Header (Setup / Game / New Game buttons) │
├─────────────────────────────────────────┤
│ Graveyard | Center Content              │ History  │
│  (left)   │ - Battlefield               │  (right) │
│           │ - Stack                     │  (fold)  │
│           │ - Phase Bar                 │          │
│           │ - Hand                      │          │
│           │ - Controls                  │          │
│           │ - Mana Pool                 │          │
│           │ - Zone Counts               │          │
├─────────────────────────────────────────┤
│ [Reserved for calculator panel]         │  <-- LINE 88-89
└─────────────────────────────────────────┘
```

**Collapsible Columns** (left & right):
- Left column (graveyard): `::subs/gy-collapsed` toggle via `::events/toggle-gy-collapsed`
- Right column (history): `::subs/history-collapsed` toggle via `::events/toggle-history-collapsed`

**Integration Point**: Line 88-89 in core.cljs explicitly reserves space for calculator:
```clojure
;; Bottom: reserved for calculator panel
[:div {:class "col-span-full"}]
```

### Zone Counts Display

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/views/zone_counts.cljs`

Currently shows:
- GY count (highlights if >= 7 for threshold)
- Library count
- Exile count

Uses subscriptions:
- `::subs/player-zone-counts` → `{:graveyard N :library N :exile N :threshold? bool}`
- `::subs/opponent-zone-counts` → same structure

---

## 4. Setup Event Flow

### Entry Point: Setup Screen

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/views/setup.cljs:1-67`

Provides:
1. Deck selector (Iggy Pop, imported decks)
2. Preset bar (save/load/delete presets)
3. Main deck list (grouped by type: Lands, Instants, Sorceries, etc.)
4. Sideboard list
5. Must-contain toggle (hand sculpting feature)
6. Bot archetype selector
7. Quick Start button

### Setup Events

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/setup.cljs`

Key handlers:
- `init-setup-handler` — Initialize setup state from localStorage
- `select-deck-handler` — Load deck into `:setup/main-deck` and `:setup/sideboard`
- `move-to-side-handler` / `move-to-main-handler` — Modify deck
- `toggle-must-contain-handler` — Set required opening hand cards (1-7 card cap)
- `save-preset-handler` / `load-preset-handler` — Persist user configs

### Hand Sculpting: Must-Contain System

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/setup.cljs:207-233`

- Stores required opening hand cards in `:setup/must-contain` (map: `card-id -> count`)
- Global cap: 7 total "must-contain" slots
- Used to restrict opening hands during setup
- Clamped when deck changes (if main-deck count drops below must-contain count)

**Related Subscriptions**:
- `::setup-subs/must-contain` — Returns the map
- `::setup-subs/must-contain-cards` — Enriched with card names and max counts

---

## 5. Existing Stats/Analytics

**Finding**: No stats, analytics, or probability features exist.

Closest features:
1. **Zone counts** — Simple cardinality display (GY/Lib/Exile)
2. **Storm count** — Spell count this turn (for storm combo tracking)
3. **Threshold indicator** — Highlights GY count when >= 7
4. **Mana pool tracking** — Shows available mana by color
5. **Hand size** — Not explicitly shown in UI (used for max hand size validation)

**No existing infrastructure for**:
- Deck statistics
- Draw probabilities
- Hypergeometric calculations
- Odds display
- Probability conditioning (based on hand, graveyard state)

---

## 6. Subscriptions for Deck/Zone Information

### Setup-Phase Subscriptions

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/subs/setup.cljs`

| Subscription | Returns | Purpose |
|---|---|---|
| `::current-main` | `[{:card/id :keyword :count N} ...]` | Main deck |
| `::current-side` | `[{:card/id :keyword :count N} ...]` | Sideboard |
| `::current-main-grouped` | `{:land [...] :instant [...] ...}` | Grouped by type for UI |
| `::current-side-named` | `[{:card/id :keyword :card/name "..." :count N} ...]` | Side with names |
| `::main-count` | `N` | Total main deck size |
| `::side-count` | `N` | Total sideboard size |
| `::must-contain` | `{:card-id -> count}` | Required opening cards |
| `::must-contain-cards` | `[{:card/id :keyword :card/name "..." :count N :max-count N} ...]` | Enriched must-contain |

### Game-Phase Subscriptions

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/subs/game.cljs:370-397`

| Subscription | Returns | Purpose |
|---|---|---|
| `::player-zone-counts` | `{:graveyard N :library N :exile N :threshold? bool}` | Player zones |
| `::opponent-zone-counts` | `{:graveyard N :library N :exile N}` | Opponent zones |
| `::hand` | `[game-object ...]` | Player hand with card data |
| `::game-db` | `datascript-db` | Full game state (Datascript) |

### Zone Queries

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/queries.cljs`

```clojure
(queries/get-objects-in-zone db player-id zone)
  → [game-object ...]

(queries/get-hand db player-id)
  → [game-object ...]

(queries/get-player-eid db player-id)
  → entity-id | nil
```

---

## 7. Integration Points & Recommendations

### Where Hypergeometric Calculator Fits

#### Option A: Setup Screen Panel (Pre-game)
- **Location**: Add to `/Users/abugosh/g/fizzle/src/main/fizzle/views/setup.cljs`
- **Trigger**: "Calculator" tab or panel in setup screen
- **Data Access**: Use `::setup-subs/current-main` and `::setup-subs/must-contain`
- **Use Case**: "What's the probability of opening with 2+ Rituals?" before Quick Start

**Advantages**:
- Stateless (no game simulation)
- User can explore different must-contain scenarios
- Can switch between decks and recalculate

#### Option B: Game Screen Bottom Panel (In-game)
- **Location**: Reserve at `/Users/abugosh/g/fizzle/src/main/fizzle/core.cljs:88-89` (already marked "reserved")
- **Trigger**: Toggle button in phase bar or controls
- **Data Access**: Subscriptions to current library, hand, graveyard
- **Use Case**: "What's my probability of drawing Ritual this turn with X cards left in deck?" during active game

**Advantages**:
- Updates as game progresses
- Accounts for known deck composition (drawn, in graveyard, in hand)
- Helps with decision-making mid-combo

#### Option C: Hybrid (Both)
- Tab in setup for deck-wide statistics
- Collapsible panel in game screen for real-time odds

### Data Layer (New Module)

**Recommended file**: `/Users/abugosh/g/fizzle/src/main/fizzle/math/hypergeometric.cljs`

Exports:
```clojure
(defn probability-in-top-n
  "P(seeing at least k copies of card-id in top n cards)
   Inputs:
     - total-cards: 60 (deck size)
     - copies-wanted: k (how many we want)
     - copies-in-deck: 4 (total in deck)
     - cards-drawn: n
   Returns: probability as decimal 0.0-1.0"
  [total-cards copies-in-deck cards-drawn copies-wanted])

(defn probability-draw-n-turns
  "P(drawing at least k cards with property P in next n turns)
   Accounts for:
     - Cards already drawn (in hand, graveyard)
     - Must-contain requirements
   Returns: probability as decimal"
  [deck-state target-card num-turns copies-wanted])

;; Plus utility functions:
;; - format-percentage(decimal) → "45.3%"
;; - combinations(n k) → C(n,k)
;; - nCr-cache for performance
```

### View Layer (New Component)

**Recommended file**: `/Users/abugosh/g/fizzle/src/main/fizzle/views/calculator.cljs`

Displays:
- Input: "How many cards?" (1-10)
- Input: "Looking for?" (card selector dropdown)
- Input: "At least?" (1-4 copies)
- Output: Probability as percentage + bar chart
- Breakdown: P(exactly 1), P(exactly 2), P(exactly 3), etc.

### Subscription/Event Helpers

Add to `/Users/abugosh/g/fizzle/src/main/fizzle/subs/game.cljs`:

```clojure
(rf/reg-sub
  ::library-state-snapshot
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      {:total-cards 60
       :in-library (queries/get-objects-in-zone game-db human-pid :library)
       :in-hand (queries/get-hand game-db human-pid)
       :in-graveyard (queries/get-objects-in-zone game-db human-pid :graveyard)
       :in-exile (queries/get-objects-in-zone game-db human-pid :exile)})))
```

---

## 8. Architecture Decisions

### Why Not Use Existing Math Libraries?

ClojureScript has math libraries (e.g., `org.apache.commons/commons-math3` is not available in JS). Hypergeometric calculations can be implemented with:
- **Combinatorics**: `C(n,k) = n! / (k! * (n-k)!)`
- **Hypergeometric PDF**: `P(X=k) = C(K,k) * C(N-K,n-k) / C(N,n)`
- **CDF**: Sum of PDFs (P(X <= k))

**Recommendation**: Implement custom module (~100 lines of pure math functions).

### Performance Considerations

Hypergeometric requires computing combinations (factorials). For deck-sized inputs (60 cards, drawing 5-10 at a time):
- Precompute factorials up to 60
- Cache `C(n,k)` results
- Avoid computing for n > 60

Cost: Negligible for UI responsiveness.

### Data Isolation

Calculator should NOT:
- Modify game state
- Dispatch events
- Depend on game events

Should only:
- Read deck/zone data via subscriptions
- Compute odds
- Display results

---

## Summary: Integration Readiness

| Aspect | Status | Notes |
|---|---|---|
| **Decklist Access** | ✓ Ready | `::setup-subs/current-main` + card lookup |
| **Zone Cardinality** | ✓ Ready | `::subs/player-zone-counts` provides live counts |
| **UI Space** | ✓ Ready | Reserved panel at `core.cljs:88-89` |
| **Data Shape** | ✓ Ready | Flat vector of `{:card/id :count}` entries |
| **Existing Calc Code** | ✗ Missing | Hypergeometric module does not exist (must build) |
| **Must-Contain Support** | ✓ Ready | Can query `::setup-subs/must-contain` |
| **Subscriptions** | ✓ Ready | Zone counts, deck config, library state available |
| **Events** | ✓ Ready | Calculator is read-only; no new events needed (except toggle visibility) |

**Recommendation**: Feasible as new feature. Build in two phases:
1. **Phase 1 (Setup)**: Deck-wide probability view on setup screen
2. **Phase 2 (Game)**: Real-time library odds during play (uses live game state)

Both can share the same `math/hypergeometric.cljs` module.
