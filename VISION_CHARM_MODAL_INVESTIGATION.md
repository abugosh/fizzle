# Vision Charm Modal Spell Flow Investigation

## Issue Report
User reports that Vision Charm's mode dialog shows options but nothing happens after picking one.

## Architecture Overview

### Two Modal Systems
The codebase has two separate modal mechanisms:

1. **`:game/pending-mode-selection`** — For spells with multiple valid CASTING MODES
   - Used when a card has multiple ways to be cast (hand primary vs. graveyard flashback, etc.)
   - Handled by `select-casting-mode-handler` in `events/casting.cljs`
   - Rendered by `mode-selector-modal` in `views/selection/custom.cljs`
   - Shows "Normal Cast" / "Flashback" / etc. mana cost options

2. **`:spell-mode` selection** — For MODAL SPELLS (choose one ability)
   - Used when a card has `:card/modes` like "Choose one —" REB/BEB/Vision Charm
   - Handled by `execute-confirmed-selection :spell-mode` + `apply-continuation :cast-after-spell-mode`
   - Rendered by `spell-mode-selection-modal` in `views/selection/custom.cljs`
   - Shows ability text options from the card's modes array

### Vision Charm Modal Flow (Correct Implementation)

```
User clicks "Cast Vision Charm"
  ↓
cast-spell-handler
  ↓
get-valid-spell-modes returns all 3 modes
  (Mode 1 has targeting, Mode 2 has no targeting, Mode 3 has targeting)
  ↓
All modes have valid targets (none or satisfied)
  → builds :spell-mode selection with auto-confirm=true
  ↓
UI shows spell-mode-selection-modal with 3 buttons:
  - "Target player mills four cards"
  - "Change land types until end of turn"
  - "Target artifact phases out"
  ↓
User clicks a mode button
  ↓
toggle-selection dispatches with the chosen mode
  ↓
toggle-selection-impl adds mode to :selection/selected
  ↓
auto-confirm triggers (select-count=1, auto-confirm?=true)
  → dispatches ::confirm-selection
  ↓
execute-confirmed-selection :spell-mode sets :object/chosen-mode
  ↓
apply-continuation :cast-after-spell-mode
  → calls initiate-cast-with-mode with the chosen mode
  ↓
Pre-cast pipeline evaluates:
  :exile-cards-cost → nil (no exile cost)
  :return-land-cost → nil (no return cost)
  :discard-specific-cost → nil (no discard cost)
  :sacrifice-permanent-cost → nil (no sac cost)
  :pay-x-life → nil (no X life cost)
  :x-mana-cost → nil (no X mana cost)
  :targeting → For Mode 2: nil (no targeting)
              For Mode 1/3: builds targeting selection
  :mana-allocation → nil (no generic mana)
  ↓
If no selections needed: casts immediately via rules/cast-spell-mode
If selections needed: shows targeting dialog
```

## Key Code Locations

### Card Definition
- File: `src/main/fizzle/cards/blue/vision_charm.cljs`
- Structure: `:card/modes` array with 3 mode maps
  - Mode 1: `:mode/label`, `:mode/targeting`, `:mode/effects`
  - Mode 2: `:mode/label`, `:mode/effects` (NO targeting)
  - Mode 3: `:mode/label`, `:mode/targeting`, `:mode/effects`

### Modal Selection System
- File: `src/main/fizzle/events/casting.cljs`
  - `build-spell-mode-selection` (line 184) — Creates selection with `auto-confirm? true`
  - `execute-confirmed-selection :spell-mode` (line 203) — Sets `:object/chosen-mode`
  - `apply-continuation :cast-after-spell-mode` (line 213) — Routes to spell casting

### Pre-Cast Pipeline
- File: `src/main/fizzle/events/casting.cljs`
  - `evaluate-pre-cast-step :targeting` (line 94) — Checks chosen-mode's targeting
  - `initiate-cast-with-mode` (line 141) — Loops through pipeline steps

### UI Component
- File: `src/main/fizzle/views/selection/custom.cljs`
  - `spell-mode-selection-modal` (line 297) — Displays modes with buttons
  - No explicit Confirm button (relies on auto-confirm)

### Auto-Confirm Mechanism
- File: `src/main/fizzle/events/selection/core.cljs`
  - `toggle-selection-impl` (line 401) — Checks auto-confirm flag at line 450
  - Dispatches `::confirm-selection` when count reaches select-count

## Potential Issues (Investigation Results)

### Issue 1: Auto-Confirm Dispatch Timing
**Status: Not a bug, but worth verifying**

