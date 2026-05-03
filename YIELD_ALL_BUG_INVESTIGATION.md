# Yield-All / F6 Behavior Investigation

**Investigation Date:** 2026-03-30

## Bug Report Summary

**Current Behavior:** The "yield all" button (F6 mode) ONLY does F6 behavior (advance through phases, ignoring stops) regardless of whether the stack is empty or full.

**Expected Behavior:**
- When stack is **non-empty**: yield-all should resolve all stack items, THEN enter F6 mode to advance through phases
- When stack is **empty**: yield-all should enter F6 mode (phase advancement ignoring stops)

---

## Implementation Analysis

### 1. Entry Point: UI Button (views/controls.cljs:81-85)

```clojure
[:button {:class (btn-class true)
          :on-click #(rf/dispatch [::priority-flow-events/yield-all])}
 (if (seq stack)
   (str "Yield All (" (count stack) ")")
   "Yield All")]
```

Button UI correctly counts stack items in label, but both states dispatch the same event.

### 2. Event Handler (events/priority_flow.cljs:71-76)

```clojure
(rf/reg-event-fx
  ::yield-all
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (apply-director-result
        (director/run-to-decision db {:yield-all? true})))))
```

**BUG LOCATION HERE**: Always passes `{:yield-all? true}` to director, regardless of stack state.

### 3. Director Logic (events/director.cljs:43-57)

The director's `human-should-auto-pass` function:

```clojure
(defn human-should-auto-pass
  "Determine if human player should auto-pass at current game state.
   Returns true if auto-pass, false if await input.

   Rules:
   - yield-all? true: always auto-pass (F6 mode)
   - Stack is non-empty: always auto-pass (stops only prevent phase advancement)
   - Stack is empty: auto-pass if current phase is NOT in player's stops"
  [game-db _player-eid stops yield-all?]
  (cond
    yield-all? true
    (not (queries/stack-empty? game-db)) true
    :else (not (contains? stops (:game/phase (queries/get-game-state game-db))))))
```

This function correctly handles the logic:
- If `yield-all?` is true → always auto-pass (F6)
- Else if stack non-empty → always auto-pass (resolve items)
- Else → check stops

### 4. Stack Resolution Path (events/director.cljs:235-249)

```clojure
(defn- step-resolve-stack
  [app-db game-db yield-all?]
  (let [result (resolution/resolve-one-item game-db)]
    (if (:pending-selection result)
      {:done {:app-db (-> app-db ...
      (let [resolved-db (sba/check-and-execute-sbas (:db result))
            new-app-db (cleanup/maybe-continue-cleanup (assoc app-db :game/db resolved-db))]
        (if (:game/pending-selection new-app-db)
          {:done {:app-db new-app-db :reason :pending-selection}}
          (if yield-all?
            {:continue new-app-db :yield-all? yield-all?}  ; Continue resolving
            {:done {:app-db new-app-db :reason :await-human}}))))))  ; Stop after one
```

**KEY POINT**: When `yield-all?` is true, director continues looping after each resolution (line 248). When false, director stops (line 249).

### 5. Phase Advancement with Turn Boundary (events/director.cljs:252-267)

```clojure
(defn- step-advance-phase
  [app-db game-db active-pid yield-all?]
  (let [advance-result (advance-one-phase game-db active-pid)]
    ...
    (let [advanced-db (:game-db advance-result)
          crossed-turn? (:crossed-turn? advance-result)
          new-active-pid (queries/get-active-player-id advanced-db)
          human-pid game-state/human-player-id
          new-yield-all? (if (and yield-all? crossed-turn? (= new-active-pid human-pid))
                           false  ; Disable F6 when returning to human's turn
                           yield-all?)]
      {:continue (assoc app-db :game/db advanced-db) :yield-all? new-yield-all?})))
```

