# Bot Stuck Investigation — Complete Trace

**Date**: 2026-03-27
**Scope**: Every code path where bot can get permanently stuck (no further events fire)

---

## TL;DR: Stuck States Found

A bot gets stuck when **`:game/pending-selection` is set but no mechanism resolves it**. There are FOUR scenarios where this can happen:

1. **Bot spell triggers targeting → pre-determined target passed → selection auto-confirms → but bot has action-count ≥20** → bot yields → selection never resolved
2. **Cleanup discard needed during bot's turn** → `begin-cleanup` returns `:pending-selection` → bot can't resolve cleanup discard (human-only)
3. **Combat phase: bot must choose attackers/blockers** → selection created → but no auto-confirm on combat selections → human UI required
4. **Storm count dialog or other resolution-time selections** → selection created during spell resolution → only resolved by UI toggle/confirm

---

## Detailed Trace

### Part 1: The Bot Loop (Entry Point)

**File**: `events/db_effect.cljs:25-52`

```clojure
;; TRIGGER: Any game-db mutation
;; GUARD: (and (not (:game/pending-selection final-app-db))
;;             (bot-interceptor/bot-should-act? sba-db))
;; ACTION: (rf/dispatch [::bot-interceptor/bot-decide])
```

**Key constraint**: If `:game/pending-selection` exists, `::bot-decide` is NEVER queued.

**File**: `bots/interceptor.cljs:137-181`

```clojure
::bot-decide handler checks:
  1. (not (:game/pending-selection app-db))  <- GUARD at line 146
  2. (not (bot-should-act? game-db))         <- GUARD at line 147
  3. (>= bot-action-count 20)                <- SAFETY LIMIT at line 148

If ANY of these is true → {:db (dissoc app-db :bot/action-count)
                           :fx [[:dispatch [:fizzle.events.priority-flow/yield]]]}
```

**Analysis**: This creates a potential deadlock. If a pending-selection is set AND the bot is the one who should resolve it, nothing triggers resolution.

---

### Part 2: Where Selections Can Be Created During Bot Turn

#### **Path 2a: Spell Casting with Targeting**

**File**: `events/casting.cljs:140-165` (`initiate-cast-with-mode`)

Pre-cast pipeline with steps:
- `:exile-cards-cost`
- `:return-land-cost`
- `:discard-specific-cost`
- `:sacrifice-permanent-cost`
- `:pay-x-life`
- `:x-mana-cost`
- **`:targeting`** ← Selection creation point
- **`:mana-allocation`** ← Selection creation point

**File**: `events/casting.cljs:93-125` (:targeting step implementation)

```clojure
(defmethod evaluate-pre-cast-step :targeting
  [_ {:keys [game-db player-id object-id mode target]}]
  ;; ...
  (if target
    ;; Line 103-110: Pre-determined target passed via opts
    (let [sel {:selection/player-id player-id
               :selection/object-id object-id
               :selection/mode mode
               :selection/target-requirement first-req
               :selection/selected #{target}}]
      {:db (sel-targeting/confirm-cast-time-target game-db sel)})  ← BYPASSES SELECTION

    ;; Line 111-125: Interactive targeting
    ;; Single player target → auto-cast (line 113-122)
    ;; Multiple targets → build selection (line 124-125) ← CREATES SELECTION
    ))
```

**CRITICAL FINDING**: When bot passes `{:target target}` in opts to `cast-spell`:
- **Single target only**: `confirm-cast-time-target` called directly, NO selection created ✓
- **But**: `targeting/find-valid-targets` is NEVER called first to validate the target exists
- If target is invalid or becomes illegal, silent failure (db unchanged, spell not cast)

**File**: `events/selection/targeting.cljs:43-78` (build-cast-time-target-selection)

```clojure
:selection/auto-confirm? true    <- Line 71: Targeting selections AUTO-CONFIRM
```

So if interactive targeting selection IS created (multiple valid targets), it has `auto-confirm? true`. But auto-confirm only fires AFTER toggle sets selection/selected (line 449-451 in selection/core.cljs).

