# Selection Modal to Controls Refactor — Architecture Analysis

## Current Architecture Overview

The selection system renders interactive components through **one centralized modal router**. All 7 mechanisms dispatch through the same entry point, with 4 being candidates for relocation to inline controls area.

### Modal Rendering Path

```
game.cljs:107          — renders [modals/selection-modal]
  ↓
modals.cljs:170-175    — subscription to ::subs/pending-selection
  ↓
modals.cljs:31-32      — defmulti render-selection-modal on :selection/mechanism
  ↓
7 mechanism defmethods (modals.cljs:68-163)
  ↓
domain-specific sub-components (selection/ subdir)
```

**Key file:** `/Users/abugosh/g/fizzle/src/main/fizzle/views/modals.cljs` — 176 lines total

---

## Selection Mechanisms & Current Rendering

### 1. `:binary-choice` (replacement effects)
- **Current render:** `modals.cljs:158-162` → `replacement-view/replacement-choice-modal`
- **Component:** `/Users/abugosh/g/fizzle/src/main/fizzle/views/selection/replacement.cljs:18-45`
- **Data structure:**
  ```clojure
  {:selection/mechanism :binary-choice
   :selection/domain :replacement-choice  ; or :unless-pay
   :selection/choices [{:choice/label "Proceed" :choice/action :proceed}
                       {:choice/label "Redirect" :choice/action :redirect}]
   :selection/selected #{}  ; set containing choice map
   :selection/auto-confirm? true}
  ```
- **Interaction:** Buttons for each choice, click to toggle selection, confirm button
- **Status in modals:** Returns modal component; needs routing to controls

### 2. `:pick-mode` (spell modes)
- **Current render:** `modals.cljs:146-151` → domain-based dispatch
- **Component:** `/Users/abugosh/g/fizzle/src/main/fizzle/views/selection/custom.cljs` (spell-mode-selection-modal)
- **Data structure:**
  ```clojure
  {:selection/mechanism :pick-mode
   :selection/domain :spell-mode  ; or :land-type-source/:land-type-target
   :selection/modes [{:mode/name "Target creature" :mode/effect-type :kill-target}
                     {:mode/name "Draw 2 cards" :mode/effect-type :draw}]
   :selection/selected #{}  ; set containing selected mode
   :selection/select-count 1
   :selection/auto-confirm? false}
  ```
- **Interaction:** Button for each mode, select one, confirm
- **Status in modals:** Returns modal component; needs routing to controls

### 3. `:accumulate` (X cost, pay X life, storm split)
- **Current render:** `modals.cljs:90-95` → domain-based dispatch
- **Components:**
  - X-mana: `/Users/abugosh/g/fizzle/src/main/fizzle/views/selection/accumulator.cljs:15-46`
  - Pay-X-life: `accumulator.cljs:51-78`
  - Storm-split: `accumulator.cljs:119-147`
- **Data structure (X-mana example):**
  ```clojure
  {:selection/mechanism :accumulate
   :selection/domain :x-mana-cost  ; or :pay-x-life, :storm-split
   :selection/selected-x 2
   :selection/max-x 5
   :selection/mode {:mode/mana-cost {:colorless 1}}
   :selection/auto-confirm? false}
  ```
- **Interaction:** Stepper controls (-, +), display current value, confirm
- **Status in modals:** Returns modal component; needs routing to controls

### 4. `:allocate-resource` (mana allocation)
- **Current render:** `modals.cljs:103` → **returns nil** (already inline!)
- **Component:** `/Users/abugosh/g/fizzle/src/main/fizzle/views/mana_pool.cljs:48-78` (allocation-view)
- **Data structure:**
  ```clojure
  {:selection/mechanism :allocate-resource
   :selection/domain :mana-allocation
   :selection/allocation {:white 1 :blue 0}  ; amounts allocated per color
   :selection/remaining-pool {:white 2 :blue 1}  ; remaining unallocated
   :selection/generic-total 5
   :selection/generic-remaining 2}
  ```
- **Interaction:** Color buttons (click to add mana to slot), reset, confirm
- **Subscription:** `subs/game.cljs:410-414` — `::mana-allocation-state`
- **Status in modals:** ALREADY INLINE in mana-pool-view!
- **Note:** The modal method returns `nil` because rendering is handled elsewhere