The auto-confirm dispatch happens at line 451:
```clojure
(rf/dispatch [:fizzle.events.selection/confirm-selection])
```

This dispatches the event asynchronously. If there's an issue with how events are processed or if a synchronous modal handler is expected, this could cause the UI to appear unresponsive. However, this is the correct pattern in re-frame.

**Verification**: Check browser console for dispatch errors when mode is clicked.

### Issue 2: Mode Selection Mismatch
**Status: Possible root cause**

The `spell-mode-selection-modal` takes the entire mode object as the toggle argument (line 308):
```clojure
:on-click #(rf/dispatch [::selection-events/toggle-selection mode])
```

The `toggle-selection-impl` expects an `id` (line 407) which is compared against items in `:selection/selected`.

**Question**: Is the mode object itself being used as the comparison key? Or should it be a mode ID?

Looking at line 426-428:
```clojure
(= select-count 1)
[(assoc-in app-db [:game/pending-selection :selection/selected]
           #{id})
```

The selected set receives the entire mode object. Then at `execute-confirmed-selection :spell-mode` (line 205):
```clojure
(let [chosen-mode (first (:selection/selected selection))]
```

The first item in `:selection/selected` is retrieved and assumed to be the mode object. This should work IF the mode object is hashable and comparable.

**Verification**: Test that the mode object survives the round-trip through `:selection/selected` set. In Clojure(Script), objects with the same reference should work in sets, but value-equality might fail if the object is re-created.

### Issue 3: Missing Confirmation Visual Feedback
**Status: UX concern, not functional bug**

The `spell-mode-selection-modal` has no visible Confirm button. Users might expect a button they need to click. The auto-confirm behavior is invisible.

**Potential Fix**: Add a visual indicator (e.g., highlight the selected mode, show "Ready to cast" message) or add an explicit confirm button for clarity.

## Tests

Vision Charm test file: `src/test/fizzle/cards/blue/vision_charm_test.cljs`

Tests use `th/cast-mode-with-target` helper which bypasses the UI selection system and directly:
1. Sets `:object/chosen-mode` on the object
2. Builds targeting selection with the chosen mode
3. Confirms with explicit target

This tests the engine path but NOT the UI -> event dispatch -> modal flow.

To verify the actual issue, you would need to:
1. Test the mode selection in the running app
2. Check browser console for JavaScript errors
3. Verify that `::selection-events/toggle-selection` is being dispatched
4. Verify that `::confirm-selection` auto-dispatch fires after toggle
5. Check that `:object/chosen-mode` is set on the object after confirmation

## Recommendations

### 1. Verify Basic Flow (Quick)
Run the app and test Vision Charm manually:
- Click Cast button
- Confirm mode selection dialog appears
- Click Mode 1 button
- Expected: Dialog closes, targeting selection appears or spell casts

If spell doesn't cast and no targeting dialog appears, the issue is in the auto-confirm flow.

### 2. Add Console Logging (Medium)
Patch `toggle-selection-impl` to log:
```clojure
(js/console.log "toggle-selection: auto-confirm?" (:selection/auto-confirm? selection)
                "select-count:" select-count
                "selected?:" selected?
                "dispatching confirm?" (and selected? (= select-count 1) (:selection/auto-confirm? selection)))
```

### 3. Add Modal Clarity (Easy UX Improvement)
In `spell-mode-selection-modal`, add a highlighted selection indicator:
```clojure
[:div {:class (str "text-sm mb-2 text-center "
                   (if (seq (:selection/selected selection))
                     "text-health-good"
                     "text-text-muted"))}
 (if (seq (:selection/selected selection))
   "Mode selected, casting..."
   "Choose a mode")]
```

### 4. Add Explicit Confirm Button (Safety)
Even with auto-confirm, add a visible confirm button for users who expect it:
```clojure
[:button {:class confirm-btn-class
          :disabled? (not valid?)
          :on-click #(rf/dispatch [::selection-events/confirm-selection])}
 "Confirm"]
```

## Summary

The Vision Charm modal spell implementation appears correct based on code inspection. The flow properly:
1. Identifies it as a modal spell
2. Builds a spell-mode selection with auto-confirm
3. Shows the modal
4. Auto-confirms when a mode is selected
5. Routes to the spell casting flow with the chosen mode set on the object

If the issue persists, it's likely:
- A dispatch/event handling race condition
- The mode object not surviving the set round-trip
- Browser console errors blocking progression
- UI not reflecting the modal selection correctly
