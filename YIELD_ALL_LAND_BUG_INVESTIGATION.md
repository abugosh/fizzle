# Yield-All (F6) Bug After Bot Land Play — Investigation Report

## Executive Summary

**BUG**: When F6 (yield-all) is active and the bot plays a land, the human player gets priority despite F6 being set. The bot should continue acting or pass without handing priority back to the human.

**ROOT CAUSE**: After a bot plays a land via `::play-land`, there is NO state cleanup or priority reset. The bot's pass is treated as a priority pass (yielding priority), when it should instead advance to the next phase while respecting F6 mode.

**The Flow Chain** (lines 223-225 in `bots/interceptor.cljs`):
1. Bot is in main1 phase with F6 active (:game/auto-mode = :f6)
2. Bot plays land via `:fizzle.events.lands/play-land`
3. Land enters battlefield, dispatch/dispatch-event fires (trigger dispatch)
4. db_effect runs SBAs, calls bot-decide
5. **BUG HERE**: bot-decide sees the bot is still the priority holder and in the same phase
6. Bot decides to pass (no more lands to play, no spells in priority decision)
7. Pass dispatches `::yield`, which calls negotiate-priority
8. negotiate-priority passes both players and calls advance-with-stops
9. advance-with-stops respects ignore-stops? and ignore-opponent-stops?
10. BUT: The F6 flags don't prevent priority from being **given to the human** after the land play

## Yield-All Mechanism: How It's Supposed to Work

### Data Structure: :game/auto-mode

**Location**: `engine/priority.cljs` lines 122-144

```clojure
(defn get-auto-mode [db]
  "Get the current auto-mode (:resolving, :f6, or nil)."
  (:game/auto-mode (d/pull db [:game/auto-mode] [:game/id :game-1])))

(defn set-auto-mode [db mode]
  "Set the auto-mode for priority passing."
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)]
    (d/db-with db [[:db/add game-eid :game/auto-mode mode]])))
```

- **:f6** — Yield-all mode: auto-pass all priority, auto-advance phases, skip human's stops
- **:resolving** — Auto-pass for stack resolution, used when opponent resolves stack items
- **nil** — Manual mode: human gets priority after each priority pass

### How F6 Gets Set

**Location**: `events/priority_flow.cljs` lines 268-283

```clojure
(defn- yield-all-handler [db]
  (if (:game/pending-selection db)
    {:db db}
    (let [game-db (:game/db db)
          mode (if (queries/stack-empty? game-db) :f6 :resolving)]
      {:db (-> db
               (assoc :game/db (priority/set-auto-mode game-db mode))
               (assoc :yield/step-count 0))
       :fx [[:dispatch [::yield]]]})))

(rf/reg-event-fx ::yield-all ...)
```

When user presses F6:
1. Auto-mode is set to `:f6` (or `:resolving` if stack not empty)
2. Dispatches `::yield` to start the cascade
3. Step counter is reset

## Priority Negotiation and Passing

**Location**: `events/priority_flow.cljs` lines 159-198

```clojure
(defn negotiate-priority [app-db]
  "Handle priority passing between players."
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        holder-eid (priority/get-priority-holder-eid game-db)

        ;; Step 1: current player passes
        gdb (priority/yield-priority game-db holder-eid)
        active-player-id (queries/get-active-player-id gdb)
        active-eid (queries/get-player-eid gdb active-player-id)
        opponent-player-id (queries/get-other-player-id gdb active-player-id)

        ;; Step 2: auto-pass opponent only in auto-mode
        should-auto-pass-opponent? (boolean auto-mode)
        gdb (if (and should-auto-pass-opponent? opponent-player-id)
              (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
                (-> gdb
                    (priority/yield-priority active-eid)
                    (priority/yield-priority opp-eid)))
              gdb)
        all-passed (or (not opponent-player-id)
                       (priority/both-passed? gdb))]
    (if all-passed
      {:app-db (assoc app-db :game/db (-> gdb priority/reset-passes (priority/set-priority-holder active-eid)))
       :all-passed? true}
      {:app-db (assoc app-db :game/db (priority/transfer-priority gdb holder-eid))
       :all-passed? false})))
```