#### **Path 2b: Spell Casting with Cost Selections**

**File**: `events/casting.cljs:38-42` (pre-cast pipeline steps)

Cost selections are created by:
- `:exile-cards-cost` → `build-exile-cards-selection` (sel-costs)
- `:return-land-cost` → `build-return-land-selection` (sel-costs)
- `:discard-specific-cost` → `build-discard-specific-selection` (sel-costs)
- `:sacrifice-permanent-cost` → `build-sacrifice-permanent-selection` (sel-costs)
- `:x-mana-cost` → `build-x-mana-selection` (sel-costs)
- `:mana-allocation` → `build-mana-allocation-selection` (sel-costs)

**All of these require human UI interaction** (toggle cards, adjust counters, confirm). Bot has no path to resolve them.

**STUCK STATE**: If bot spell has:
- `:card/alternate-costs` with `:sacrifice-permanent-cost`, `:return-land-cost`, `:discard-specific-cost`
- `:card/modes` with `:discard-hand` cost or `:pay-x-life`
- `:mode/mana-cost` with generic mana

Then casting triggers a pre-cast cost selection → `game/pending-selection` is set → no event fires to resolve it.

#### **Path 2c: Modal Spell Selection**

**File**: `events/casting.cljs:183-199` (build-spell-mode-selection)

```clojure
:selection/auto-confirm? true    <- Modal spells have auto-confirm
```

Modal spells that have valid modes get a spell-mode selection with `:auto-confirm? true`. The multimethod handler (line 202-209) executes immediately and clears the selection. ✓ Safe.

---

#### **Path 2d: Spell Resolution & Interactive Effects**

**File**: `events/resolution.cljs:63-108` (resolve-one-item)

When a stack-item resolves, `engine/resolution/resolve-stack-item` is called. If it returns `:needs-selection`, a selection is built and returned.

**File**: `events/resolution.cljs:104-105`

```clojure
(:needs-selection result)
(build-selection-from-result game-db controller top result)
```

**Selection types that can be created during resolution**:
- `:storm-split` (line 74-77) — storm count dialog
- `:needs-attackers` (line 79-94) — choose attackers (bot has `bot-choose-attackers` protocol, line 84)
- `:needs-blockers` (line 96-102) — choose blockers (human only)
- `(:needs-selection result)` (line 104) — various interactive effects

**STUCK STATE**: Bot casts spell → stack-item resolves → triggers interactive effect (e.g., `:return-from-graveyard`, `:scry`, `:peek-and-select`, `:draw` with :any-player) → selection created → only human UI can resolve it.

**Example**: Lightning Bolt cast by bot, but if resolution somehow triggers a library peek or graveyard selection, the bot can't complete the turn.

---

#### **Path 2e: Cleanup Phase**

**File**: `events/cleanup.cljs:47-72` (begin-cleanup)

```clojure
(if (pos? discard-count)
  ;; Need to discard - don't expire grants yet
  {:db db
   :pending-selection (build-cleanup-discard-selection player-id discard-count)}
  ;; No discard needed
  {:db (-> db
           (grants/expire-grants current-turn :cleanup)
           (clear-damage-marks))})
```

**File**: `events/cleanup.cljs:31-44` (build-cleanup-discard-selection)

```clojure
:selection/type :discard
:selection/lifecycle :finalized
:selection/auto-confirm? false    <- NO AUTO-CONFIRM
```

**STUCK STATE**: During bot's turn, if hand size > 7, `begin-cleanup` creates a discard selection with `auto-confirm? false`. This selection is only resolved by human UI (`toggle-selection` + `confirm-selection`). The bot can't provide input and the turn hangs.

**Trigger**: Any bot spell that draws cards (or any other effect filling hand) followed by yield.

---

### Part 3: Selection Resolution Mechanisms

#### **Auto-Confirm Path**

**File**: `events/selection/core.cljs:400-452` (toggle-selection-impl)

```clojure
(if (and selected?
         (= select-count 1)
         (:selection/auto-confirm? selection))
  (do (rf/dispatch [:fizzle.events.selection/confirm-selection])
      new-db)
  new-db)
```