### Staying as Modals (Read-Only for This Refactor)
- `:n-slot-targeting` — targeting selections (complex modal with cards/players)
- `:pick-from-zone` — zone picks (card grids)
- `:reorder` — card ordering (scry, order-top, etc.)

---

## Selection Lifecycle & Event Flow

### Setting Pending Selection
1. **Entry point:** Any handler needing player interaction calls `build-selection-for-effect` or similar builder
2. **Builder returns:** Selection map with `:selection/mechanism`, `:selection/domain`, `:selection/lifecycle`
3. **Wiring to app-db:** `set-pending-selection` in `spec.cljs:684-695`
   ```clojure
   (defn set-pending-selection [app-db selection]
     (spec-util/validate-at-chokepoint! ::selection selection "set-pending-selection")
     (assoc app-db :game/pending-selection selection))
   ```
4. **Spec validation:** `spec.cljs` enforces mechanism/domain contract at chokepoint (line 693)

### Confirm Path
```
dispatch ::selection-events/confirm-selection
  ↓
confirm-selection-handler (events/selection.cljs)
  ↓
confirm-selection-impl (core.cljs:430)
  ↓
execute-confirmed-selection multimethod (core.cljs:72-245)
  ↓
apply-domain-policy multimethod (core.cljs:87-98)
  ↓
domain-specific handler (e.g., replacement.cljs, costs.cljs)
  ↓
dissoc :game/pending-selection
```

**Key file:** `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs`

---

## Controls View Architecture

**Current file:** `/Users/abugosh/g/fizzle/src/main/fizzle/views/controls.cljs` — 86 lines

### Current Buttons
- "Cast [name]" / "Play [name]" — dispatches `::casting-events/cast-spell` or `::lands-events/play-land`
- "Cast & Yield" / "Play & Yield" — dispatches `::priority-flow-events/cast-and-yield`
- "Cycle [name]" (conditional) — dispatches `::cycling-events/cycle-card`
- "Yield: [stack-item-name]" — dispatches `::priority-flow-events/yield`
- "Yield All (N)" — dispatches `::priority-flow-events/yield-all`

### Subscriptions Used
```clojure
(let [can-cast? @(rf/subscribe [::subs/can-cast?])
      can-play-land? @(rf/subscribe [::subs/can-play-land?])
      can-cycle? @(rf/subscribe [::subs/can-cycle?])
      selected @(rf/subscribe [::subs/selected-card])
      card-info @(rf/subscribe [::subs/selected-card-info])
      stack @(rf/subscribe [::subs/stack])]
  ...)
```

### How to Extend
- Add new subscription for pending selection state (if needed)
- Conditionally render selection-specific UI blocks
- Dispatch toggle and confirm events to selection system

---

## Subscription Structure for Selection

**File:** `/Users/abugosh/g/fizzle/src/main/fizzle/subs/game.cljs`

### Core Subscriptions (Required for All Inline Mechanisms)

| Subscription | Line | Returns | Usage |
|---|---|---|---|
| `::pending-selection` | 395-398 | `(:game/pending-selection db)` | Raw selection state |
| `::selection-valid?` | 401-406 | bool | Show "valid" vs "invalid" label |
| `::mana-allocation-state` | 410-414 | selection if domain is `:mana-allocation` | Already used by mana-pool-view |
| `::unless-pay-state` | 417-422 | selection if domain is `:unless-pay` | Checks for unless-pay choice |

### Domain-Specific Subscriptions

| Subscription | Line | Purpose |
|---|---|---|
| `::unless-pay-can-afford?` | 425-441 | Checks if player has mana to pay optional cost |
| `::selection-hand` | 444-451 | Pulls cards available in selection |
| `::selection-cards` | 457-487+ | Maps card sources (candidates, valid-targets, library, etc.) |

### New Subscriptions You'll Need