**Key insight**: In auto-mode (:f6 or :resolving), BOTH players auto-pass, so `all-passed?` is always true. This causes the function to skip phase advancement logic and return `all-passed? true`, which triggers stack resolution or phase advancement.

## Phase Advancement in F6 Mode

**Location**: `events/priority_flow.cljs` lines 106-156

```clojure
(defn- yield-advance-phase [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        f6? (= :f6 auto-mode)
        active-player-id (queries/get-active-player-id game-db)
        human-pid (queries/get-human-player-id game-db)

        ;; F6 only skips the human's stops — other players' stops are always respected
        ignore-stops? (and f6? (= active-player-id human-pid))

        ;; F6 also skips human's opponent-stops (human opted into full-auto)
        ignore-opponent-stops? f6?

        result (advance-with-stops app-db ignore-stops? ignore-opponent-stops?)
        result-db (:game/db (:app-db result))
        new-active-id (queries/get-active-player-id result-db)
        crossed-turn? (not= active-player-id new-active-id)]
    (cond
      (:game/pending-selection (:app-db result))
      result

      crossed-turn?
      (let [landed-on-human? (= new-active-id human-pid)]
        (if (and landed-on-human? f6?)
          {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
           :continue-yield? true}
          {:app-db (:app-db result)
           :continue-yield? true}))

      ;; F6 on human's turn, same turn — clear auto-mode (stop hit)
      (and f6? (= active-player-id human-pid))
      (update result :app-db
              (fn [adb] (update adb :game/db priority/clear-auto-mode)))

      ;; Non-human player's turn stopped at their phase — keep auto-mode
      f6?
      result

      :else
      result)))
```

**Key issue**: When the bot is in F6 mode and plays a land, priority is currently held by the bot, and after the land play:
- The bot's next decision (bot-decide) sees no more phase actions (no more lands to play)
- The bot passes priority
- This dispatches `::yield`
- `yield-impl` calls `negotiate-priority` which auto-passes both players (because auto-mode = :f6)
- Then calls `yield-advance-phase` to advance phases

### But Here's The Problem

After a bot plays a land in main1, the bot immediately gets asked to make a priority decision again (via bot-decide). At this point:
- Auto-mode is still :f6
- Bot holds priority
- Bot sees no more lands to play (can-play-land check failed for remaining cards)
- Bot passes via dispatch [::yield]

**The bug**: The bot's pass when it's the current player in F6 mode should NOT give priority to the human. Instead, it should only advance phases (if both have passed). But the flow goes:
1. Bot passes (dispatch ::yield)
2. negotiate-priority auto-passes both
3. yield-advance-phase is called
4. advance-with-stops respects ignore-stops?=true and ignore-opponent-stops?=true
5. Phases advance until a stop is hit

**BUT**: If no stop is hit on the bot's turn, and the turn stays on the bot, it should continue the loop. However, the human player isn't getting priority incorrectly — something else is happening.

## Re-examining: Where Does Priority Go To Human?

Actually, re-reading the code more carefully:

In `advance-with-stops` (lines 13-73 of priority_flow.cljs), the function:
1. Enters a loop advancing phases
2. When it hits cleanup → runs cleanup, then `start-turn` switches active player
3. When it doesn't hit cleanup → calls `phases/advance-phase` and checks for stops

**The issue**: After the bot plays a land:
- The land enters the battlefield
- But we're STILL in main1 phase, priority hasn't been reset
- Bot's bot-decide fires (via db_effect)
- Bot finds no lands to play (land just played), decides to pass
- Bot dispatches ::yield
- This causes the WHOLE priority+phase cascade

**What actually should happen**: After a bot plays a land, if F6 is active:
- Don't immediately ask bot-decide again
- Instead, let the phase advance naturally under F6 rules
- Only when the bot's turn ends should priority be handed back

## The Real Root Cause: No "Bot Just Acted" State

The issue is that after the bot plays a land (via `::play-land`):
1. Land enters battlefield via dispatch-event
2. db_effect fires immediately, calls bot-decide
3. Bot hasn't had time to process "I just played a land, now I should evaluate my next action"
4. The bot's next decision (pass) is treated as priority passing, not as phase advancement