When a single item is toggled AND `auto-confirm? true`:
1. `::confirm-selection` is dispatched (async, goes through event queue)
2. `confirm-selection-handler` validates and calls `confirm-selection-impl`
3. Selection is executed and cleared

**But**: This only fires when toggle happens (human UI clicks a card).

#### **Manual Confirm Path**

**File**: `events/selection/core.cljs:459-468` (confirm-selection-handler)

```clojure
(rf/reg-event-db
  ::confirm-selection
  (fn [db [_ _]]
    (confirm-selection-handler db)))
```

Only fired by:
- `toggle-selection-impl` (line 450) when auto-confirm is true
- Human UI clicking confirm button

---

### Part 4: Bot Action Safety Limit

**File**: `bots/interceptor.cljs:144-148`

```clojure
(let [game-db (:game/db app-db)
      bot-action-count (or (:bot/action-count app-db) 0)]
  (if (or (not game-db)
          (:game/pending-selection app-db)
          (not (bot-should-act? game-db))
          (>= bot-action-count 20))    <- SAFETY LIMIT
    {:db (dissoc app-db :bot/action-count)
     :fx [[:dispatch [:fizzle.events.priority-flow/yield]]]}
    ...
```

**Issue**: If bot has taken ≥20 actions, `::bot-decide` yields regardless of state. If a selection is pending, yield does nothing and the bot is stuck.

---

### Part 5: Yield & Phase Advancement

**File**: `events/priority-flow.cljs:140-200` (yield-advance-phase)

When bot yields:
1. If stack is non-empty → resolve items
2. If stack is empty → advance phases
3. During bot's turn → advance one phase per yield (line 156-184)
4. If pending-selection exists → return with pending-selection (line 164-165)

