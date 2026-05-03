# Quick Bot Stuck Debug Checklist

## When Bot Gets Stuck

Open browser console and run:

```javascript
// 1. What selection is pending?
console.log("Pending selection:", re_frame.core.subscribe(["game/pending-selection"])[@.deref]());

// 2. Who holds priority?
const db = re_frame.core.subscribe(["game/db"])[@.deref]();
const holderId = /* priority/get-priority-holder-eid db */;

// 3. Is the priority holder a bot?
const botArchetype = /* player entity with holderId */;
console.log("Bot archetype:", botArchetype);

// 4. What's the action count?
console.log("Action count:", re_frame.core.subscribe(["app-db"])[@.deref]().bot_action_count);
```

**Quick map** of stuck condition:

| Pending Selection Type | Resolvable by Bot? | Location |
|---|---|---|
| `:discard` + `cleanup? true` | NO | `events/cleanup.cljs:31-44` |
| `:pay-x-life` | NO | `events/selection/costs.cljs` (builder) |
| `:exile-cards-cost` | NO | `events/selection/costs.cljs` |
| `:return-land-cost` | NO | `events/selection/costs.cljs` |
| `:discard-specific-cost` | NO | `events/selection/costs.cljs` |
| `:sacrifice-permanent-cost` | NO | `events/selection/costs.cljs` |
| `:cast-time-targeting` | YES (if 1 item selected) | `events/selection/targeting.cljs:71` (auto-confirm: true) |
| `:spell-mode` | YES | `events/casting.cljs:196` (auto-confirm: true) |
| `:attacker-selection` | YES (bot protocol) | `events/resolution.cljs:81-90` |
| `:blocker-selection` | NO | `events/resolution.cljs:96-102` |
| `:storm-split` | NO | `events/selection/storm.cljs` |
| `:tutor`, `:scry`, etc. | NO | `events/selection/{library,zone_ops}.cljs` |

---

## The Bot Stuck Loop

```
db_effect.cljs:50 checks: (not (:game/pending-selection final-app-db))
    ↓
    if pending-selection exists → ::bot-decide is NOT dispatched ✗
    ↓
bots/interceptor.cljs:173 (bot-decide-action never called)
    ↓
No action is generated
    ↓
interceptor.cljs:150-151: {:db (dissoc app-db :bot/action-count)
                           :fx [[:dispatch [:fizzle.events.priority-flow/yield]]]}
    ↓
priority-flow.cljs:140-200 (yield-advance-phase)
    ↓
    if pending-selection exists → return with pending-selection (line 164-165)
    ↓
events/selection/core.cljs neither toggle nor confirm happens
    ↓
No resolution possible
    ↓
STUCK 🔴
```

---

## Fix Locations by Category

### Fix 1: Cleanup Discard (Easiest)
- **File**: `events/cleanup.cljs:31-72` (begin-cleanup, build-cleanup-discard-selection)
- **Problem**: Creates `:discard` selection with `auto-confirm? false` during bot's turn
- **Fix**: Check if player is bot; if yes, auto-select and discard random/oldest cards instead of creating selection
- **Impact**: Prevents cleanup hang; game continues

### Fix 2: Pre-Cast Cost Selections (Medium)
- **File**: `events/selection/costs.cljs` (all cost builders)
- **Problem**: Cost selections created without bot-awareness; bot can't toggle/confirm
- **Fix**: Add bot detection to casting pipeline; skip cost selection or auto-resolve with bot logic
- **Note**: Requires knowing bot's cost-resolution strategy (pay all x-life? sacrifice any? etc.)

### Fix 3: Targeting with No Pre-Determined Target (Hard)
- **File**: `events/casting.cljs:93-125` (evaluate-pre-cast-step :targeting)
- **Problem**: When bot doesn't provide `:target` in opts and multiple targets exist, selection created (has auto-confirm but no toggle)
- **Fix**: Bot must ALWAYS pass `:target` in opts when casting targeted spells, OR targeting step must detect bot and call bot protocol
- **Impact**: Requires bot protocol to return target for any spell