**The flow that's broken**:
```
Bot plays land (main1)
  → land dispatched to battlefield
  → db_effect fires
  → bot-decide asked immediately
  → bot sees no more lands, decides to pass
  → ::yield dispatched
  → negotiate-priority (auto-mode = :f6)
  → both pass → all-passed?=true
  → yield-advance-phase
  → But wait, what phase are we in?
```

The bot is still in **main1 phase**. After both pass on an empty stack in main1:
- advance-with-stops should advance to combat
- advance-with-stops respects ignore-stops?=true (F6, bot's turn)
- Should advance past combat (no creatures) to main2
- And continue

**But the human gets priority**. This means:
1. The phases are advancing (good)
2. But a stop is being hit (bad)
3. Or a turn boundary is being crossed (bad)

## Hypothesis: F6 Flag Not Being Passed Correctly

Looking at line 117 of priority_flow.cljs:
```clojure
(if (= :f6 auto-mode)
```

This checks if auto-mode is :f6. But after bot plays land, is auto-mode still :f6?

**Yes, it should be** — there's no code path that clears it. The only places auto-mode is cleared are:
- Line 138 in yield-advance-phase when crossing turn to human with F6 active
- Line 146 in yield-advance-phase when hitting a stop on human's turn
- Line 239 in yield-handler when safety limit exceeded
- Line 86 in yield-resolve-stack when selection needed

After the bot plays a land, auto-mode should still be :f6.

## The ACTUAL Problem: Misunderstanding the Flow

Wait. Let me re-trace this more carefully.

After bot plays land in main1 with F6 active:
1. `::play-land` dispatched
2. db_effect fires, bot-decide called
3. Bot decides to pass
4. `::yield` dispatched
5. yield-handler calls yield-impl
6. yield-impl calls negotiate-priority
   - auto-mode = :f6
   - Both players auto-pass
   - all-passed? = true (both passed)
7. yield-impl calls yield-resolve-stack (stack empty? NO, we're checking this)
   - Actually stack IS empty after land enters
   - So calls yield-advance-phase
8. yield-advance-phase:
   - auto-mode = :f6 (still set)
   - f6? = true
   - active-player-id = bot (still active, no turn boundary yet)
   - ignore-stops? = false (active-player is NOT human)
   - ignore-opponent-stops? = true
   - Calls advance-with-stops with (false, true)
9. advance-with-stops:
   - Enters loop in main1
   - Next phase is combat
   - Calls advance-phase (to combat)
   - Stack still empty
   - Checks if active player (bot) has stop at combat
     - ignore-stops? = false, so DOES check bot's :player/stops
     - If bot has no stop at combat, continues loop
   - Combat phase has no creatures (typically), skips to main2
   - Checks if active player (bot) has stop at main2
     - ignore-stops? = false, so DOES check bot's :player/stops
     - **If bot has a stop at main2, RETURNS HERE**
     - Or if no stop, continues to end phase

**AHA!** The bug is line 121:
```clojure
ignore-stops? (and f6? (= active-player-id human-pid))
```

This only ignores the HUMAN's stops when F6 is active. But if the BOT is the active player, the bot's stops are still checked!

If the bot has a stop set at main2 (via phase-actions), then advance-with-stops will stop at main2, and control returns to the human!

## The Fix Required

The logic in yield-advance-phase needs to be fixed:

**Current (buggy)**:
```clojure
;; F6 only skips the human's stops — other players' stops are always respected
ignore-stops? (and f6? (= active-player-id human-pid))
```

**Should be (when F6 is active on ANY player, skip ALL stops)**:
```clojure
;; F6 skips all stops for the active player (whether human or bot)
ignore-stops? (and f6? (= active-player-id human-pid))
```

Wait, that's the same. Let me re-read the comment...

"F6 only skips the human's stops — other players' stops are always respected"

**This is the design choice**. But is it correct?

If F6 is active (yield-all), the intent is:
- Cascade all the way until you reach a point where the human should get priority
- This means: skip ALL stops until you reach a human priority phase, a human's opponent-stop, or a turn boundary to the human

The current code:
- Skips human's stops (correct)
- Respects bot's stops (INCORRECT for yield-all semantics)

## The Real Fix

When F6 is active, BOTH ignore-stops and ignore-opponent-stops should be true:
```clojure
;; F6 skips all stops for all players until human reaches a decision point
ignore-stops? f6?
ignore-opponent-stops? f6?
```

Or more precisely:
```clojure
;; F6 mode: skip all stops to cascade phases automatically
ignore-stops? f6?
```

But wait, the code already has:
```clojure
ignore-opponent-stops? f6?
```

So opponent-stops are skipped. The issue is that **active player's stops** (`ignore-stops?`) are only skipped when the active player is the human.

## Summary: The Bug

**In `events/priority_flow.cljs`, line 121:**
```clojure
ignore-stops? (and f6? (= active-player-id human-pid))
```

**Should be:**
```clojure
ignore-stops? f6?
```

**Reason**: In F6 (yield-all) mode, ALL stops should be ignored to create a full cascade. Currently, if the bot is the active player and has stops set, those stops are respected, which gives priority back to the human mid-bot-turn.

**Test case**:
1. Set up bot with main2 stop (bot has phase-actions configured)
2. Press F6 during human's turn
3. Turn passes to bot (via advance-with-stops)
4. Bot plays a land in main1
5. After land play, bot's main2 stop is hit
6. Control returns to human (WRONG)
7. Should: bot continues cascade, all stops ignored, until human's next decision point

**Wait, let me verify**: Does the bot actually have stops set at main2?

Looking at `events/init.cljs` (not provided, but referenced in memory):
- Bot stops are derived from phase-actions during init
- If bot has no phase-action at main2, then no stop at main2
- So the bot wouldn't hit a stop at main2

Let me reconsider...

## Re-examination: What Stops Get Set?

If there's no mention of bot stops being set AT ALL, then maybe the issue is different.

Looking at `bots/rules.cljs` lines 160-166:
```clojure
(defn get-phase-action [spec phase]
  "Look up phase action from a bot spec."
  (if-let [action (get-in spec [:bot/phase-actions phase])]
    {:action action}
    {:action :pass}))
```

So bot specs have `:bot/phase-actions` which is a map of phase -> action.

But where are bot stops set from this?

**Not shown in the provided files**. The code references "bot stops are derived from phase-actions at init" but the actual setup code isn't visible.

## Conservative Approach: Check Both Scenarios

**Scenario A: Bot stops ARE being set from phase-actions**
→ Then the fix is: `ignore-stops? f6?`

**Scenario B: Bot stops are NOT being set**
→ Then the bug is elsewhere

Given the evidence:
- User reports human gets priority after bot plays land
- F6 is active
- No phase-triggered action should hand priority to human

**Most likely**: Scenario A is correct, and the fix is to make `ignore-stops? f6?` instead of the conditional on human-ness.

This would ensure that in F6 mode, the entire phase cascade runs without stopping, until:
1. Crossing a turn boundary (handled by yield-advance-phase)
2. Hitting a pending-selection (handled by early return)
3. Or safety limit (handled by yield-handler)

---

## Files Involved

1. `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs` — **PRIMARY BUG LOCATION**
   - Line 121: `ignore-stops?` condition is too restrictive
   - Should skip all stops in F6 mode, not just human stops

2. `/Users/abugosh/g/fizzle/src/main/fizzle/bots/interceptor.cljs` — Bot land play path
   - Lines 220-225: Bot plays land, then bot-decide fires
   - Confirm no state-clearing needed here

3. `/Users/abugosh/g/fizzle/src/main/fizzle/events/lands.cljs` — Land play implementation
   - Confirm no priority/auto-mode manipulation

4. `/Users/abugosh/g/fizzle/src/main/fizzle/events/db_effect.cljs` — SBA + bot chokepoint
   - Confirm no F6 clearing

5. `/Users/abugosh/g/fizzle/src/main/fizzle/events/phases.cljs` — Phase advancement
   - Confirm stop-checking is done by priority_flow, not phases