**CRITICAL BUG BEHAVIOR HERE**: When turn boundary is crossed and control returns to human, `yield-all?` is reset to `false` (line 264-265). This is correct for F6 (we should stop at human's next turn stop), but it means the director never cascades through the entire sequence.

---

## Test Evidence

### Expected Behavior Tests

**Test: `yield-all-resolves-entire-stack` (priority_test.cljs:188-204)**

```clojure
(deftest yield-all-resolves-entire-stack
  (testing "yield-all with non-empty stack resolves all items"
    (let [scenario (setup-app-db {:mana {:black 2} :stops #{:main1 :main2}})
          ;; Cast 2 Dark Rituals on stack
          result (dispatch-yield-all app-db)]
      ;; yield-all (F6 mode) resolves all stack items then cascades through the
      ;; entire turn cycle (player T1 -> opponent T2 -> player T3 main1).
      (is (empty? (q/get-all-stack-items (:game/db result)))
          "Stack should be empty after yield-all"))))
```

**Expected**: Stack resolves completely, then advances through entire turn cycle

**Test: `yield-all-empty-stack-f6-advances-to-new-turn` (priority_test.cljs:207-214)**

```clojure
(deftest yield-all-empty-stack-f6-advances-to-new-turn
  (testing "yield-all with empty stack enters F6 mode, advances through turn and opponent turn"
    (let [app-db (merge (history/init-history) (setup-app-db))
          result (dispatch-yield-all app-db)]
      ;; F6 ignores player stops, advances through player turn, opponent turn, to next player turn
      (is (= 3 (:game/turn (q/get-game-state (:game/db result))))
          "Should advance to turn 3 (player T1 -> opponent T2 -> player T3)"))))
```

**Expected**: With empty stack, should enter F6 and advance through turns

---

## Root Cause

The `::yield-all` event handler (events/priority_flow.cljs:71-76) has **insufficient logic**:

**Current implementation:**
```clojure
(rf/reg-event-fx
  ::yield-all
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (apply-director-result
        (director/run-to-decision db {:yield-all? true})))))  ; ALWAYS true
```

**What should happen:**
1. Check if stack is empty
2. If stack non-empty: set `{:yield-all? true}` to cascade through all resolutions
3. If stack empty: set `{:yield-all? true}` to enter F6 mode (advance phases ignoring stops)

However, both cases should pass `{:yield-all? true}` to the director. The director correctly handles both:
- Non-empty stack: resolves items repeatedly until stack empty (line 248)
- Empty stack: advances phases with auto-pass (human-should-auto-pass with `yield-all?=true`)

**So the current implementation is actually CORRECT in passing `{:yield-all? true}`.**

---

## Actual Problem: Turn Boundary Behavior

The real issue is in the **turn boundary reset logic** (director.cljs:264-265):

```clojure
new-yield-all? (if (and yield-all? crossed-turn? (= new-active-pid human-pid))
                 false  ; BUG: Resets F6 when returning to human!
                 yield-all?)
```

**What this does:**
- When `yield-all?=true` AND turn crosses AND human is now active → set `yield-all?=false`
- This means after resolving opponent's turn, F6 mode disables

**The problem:**
- User presses "yield all" expecting F6 through the entire turn cycle
- Director resolves all stack items (correct)
- Director crosses to opponent's turn (correct)
- Opponent plays their turn (correct)
- But when returning to human's turn, `yield-all?` is reset to `false`
- So director stops at human's first stop, not continuing in F6 mode

---

## Evidence in Tests

Test `yield-all-empty-stack-f6-advances-to-new-turn` (line 207) expects:
- `yield-all` on empty stack → advance to turn 3 (player T1 → bot T2 → player T3)

This test **should PASS** if the director is working correctly because:
1. Stack is empty
2. Director starts at human main1
3. With `yield-all?=true`, human auto-passes all phases
4. Human's turn ends, control goes to opponent
5. Opponent (bot) completes its turn
6. Control returns to human...
7. **BUT `yield-all?` is reset to false at line 264**
8. So human gets stopped at main1 again instead of entering F6 mode

---

## The Correct Fix

The turn boundary reset logic should be **removed or reconditional**:

**Option 1: Never reset `yield-all?` (True F6)**
```clojure
new-yield-all? yield-all?
```
This makes F6 truly cascade through the entire turn cycle, which may be the intended behavior.

**Option 2: Reset only after clearing stops (Bounded F6)**
```clojure
new-yield-all? (if (and yield-all? crossed-turn? (= new-active-pid human-pid))
                 ;; Check if human has any stops remaining
                 (not (seq (get-player-stops game-db human-eid)))
                 yield-all?)
```
This would continue F6 if human has cleared all their stops, but resume stops if they have any set.

**Option 3: Document and keep current behavior (F6 one turn only)**
If the current behavior is intentional (F6 one player's turn + opponent's turn, then stop at next human turn), document it clearly in the code.

---

## Summary

**The "yield all" button works correctly for its primary function:**
- Resolves the entire stack via `yield-all?=true` to the director
- Enters F6 mode to auto-pass phase stops

**The bug is subtle:**
- When F6 crosses a turn boundary back to human player, the `yield-all?` flag is reset to `false`
- This causes the director to stop at human's next stop instead of continuing in F6 mode
- User expectation: "yield all" keeps cascading until all stops are passed through the full turn cycle

**Files involved:**
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs:71-76` — Event handler (logic correct)
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/director.cljs:264-265` — Turn boundary reset (likely bug source)
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/priority_test.cljs:207-214` — Test expects full turn cycle advance