For inline rendering of the 4 mechanisms, you may want to add:
- `::is-binary-choice?` — true when `(:selection/mechanism s) == :binary-choice`
- `::is-pick-mode?` — true when `(:selection/mechanism s) == :pick-mode`
- `::is-accumulate?` — true when `(:selection/mechanism s) == :accumulate`
- OR: single `::selection-mechanism` returning the mechanism keyword
- `::selection-choices` — returns `(:selection/choices s)` for binary-choice
- `::x-value` — returns `(:selection/selected-x s)` for accumulate

---

## Data-Driven Dispatch Pattern (ADR-030)

All selections now dispatch on **`:selection/mechanism`** (interaction pattern) followed by **`:selection/domain`** (policy).

### Mechanism Defmethods (core.cljs:213-245)
```clojure
(defmethod execute-confirmed-selection :pick-from-zone [game-db selection]
  (apply-domain-policy game-db selection))

(defmethod execute-confirmed-selection :binary-choice [game-db selection]
  (apply-domain-policy game-db selection))
;; ... etc for all 7 mechanisms
```

Each mechanism's defmethod delegates to `apply-domain-policy`, which dispatches on `:selection/domain` for policy-specific handling.

### View Router Pattern (modals.cljs:31-32)
```clojure
(defmulti render-selection-modal
  (fn [selection _cards] (:selection/mechanism selection)))
```

**For inline rendering:** You'll replicate this pattern in `controls.cljs`:
```clojure
(defmulti render-inline-selection
  (fn [selection] (:selection/mechanism selection)))

(defmethod render-inline-selection :binary-choice [selection]
  ;; render buttons for choices
  )
```

---

## Implementation Strategy for 4 Mechanisms

### Step 1: Add Subscriptions (subs/game.cljs)

Add these for easier access to selection data:

```clojure
(rf/reg-sub ::selection-mechanism
  :<- [::pending-selection]
  (fn [sel _] (:selection/mechanism sel)))

(rf/reg-sub ::selection-domain
  :<- [::pending-selection]
  (fn [sel _] (:selection/domain sel)))

(rf/reg-sub ::binary-choice-state
  :<- [::pending-selection]
  (fn [sel _]
    (when (= :binary-choice (:selection/mechanism sel)) sel)))

(rf/reg-sub ::pick-mode-state
  :<- [::pending-selection]
  (fn [sel _]
    (when (= :pick-mode (:selection/mechanism sel)) sel)))

(rf/reg-sub ::accumulate-state
  :<- [::pending-selection]
  (fn [sel _]
    (when (= :accumulate (:selection/mechanism sel)) sel)))
```

### Step 2: Extend controls-view (controls.cljs:32-85)

```clojure
(defn controls-view []
  (let [... existing bindings ...
        binary-choice @(rf/subscribe [::subs/binary-choice-state])
        pick-mode @(rf/subscribe [::subs/pick-mode-state])
        accumulate @(rf/subscribe [::subs/accumulate-state])]
    [:div {:class "mb-4"}
     ;; Existing buttons
     (when-not (or binary-choice pick-mode accumulate)
       ;; Only show play buttons if not in selection mode
       [... existing buttons ...])
     
     ;; Binary choice (replacement/unless-pay)
     (when binary-choice
       [render-binary-choice binary-choice])
     
     ;; Pick mode
     (when pick-mode
       [render-pick-mode pick-mode])
     
     ;; Accumulate (X-mana, pay-X-life, storm-split)
     (when accumulate
       [render-accumulate accumulate])]))
```

### Step 3: Create Helper Components (controls.cljs or new selection/controls.cljs)

For each mechanism, create a pure component following the pattern in `replacement.cljs` and `accumulator.cljs`:

- **`render-binary-choice`** — button per choice, toggle/confirm
- **`render-pick-mode`** — button per mode, toggle/confirm
- **`render-accumulate`** — domain-specific dispatch to stepper UI

### Step 4: Update Modal Router (modals.cljs:31-163)

Route returning mechanisms to nothing:

```clojure
(defmethod render-selection-modal :binary-choice [s _] nil)
(defmethod render-selection-modal :pick-mode [s _] nil)
(defmethod render-selection-modal :allocate-resource [_ _] nil)  ; already nil
(defmethod render-selection-modal :accumulate [s _] nil)
```