### Fix 4: Combat: Blockers (Medium)
- **File**: `events/resolution.cljs:96-102` (needs-blockers)
- **Problem**: No bot protocol for blockers; selection created without auto-resolution
- **Fix**: Add `bot-choose-blockers` protocol method (similar to `bot-choose-attackers` at line 84)

### Fix 5: Combat: Attackers (Already Safe)
- **File**: `events/resolution.cljs:79-90`
- **Status**: ✓ Bot protocol called synchronously (line 84), selection confirmed immediately (line 89)
- **Safe**: This path does NOT create a hanging selection

### Fix 6: Safety Limit (Low Priority)
- **File**: `bots/interceptor.cljs:144-148`
- **Problem**: Action count ≥20 forces yield even with pending-selection
- **Fix**: Add logging/warning when bot reaches limit with pending-selection; don't silent-yield

---

## Verification Commands

### Is selection blocking bot?
```clojure
(not (:game/pending-selection (re-frame.core/subscribe [:db])))
;; Returns false if selection is pending
```

### Who should resolve it?
```clojure
(let [sel (:game/pending-selection db)
      sel-type (:selection/type sel)
      auto-confirm? (:selection/auto-confirm? sel)]
  (if auto-confirm?
    "Auto-confirm enabled (human toggle needed to fire it)"
    "Requires human confirm"))
```

### Is bot the priority holder?
```clojure
(bot-interceptor/bot-should-act? game-db)
;; Returns true if bot holds priority
```

---

## Why Each Path Gets Stuck

1. **Cleanup discard**: Created by `begin-cleanup` (called during phase transition), no bot logic knows how to discard
2. **Cost selections**: Created by pre-cast pipeline, pipeline has no bot path, only human UI
3. **Interactive targeting**: Created during casting if multiple targets, auto-confirm only fires on toggle (human UI)
4. **Spell resolution effects**: Created during resolution, effect system has no bot dispatch for tutor/scry/etc.
5. **Blockers**: Combat phase, blocker selection created, no bot protocol exists

---

## Test Case: Minimal Bot Stuck Reproduction

**To verify Fix 1 (cleanup discard)**:

1. Create game with bot opponent (burn bot has 20 Mountains + 40 Bolts)
2. Human hand-sculpt to have 7 cards + large deck
3. Take turns until bot's cleanup phase
4. If human has >7 cards at end of bot's turn → `begin-cleanup` creates discard selection
5. Game hangs (if fix not applied)

**To verify Fix 3 (targeting with no pre-determined target)**:

1. Create game with bot opponent
2. Create custom bot that casts Lightning Bolt without `:target` in opts
3. Ensure ≥2 valid targets exist (opponent player + creature)
4. Bot decides to cast Lightning Bolt
5. Targeting selection created (has `auto-confirm? true` but no toggle happens)
6. Game hangs (no confirmation event)

---

## Files to Add Logging

Add console.log in these locations when bot gets stuck:

**events/db_effect.cljs:50**
```clojure
(when (not (:game/pending-selection final-app-db))
  ;; dispatch ::bot-decide
  )
;; Add ELSE:
(when (:game/pending-selection final-app-db)
  (js/console.warn "🔴 Bot stuck: pending-selection blocks ::bot-decide"
                   {:selection-type (-> final-app-db :game/pending-selection :selection/type)
                    :bot-should-act (bot-interceptor/bot-should-act? sba-db)}))
```

**bots/interceptor.cljs:146**
```clojure
(if (:game/pending-selection app-db)
  (do (js/console.warn "🔴 Bot yielding due to pending selection"
                       (:selection/type (:game/pending-selection app-db)))
      ;; yield
      ))
```

---

## Summary

**Stuck condition**: `:game/pending-selection` is set during bot's turn + no bot-aware resolution mechanism exists

**Most likely culprit**: Cleanup discard during bot turn (cleanup phase always happens, hand size >7 is common)

**Quick fix**: Add cleanup discard handler for bots in `events/cleanup.cljs:31-72`