**STUCK STATE**: If cleanup discard is pending during bot's turn, `yield-advance-phase` returns with the pending-selection. No further events fire because:
- `::bot-decide` is blocked by the pending-selection guard
- No UI click events are happening (bot doesn't interact with UI)

---

## Summary: All Stuck Paths

| Path | Selection Type | Auto-Confirm? | Who Can Resolve? | Stuck Condition |
|------|---|---|---|---|
| **Pre-cast cost (exile/return/discard/sac/life)** | Cost selection | false | Human only | Bot spell has alternate/mode cost → pending selection set |
| **Pre-cast targeting (interactive)** | cast-time-targeting | true | Auto (toggle) | Multiple valid targets exist → selection created → no toggle happens |
| **Modal spell** | spell-mode | true | Auto-confirm | ✓ Safe (confirmed immediately) |
| **Spell resolution (interactive)** | storm-split, graveyard-return, tutor, etc. | varies | Mostly human | Spell effect needs selection → selection created → no UI |
| **Cleanup discard** | discard | false | Human only | Hand > 7 during bot's turn → selection created → can't discard |
| **Combat attackers** | attacker-selection | varies | Bot has protocol (line 84) | ✓ Bot protocol called, but returns `{:db}` sync not async |
| **Combat blockers** | blocker-selection | false | Human only | Bot as defender → selection created → can't block |

---

## Critical Code Locations

### Bot Loop Entrypoints
- **db_effect.cljs:50-52** — Only place `::bot-decide` is dispatched
- **bots/interceptor.cljs:146** — Guard: if pending-selection exists, bot yields instead

### Selection Creators
1. **casting.cljs:161** — Pre-cast cost selections
2. **casting.cljs:124** — Interactive targeting selections
3. **resolution.cljs:93** — Combat attacker/blocker selections
4. **resolution.cljs:104** — Spell resolution interactive effects
5. **cleanup.cljs:66** — Cleanup discard selection

### Selection Resolvers
- **toggle-selection-impl (core.cljs:449-451)** — Auto-confirm via dispatch
- **confirm-selection-handler (core.cljs:459-468)** — Manual confirm
- **confirm-selection-impl (core.cljs:369-393)** — Execution entry point

### Safety Limit
- **interceptor.cljs:148** — Action count ≥20 forces yield

---

## Specific Bot Stuck Scenarios

### Scenario 1: Bot Cleanup Discard (GUARANTEED STUCK)
1. Bot has >7 cards in hand
2. Yield is called (phase advance triggers cleanup)
3. `begin-cleanup` detects >7 and creates discard selection
4. Selection has `auto-confirm? false`
5. `::bot-decide` checks `(not (:game/pending-selection app-db))` at line 146 → FAILS
6. Bot yields (line 151)
7. `yield-advance-phase` sees pending-selection at line 164 → returns unchanged
8. No event fires to resolve discard
9. **Game hangs** — cleanup selection never confirmed

### Scenario 2: Bot Spell with Alternate Cost (GUARANTEED STUCK)
1. Bot decides to cast Dark Ritual (flashback mode needs `:pay-x-life`)
2. `cast-spell` initiated
3. `:pay-x-life` step in pre-cast pipeline (line 81-84)
4. `build-pay-x-life-selection` returns selection with `auto-confirm? false`
5. Selection is set on app-db
6. Pre-cast loop returns with `{:selection sel}`
7. `initiate-cast-with-mode` (line 160-162): pending-selection is set
8. `::bot-decide` next fires, checks guard at line 146 → FAILS
9. Bot yields
10. **Game hangs** — life payment selection never confirmed

### Scenario 3: Multi-Target Spell (STUCK IF NO AUTO-TOGGLE)
1. Bot casts Lightning Bolt with no `:target` in opts
2. Targeting step finds multiple valid targets (2+ players/creatures)
3. `build-cast-time-target-selection` is called (line 124-125)
4. Selection is created with `auto-confirm? true` (line 71)
5. Selection is set as pending
6. **Safe path**: If human clicks a target, toggle fires auto-confirm dispatch
7. **Stuck path**: If no human clicks AND bot doesn't have pre-determined target, selection hangs

### Scenario 4: Action Count Limit (FORCED YIELD + STUCK)
1. Bot takes 20 actions in a single turn
2. `::bot-decide` fires, sees action-count ≥20 (line 148)
3. Yields regardless of pending-selection (line 150-151)
4. If pending-selection exists, yield can't clear it
5. **Game hangs** — infinite yield loop with pending-selection

---

## Recommendations for Investigation

To find the actual stuck state in production:

1. **Check app-db when stuck**:
   ```clojure
   (js/console.log (:game/pending-selection @rf/app-db))
   ```
   If not nil, which selection type?

2. **Check bot state**:
   ```clojure
   (js/console.log (queries/get-priority-holder-eid (:game/db @rf/app-db)))
   (js/console.log (:player/bot-archetype (player from above)))
   ```
   Is the bot holding priority?

3. **Check action count**:
   ```clojure
   (js/console.log (:bot/action-count @rf/app-db))
   ```
   Is bot at ≥20 actions?

4. **Add logging to db_effect.cljs:50**:
   ```clojure
   (when (not (bot-interceptor/bot-should-act? sba-db))
     (js/console.log "Bot should not act. Selection:" (:game/pending-selection final-app-db)))
   ```

5. **Check if selection is resolvable**:
   - Storm split: requires toggle → numbers → confirm
   - Cleanup discard: requires toggle → cards → confirm
   - Targeting: requires toggle → target → confirm (with auto-confirm)
   - Costs: requires toggle → items → confirm

---

## Code Changes Needed to Fix

1. **Add bot-aware cleanup discard**:
   - During bot's cleanup, if >7 cards, auto-select/discard random cards instead of creating selection

2. **Add bot-aware cost selections**:
   - Pre-cast cost selections need to detect bot player and either skip or auto-resolve

3. **Add bot-aware targeting**:
   - Bot must provide `:target` in opts when casting, OR targeting step must detect bot and use bot protocol

4. **Add pending-selection timeout**:
   - If pending-selection exists AND bot can't resolve it for N turns, forcibly clear and continue

5. **Prevent safety limit bypass**:
   - If action-count ≥20 AND pending-selection, mark bot as stuck instead of silent yield