Leave `:n-slot-targeting`, `:pick-from-zone`, `:reorder` as-is.

---

## Event Handlers Required

No new event handlers are needed! Use existing:

| Event | File | Purpose |
|---|---|---|
| `::selection-events/toggle-selection` | selection.cljs:31-33 | Deselect/select an item |
| `::selection-events/confirm-selection` | selection.cljs:36-39 | Apply the selection |
| `::cost-events/increment-x-value` | selection/costs.cljs | Increment X (for accumulate) |
| `::cost-events/decrement-x-value` | selection/costs.cljs | Decrement X |
| `::storm-events/adjust-storm-split` | selection/storm.cljs | Adjust storm copy count |

The UI components call these directly via `rf/dispatch`.

---

## Key Implementation Files

### Architecture Entry Point
- **`src/main/fizzle/core.cljs`** line 107 — renders `[modals/selection-modal]` in game-screen

### Selection System Entries
- **`src/main/fizzle/subs/game.cljs`** — subscriptions (395-600+ lines)
- **`src/main/fizzle/events/selection/core.cljs`** — mechanism multimethods (72-245)
- **`src/main/fizzle/events/selection/spec.cljs`** — validation at chokepoint (684-695)
- **`src/main/fizzle/events/selection.cljs`** — event handlers (toggle, confirm)

### Current Modal Implementations (Reference)
- **`src/main/fizzle/views/modals.cljs`** — main router
- **`src/main/fizzle/views/selection/replacement.cljs`** — binary-choice modal
- **`src/main/fizzle/views/selection/custom.cljs`** — pick-mode modal (spell-mode-selection-modal)
- **`src/main/fizzle/views/selection/accumulator.cljs`** — accumulate modals (x-mana, pay-x-life, storm-split)
- **`src/main/fizzle/views/mana_pool.cljs:48-78`** — allocate-resource inline (already working)

### Controls View
- **`src/main/fizzle/views/controls.cljs`** — where you'll add inline rendering

---

## Data Flow Diagram

```
Game State (:game/pending-selection = selection map)
  ↓
Subscription (::pending-selection)
  ↓
Modal Router (modals/selection-modal) OR Inline Renderer (controls-view)
  ├─ :binary-choice → replacement-choice-modal OR inline buttons
  ├─ :pick-mode → spell-mode-selection-modal OR inline buttons
  ├─ :accumulate → accumulator modals OR inline stepper
  ├─ :allocate-resource → nil (renders in mana-pool-view)
  └─ :n-slot-targeting, :pick-from-zone, :reorder → modal-only
  ↓
User interaction: toggle-selection, confirm-selection
  ↓
execute-confirmed-selection multimethod
  ↓
apply-domain-policy multimethod
  ↓
Domain handler updates game-db, clears pending-selection
```

---

## Testing Considerations

1. **Inline rendering tests** — Assert that pending-selection → UI components render (no modal overlay)
2. **Event dispatch** — Toggle and confirm should dispatch same events as modals
3. **Subscription filtering** — Add tests for new subscriptions (binary-choice-state, etc.)
4. **Mutual exclusivity** — When selection is pending, play buttons disabled; selection buttons enabled
5. **Production paths** — Use `th/cast-with-mode`, `th/confirm-selection` helpers from test-helpers

---

## Summary: 4 Mechanisms to Move

| Mechanism | Domain(s) | Modal Component | Inline Location |
|---|---|---|---|
| `:binary-choice` | `:replacement-choice`, `:unless-pay` | `replacement.cljs:18-45` | controls.cljs (new) |
| `:pick-mode` | `:spell-mode`, `:land-type-*` | `custom.cljs` (spell-mode-selection-modal) | controls.cljs (new) |
| `:accumulate` | `:x-mana-cost`, `:pay-x-life`, `:storm-split` | `accumulator.cljs:15-147` | controls.cljs (new) |
| `:allocate-resource` | `:mana-allocation` | (**returns nil**) | mana_pool.cljs:48-78 (**already inline**) |

The `:allocate-resource` mechanism is already routing to `mana-pool.cljs` because the modal defmethod returns `nil`. The other 3 need explicit routing away from the modal system to controls.
