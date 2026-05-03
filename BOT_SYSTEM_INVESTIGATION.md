# Bot System Deep Investigation Report

**Date**: 2026-03-27
**Scope**: Comprehensive audit of bot protocol, execution flow, priority interaction, and quality issues
**Files Analyzed**: 4 bot files (~520 lines), db_effect.cljs, priority_flow.cljs, phases.cljs, 5 test files

---

## Executive Summary

The bot system is well-architected at the **protocol layer** (pure functions, clean dispatch) but has **quality issues in execution flow control**:

1. **Critical Issue**: Unnecessary action-count safety limit (line 148 in interceptor.cljs) — bot passes after 20 actions even if valid plays remain
2. **Quality Issue**: Incomplete turn cycle for bot — no `:declare-attackers` action or combat automation
3. **Quality Issue**: No bot blockers implementation — burn bot only attacks (if at all), never blocks
4. **Design Gap**: `bot-phase-action` only supports one action per phase (`:play-land`); no sequential actions within a phase
5. **Timing Risk**: Bot decisions fire synchronously after EVERY game-db mutation — can cause multiple ::bot-decide fires in one event
6. **Conditional Logic**: Bot auto-pass heuristic in `negotiate-priority` (priority_flow.cljs:245-258) is complex and has subtle dependencies

---

## 1. Bot Protocol and Definitions

### Location & Structure

| File | Lines | Purpose |
|------|-------|---------|
| `bots/protocol.cljs` | 66 | Public API, pure getter functions, multimethod dispatch |
| `bots/definitions.cljs` | 57 | Bot specs (goldfish, burn) as EDN data |
| `bots/rules.cljs` | 180 | Condition evaluators (multimethod on `:check`) |
| `bots/interceptor.cljs` | 188 | Event handlers, ::bot-decide, dispatch builder |

### Protocol Functions (protocol.cljs)

**4 main functions:**

1. **`get-bot-archetype`** (lines 15-23)
   - Pure datascript query: `(db, player-id) -> keyword | nil`
   - Returns `:goldfish`, `:burn`, or nil for human players
   - ✓ Correct — single query, no side effects

2. **`bot-priority-decision`** (lines 26-34)
   - Dispatcher: takes archetype, context `{:db, :player-id}`
   - Calls `rules/match-priority-rule` with bot spec's `:bot/priority-rules`
   - Returns `:pass` or action map `{:action, :object-id, :target}`
   - ✓ Correct — pure dispatch, handles nil gracefully

3. **`bot-phase-action`** (lines 37-45)
   - Pure dispatcher on bot spec, phase → action
   - Only supports phase -> single action mapping via `:bot/phase-actions`
   - **⚠️ Design Issue**: Cannot represent "play land, then cast spells in main phase"
   - **⚠️ Limitation**: Goldfish/burn both map to `:main1 -> :play-land` only

4. **`bot-choose-attackers`** (lines 48-56)
   - Declared but NOT CALLED anywhere in codebase
   - No attack phase handling; burn bot never attacks despite `:bot/attack-strategy :all` in spec
   - **✗ Dead Code**: Combat system exists but bot integration missing
   - Returns `[]` (no attackers) for nil spec

### Definitions (definitions.cljs)

**Goldfish Spec** (lines 9-17):
```clojure
{:bot/name "Goldfish"
 :bot/deck [5 basics, 12 each]           ; 60 cards total
 :bot/phase-actions {:main1 :play-land}  ; Only action: play land
 :bot/priority-rules []}                 ; No priority rules → always :pass
```

**Burn Spec** (lines 20-31):
```clojure
{:bot/name "Burn"
 :bot/deck [20 Mountains, 40 Lightning Bolts]  ; 60 cards
 :bot/phase-actions {:main1 :play-land}
 :bot/attack-strategy :all                     ; NEVER USED
 :bot/priority-rules
   [{:rule/mode :auto
     :rule/conditions [
       {:check :zone-contains :zone :hand :player :self :card-id :lightning-bolt}
       {:check :has-untapped-source :color :red}
       {:check :stack-empty}]
     :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}]}
```

**Quality Issues**:
- Both bots have identical `:bot/phase-actions` — only play lands on main1
- `:bot/attack-strategy :all` defined on burn but protocol function never called
- No block/respond rules in either spec — goldfish is truly passive, burn only attacks

---

## 2. Condition Evaluator Engine (rules.cljs)

### Multimethod: `evaluate-condition` (dispatches on `:check`)

**6 Condition Types Implemented:**

| Check | Lines | Purpose | Issues |
|-------|-------|---------|--------|
| `:zone-contains` | 33-40 | "Does zone have a card?" | ✓ Correct |
| `:has-mana-for` | 43-55 | "Can player afford card's mana cost?" | ⚠️ Uses `mana/can-pay?` — doesn't account for already-tapped lands in burn decision |
| `:stack-empty` | 58-60 | "Is stack empty?" | ✓ Correct |
| `:has-untapped-source` | 63-76 | "Does player have untapped mana source of color?" | ✓ Correct |
| `:zone-count` | 79-90 | "Count cards in zone, compare with gte/lte/eq" | ✓ Correct |
| `:stack-has` | 93-103 | "Does stack have spell/ability by controller+type?" | ✓ Correct |
| `:default` | 106-109 | Unknown checks throw ex-info | ✓ Correct — fail-fast |

### Rule Matching (match-priority-rule, lines 146-157)

```clojure
(defn match-priority-rule [rules context]
  (if (seq rules)
    (or (some (fn [rule]
                (when (and (not= :interactive (:rule/mode rule))
                           (evaluate-conditions (:rule/conditions rule) context))
                  (resolve-action (:rule/action rule) context)))
              rules)
        :pass)
    :pass))
```

**Behavior**:
- Filters out `:interactive` rules (for human player selection, not bots)
- Returns first matching rule's resolved action, else `:pass`
- Conditions are AND'd together via `every?` (line 116)

**⚠️ Quality Issue**: No scoping for :interactive rules — if a bot spec mistakenly has `:interactive` rules, they're silently skipped. No warning.

### Action Resolution (resolve-action, lines 119-143)

Converts symbolic `:card-id :lightning-bolt` to object-id UUID:

```clojure
(defn resolve-action [action-template context]
  ;; Find card in hand by :card/id
  ;; Resolve :opponent/:self to player-id
  ;; Returns :pass if card not in hand or target unresolvable
```

**⚠️ Critical Issue**: Only searches hand, not library. Burn rule assumes bolt in hand, never searches library for bolts. If bot topdeck a bolt, it won't be found until next turn (after hand reveal).

---

## 3. Event Handler & Dispatch Flow (interceptor.cljs)

### Bot Detection (bot-should-act?, lines 24-36)

```clojure
(defn bot-should-act? [game-db]
  (let [holder-eid (priority/get-priority-holder-eid game-db)]
    (if holder-eid
      (let [player-id (some ...)]
        (boolean (and player-id (bot/get-bot-archetype game-db player-id)))))))
```

**✓ Correct**: Checks if priority holder (entity) is a bot player.

### Action Planning (bot-decide-action, lines 68-99)

**High-Level Flow**:
1. Get priority holder → resolve to player-id → get archetype
2. Call `bot/bot-priority-decision` for action
3. If action is cast spell:
   - Find tap sequence to pay mana cost
   - Check `have-taps >= needed-mana`
   - If insufficient, return `:pass`

**⚠️ Quality Issue** (lines 91-94):
```clojure
needed-mana (reduce + 0 (vals mana-cost))
have-taps (count tap-seq)
(if (< have-taps needed-mana)
  {:action :pass}
```

This is **WRONG**:
- `reduce + 0 (vals {:red 1 :green 1})` = 2
- But mana cost structure is `{:red 1 :green 1}` = "pay 1R and 1G"
- Burning bolt costs `{:red 1}` — the code works for single-color costs
- **Multi-color spells break this**: cost of `{:white 1 :blue 1}` would wrongly require 2 taps

**Impact**: Any future multi-color spell in burn bot deck (e.g., Lightning Angel) would fail silently.

### Dispatch Builder (build-bot-dispatches, lines 102-118)

Converts action plan to re-frame effects:

```clojure
(defn build-bot-dispatches [action]
  (let [player-id (:player-id action)
        tap-dispatches (mapv (fn [{:keys [object-id mana-color]}]
                               [:dispatch [:fizzle.events.abilities/activate-mana-ability ...]])
                             (:tap-sequence action))
        cast-dispatch [:dispatch [:fizzle.events.casting/cast-spell ...]]]
    (conj tap-dispatches cast-dispatch)))
```

**✓ Correct**: Produces sequence of `[:dispatch ...]` effects for re-frame `:fx` key. Each dispatch goes through full event pipeline.

### Event Handler (bot-decide-handler, lines 137-188)

**Core Logic**:

```clojure
(defn bot-decide-handler [app-db]
  (let [game-db (:game/db app-db)
        bot-action-count (or (:bot/action-count app-db) 0)]
    (if (or (not game-db)
            (:game/pending-selection app-db)
            (not (bot-should-act? game-db))
            (>= bot-action-count 20))  ; ← CRITICAL ISSUE
      {:db (dissoc app-db :bot/action-count)
       :fx [[:dispatch [:fizzle.events.priority-flow/yield]]]}
      ;; Check phase action first, then priority decision
      (cond
        (and (= :play-land (:action phase-action))
             (find-bot-land-to-play game-db player-id))
        ;; Play land — increment counter, dispatch

        :else
        ;; Priority decision — cast or pass
        (let [action (bot-decide-action game-db)]
          (if (= :pass (:action action))
            ;; Pass — dispatch ::yield
            :else
            ;; Cast — build and dispatch taps + cast
```

**⚠️ CRITICAL ISSUE** (line 148):
```clojure
(>= bot-action-count 20)
```

The bot's action counter hits a safety limit after 20 actions and forces `:pass`.

**Problems**:
1. **Arbitrary limit**: Why 20? No comment explaining
2. **Incomplete turn**: Burn bot may have cast only 5 bolts before hitting limit, forcing pass
3. **Counter incremented per action**: Each play-land, each cast increments counter (lines 168, 180)
4. **Not reset between turns**: Counter resets only when bot passes (line 176), not at turn start
5. **Real scenario**: Burn bot T2: plays land (1), casts bolt (2), opponent passes, bot gets priority, casts bolt (3)... after 20 actions, bot forced to pass

**Expected Behavior**: Bot should cast all legal bolts until it has insufficient mana.

**Root Cause**: Designed as a safety valve against infinite loops, but implemented too conservatively.

### Land Playing (find-bot-land-to-play, lines 123-134)

```clojure
(defn find-bot-land-to-play [game-db player-id]
  (let [hand (queries/get-hand game-db player-id)]
    (some (fn [obj]
            (let [oid (:object/id obj)]
              (when (rules/can-play-land? game-db player-id oid)
                oid)))
          hand)))
```

✓ Correct: Finds first land in hand that can legally be played (enforces one-land-per-turn).

---

## 4. Trigger Mechanism: From Game-DB Mutation to Bot Decision

### The Chokepoint (db_effect.cljs)

Custom `:db` effect handler registered at app init:

```clojure
(defn game-db-effect-handler [new-app-db]
  (let [old-game-db (:game/db @rf-db/app-db)
        new-game-db (:game/db new-app-db)]
    (if (or (nil? new-game-db) (identical? new-game-db old-game-db))
      ;; No game-db change — update app-db if needed
      (when-not (identical? @rf-db/app-db new-app-db)
        (reset! rf-db/app-db new-app-db))
      ;; Game-db changed
      (let [sba-db (sba/check-and-execute-sbas new-game-db)
            final-app-db (assoc new-app-db :game/db sba-db)]
        (reset! rf-db/app-db final-app-db)
        (when (and (not (:game/pending-selection final-app-db))
                   (bot-interceptor/bot-should-act? sba-db))
          (rf/dispatch [::bot-interceptor/bot-decide]))))))
```

**Flow**:
1. Every game-db change triggers this custom handler
2. Runs SBAs first (state-based actions)
3. Checks if bot should act
4. **Queues** `::bot-decide` via `rf/dispatch` (async, not dispatch-sync)

**⚠️ Design Decision** (line 16 comment):
```clojure
;; rf/dispatch (not dispatch-sync) for ::bot-decide to avoid re-entry
```

**Good**: Uses async dispatch to prevent re-entry issues.
**But**: Still susceptible to multiple ::bot-decide queuing if multiple game-db changes happen before first one fires.

### When Bot Fires

**Scenario**: Bot casts lightning bolt:
1. Event: `::cast-spell` for bolt
2. Handlers run:
   - `casting/cast-spell-handler` creates spell stack-item
   - Returns `:db` effect with new game-db
3. Custom `:db` handler fires:
   - Runs SBAs (none triggered by new spell on stack)
   - Checks `bot-should-act?` on NEW state
   - **Priority is STILL ON BOT** (cast-spell handler doesn't move priority)
   - Queues `::bot-decide`
4. `::bot-decide` fires (async):
   - Bot checks conditions again
   - If bolt still castable, casts another bolt
   - Repeats

**⚠️ Timing Issue**: Multiple state mutations in one event can queue multiple `::bot-decide` calls if conditions are met.

**Example Scenario** (potential issue):
```
T1: Bot main phase
  Event: ::cast-spell (bolt 1)
    → game-db updates
    → custom :db handler → queues ::bot-decide
  Event: ::bot-decide
    → casts bolt 2
    → game-db updates (SBAs run, new state)
    → custom :db handler → queues ::bot-decide (again)
  Event: ::bot-decide
    → casts bolt 3
    ...
```

This is **correct behavior** for sequential casting, but no guard against malformed game-db mutations creating infinite loops.

---

## 5. Priority System Interaction

### Priority Phases (engine/priority.cljs:11-14)

```clojure
(def priority-phases #{:upkeep :draw :main1 :combat :main2 :end})
```

Untap and cleanup do NOT grant priority.

### Priority After Cast (from casting.cljs, not shown here)

When spell cast:
- Stack item created with `:stack-item/controller <caster>`
- Priority **remains on caster** (player who cast)
- Stack is non-empty
- Opponent gets priority next (via yield)

### Bot Auto-Pass Logic (priority_flow.cljs:218-272)

Complex heuristic for when opponent should auto-pass (not require human input):

```clojure
(defn negotiate-priority [app-db]
  (let [result (priority/yield-priority game-db holder-eid)]  ;; Current player passes
    (let [should-auto-pass-opponent?
          (when opponent-player-id
            (or auto-mode  ;; :resolving or :f6
                (and (not (player-is-bot? gdb holder-eid))  ;; Human yielded
                     (or (and (player-is-bot? gdb opp-eid)
                              (bot-would-pass? gdb opponent-player-id))  ;; Bot would pass
                         (and (player-is-bot? gdb active-eid)
                              (queries/stack-empty? gdb))))  ;; Bot turn, empty stack
                (and (player-is-bot? gdb active-eid)
                     (queries/stack-empty? gdb)
                     (not (priority/check-stop gdb active-eid ...)))))]
      ;; Auto-pass opponent if conditions met
```

**Conditions for opponent auto-pass**:
1. Auto-mode is active (`:resolving` or `:f6`) — ALWAYS auto-pass
2. Human just yielded AND one of:
   a. Bot would pass (checked via `bot-would-pass?`)
   b. Bot's turn with empty stack (no responses to bot actions)
3. Bot's turn with empty stack and no phase stop

**⚠️ Subtle Coupling** (lines 250-258):
- `bot-would-pass?` calls `bot/bot-priority-decision` in game-db state
- This re-evaluates bot conditions (expensive query on every yield)
- If `bot-would-pass?` returns false due to incomplete game-db state (race condition), opponent doesn't auto-pass
- Human must manually yield, causing cascading ::yield dispatches

**Quality Concern**: This heuristic is correct but fragile — depends on three different checks that are hard to reason about together.

---

## 6. Phase and Turn Cycle

### Turn Phases (from phases.cljs)

```clojure
(def phases [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])
```

**Bot's Turn Cycle** (priority_flow.cljs:115-137):

During bot's turn, `yield-advance-phase` (line 140) handles phase advancement:

```clojure
(if bot-driving?
  ;; Bot interceptor driving: one phase at a time
  (let [result (bot-turn-advance-one-phase app-db)]
    ...
    ;; Check for stop hit
    (and (not f6?)
         (priority/check-stop result-db active-eid new-phase))
    {:app-db (:app-db result)}  ;; Stop hit — pause

    ;; Same turn, no stop — pause for bot interceptor to dispatch ::bot-decide
    :else
    {:app-db (:app-db result)}))
```

**Flow During Bot's Turn**:
1. `::yield` fires → calls `yield-impl`
2. `negotiate-priority` → both players pass (human auto-passes on empty stack)
3. `yield-advance-phase` → advances ONE phase (lines 157-184)
4. Returns without `:continue-yield?`
5. Main event loop idle
6. Custom `:db` handler from phase advance detects bot holds priority
7. Queues `::bot-decide`
8. `::bot-decide` fires → bot casts spell or plays land
9. If action taken, game-db changes → custom `:db` handler → SBAs → re-checks bot-should-act
10. If bot still holds priority, queues another `::bot-decide`
11. Loop: bot casts, bot gets priority, bot decides again

**⚠️ Design Issue** (lines 182-184):
```clojure
;; Same turn, no stop — pause for bot interceptor to dispatch ::bot-decide
:else
{:app-db (:app-db result)}
```

Comment says "pause for bot interceptor to dispatch" but code just returns. The pause happens because `:continue-yield?` is NOT set, so yield-handler doesn't re-dispatch. Re-entry comes from custom `:db` handler after bot action.

**Missing**: No mention that this relies on custom `:db` handler to trigger next action.

### Combat Phase Handling

**⚠️ CRITICAL GAP**: Combat phase advances but:
1. No `::declare-attackers` event handler in code
2. `bot-choose-attackers` exists (protocol.cljs:48) but never called
3. Burn spec has `:bot/attack-strategy :all` but it's dead code
4. Combat infrastructure exists (`engine/combat.cljs`) but bot integration missing

**Consequence**: Bot never attacks, even if it has creatures on battlefield.

---

## 7. Test Coverage

### Test Files (5 total, ~225 lines)

| File | Tests | Coverage |
|------|-------|----------|
| `protocol_test.cljs` | 16 | Bot protocol functions, specs, decks |
| `rules_test.cljs` | ? | (Not read) |
| `interceptor_test.cljs` | 14 | Action planning, dispatch, safety limit |
| `burn_integration_test.cljs` | 5 | Turn progression, yield-impl integration |
| `definitions_test.cljs` | ? | (Not read) |

### Coverage Analysis

**Well-Tested**:
- ✓ `get-bot-archetype` return values
- ✓ `bot-priority-decision` for goldfish and burn
- ✓ `bot-phase-action` returns correct values
- ✓ Deck composition (card counts, types)
- ✓ `bot-decide-action` action planning
- ✓ `build-bot-dispatches` dispatch sequence
- ✓ `bot-should-act?` detection
- ✓ Turn progression via `yield-impl`

**Not Tested**:
- ✗ Safety limit behavior (action-count >= 20)
- ✗ Combat/attack declaration
- ✗ Bot blocking (no rules defined)
- ✗ Multi-turn gameplay (damage accumulation, game end)
- ✗ Edge cases: empty hand, tapped-out, opponent responses
- ✗ Phase action execution (phase transitions)
- ✗ Condition evaluation edge cases (resolving players, zone counts)
- ✗ Infinite loop prevention (beyond safety limit)

### Missing Test Scenarios

1. **Burn bot full game**: Bot plays lands, casts bolts each turn until opponent dies
2. **Goldfish vs burn**: Goldfish plays lands, burn casts bolts, verify game progresses
3. **Safety limit activation**: Bot exceeds 20 actions, verify it passes
4. **Multi-color spell**: Future-proofing for burn bot with dual-color spell
5. **Combat omission**: Burn bot with creatures on battlefield (once combat implemented)

---

## 8. Known Gaps & Inconsistencies

### Feature Gaps (Not Implemented)

| Feature | Status | Impact |
|---------|--------|--------|
| Combat/attack declaration | Dead code (choose-attackers never called) | Burn bot never attacks |
| Combat/blocking | No blocking rules | Burn bot defenseless |
| Respond during opponent turn | No priority rules for burn to respond | Burn can't cast if human has stack |
| Lifecycle rules | Only :auto mode supported | No :on-pause, :reactive, :scripted modes |
| Mana color matching | Uses simple tap sequence, doesn't verify mana types | Multi-color spells may use wrong lands |

### Code Quality Issues

| Issue | Severity | Line(s) | Description |
|-------|----------|---------|-------------|
| Multi-color mana cost broken | Medium | interceptor.cljs:91 | `reduce + 0 (vals mana-cost)` wrong for multi-color |
| Action count safety limit | High | interceptor.cljs:148 | Arbitrary 20-action limit stops bot prematurely |
| Dead code: choose-attackers | Low | protocol.cljs:48 | Function exists but never called |
| Dead code: attack-strategy | Low | definitions.cljs:25 | Spec field unused |
| Incomplete turn cycle | High | No `:declare-attackers` handler | Bot skips combat entirely |
| Interactive rules filtered silently | Low | rules.cljs:152 | No warning if bot spec has :interactive rules |
| Action only from hand | Medium | rules.cljs:130 | Can't cast from library (relevant after tutor) |
| Complex priority auto-pass | Medium | priority_flow.cljs:218-272 | Hard-to-reason heuristic, fragile |
| Phase action one-per-phase | High | protocol.cljs:37 | Can't express "play land then cast spell" |
| No phase action exit condition | Medium | interceptor.cljs:163-169 | Phase actions forced; can't skip :play-land even if wrong |

### Architectural Observations

**Good Decisions**:
1. ✓ Pure functions for protocol, definitions, rules
2. ✓ Datascript queries not abused (single lookup per decision)
3. ✓ Event dispatch goes through full pipeline (history, SBAs, priority)
4. ✓ Custom `:db` handler chokepoint prevents scattered bot checks

**Bad Decisions**:
1. ✗ Action counter resets on pass, not per turn (confuses phases with actions)
2. ✗ `bot-phase-action` returns single action, not list
3. ✗ No integration with combat system despite infrastructure existing
4. ✗ Specs use `:rule/conditions` and `:rule/action` but these terms shadow MTG "rules"

---

## 9. Event Ordering & Race Conditions

### Dispatch Sequence (Happy Path)

```
Human casts Lightning Bolt
  ├─ Event: ::cast-spell
  │  ├─ Handler: casting/cast-spell-handler
  │  │  └─ Returns: {:db new-game-db-with-bolt-on-stack}
  │  └─ Effect: :db (custom handler)
  │     ├─ Runs: SBAs
  │     ├─ Checks: bot-should-act?
  │     ├─ Condition: Not bot's turn (priority on human)
  │     └─ Result: Does NOT queue ::bot-decide
  │
  ├─ Event: Human yields ::yield
  └─ (Game state: bot holds priority, stack has bolt)
     ├─ Event: ::yield
     │  ├─ Handler: yield-impl
     │  ├─ Step 1: negotiate-priority
     │  │  ├─ Human passes (was priority holder)
     │  │  ├─ Bot auto-checks: would-pass?
     │  │  ├─ Bot checks conditions: has bolt? has mana? stack-empty?
     │  │  ├─ stack-empty? = FALSE (bolt on stack)
     │  │  └─ Bot would NOT auto-pass (keeps priority to respond)
     │  ├─ Step 2: All passed? NO (bot hasn't yielded)
     │  └─ Step 3: Transfer priority to bot
     │     └─ Returns without :continue-yield?
     │
     └─ Event: ::bot-decide (queued by custom :db handler)
        ├─ Handler: bot-decide-handler
        ├─ Check: bot-should-act? YES
        ├─ Check: action-count (0 < 20) YES
        ├─ Decision: burn-bot wants to cast
        ├─ Taps mountain (game-db changes)
        │  └─ Effect: :db custom handler
        │     ├─ Runs SBAs
        │     ├─ Queues ::bot-decide (bot still has priority after tapping)
        └─ Casts bolt at human
           └─ Effect: :db custom handler
              └─ Queues ::bot-decide (bolt on stack, bot can cast more if it has resources)
```

### Potential Issue: Multiple Bot Decisions in Flight

**Scenario** (hypothetical issue):

If a single re-frame event causes two game-db mutations before any ::bot-decide fires:

```
Event: ::batch-operation
  ├─ Mutation 1: game-db1
  │  └─ Effect: :db (custom) → queues ::bot-decide
  ├─ Mutation 2: game-db2
  │  └─ Effect: :db (custom) → queues ::bot-decide (again!)
  └─ Both dispatches in queue

Next: ::bot-decide runs, mutates game-db3
Next: ::bot-decide runs (second one), sees different game-db, may duplicate action
```

**Real Risk**: Low in current code (single mutation per event), but possible if future events batch operations.

**Safeguard**: `:bot/action-count` exists (to prevent loops), but doesn't prevent race conditions (two ::bot-decide in flight use same count check).

---

## 10. Summary of Findings

### Critical Issues (Fixes Required)

1. **Action Count Safety Limit** (interceptor.cljs:148)
   - **Problem**: Bot forced to pass after 20 actions, interrupting turn
   - **Fix**: Remove or implement as true game-turn boundary check
   - **Test**: Burn bot should cast all available bolts in one turn without limit

2. **Incomplete Turn Cycle** (No combat handler)
   - **Problem**: Combat phase exists but bot never attacks
   - **Fix**: Implement `::declare-attackers` event handler
   - **Test**: Burn bot with creatures should attack each turn

3. **Multi-Color Mana Cost** (interceptor.cljs:91)
   - **Problem**: `reduce + 0 (vals cost)` fails for dual-color spells
   - **Fix**: Calculate by summing color requirements properly
   - **Test**: Add dual-color spell to burn deck, verify it casts correctly

### Quality Issues (Should Improve)

1. **Dead Code** (protocol.cljs:48, definitions.cljs:25)
   - Remove `bot-choose-attackers` or implement combat
   - Remove `:bot/attack-strategy` from specs until combat works

2. **Complex Priority Logic** (priority_flow.cljs:218-272)
   - `negotiate-priority` has 3 nested conditions, hard to reason about
   - Consider extracting `should-auto-pass-opponent?` as named function
   - Document assumptions (e.g., "bot would-pass is checked at each yield")

3. **Phase Action Limitation** (protocol.cljs:37)
   - Current: one action per phase
   - Better: return list of actions or check action result
   - Enable: "play land, then cast spell in main phase"

4. **Condition Evaluation Gaps** (rules.cljs:130)
   - Only searches hand for cards in `resolve-action`
   - Should search library for post-tutor scenarios
   - Or accept resolved object-id instead of :card-id

### Design Observations

**Bot system is well-suited for**:
- Simple archetypes (goldfish, burn)
- Single-action phases (play land OR cast spell, not both)
- Testing specific card interactions (player tunes hand, bot applies pressure)

**Bot system struggles with**:
- Reactive bots that hold priority (would need :interactive rules)
- Multi-step turns (land, cast, ability activation, attack)
- Complex sequencing (Storm count management, chaining effects)
- Block/respond logic (no implemented)

---

## Appendix: Files & Line References

### Key Files

| Path | Size | Key Functions |
|------|------|---|
| `src/main/fizzle/bots/protocol.cljs` | 66 | `get-bot-archetype`, `bot-priority-decision`, `bot-phase-action` |
| `src/main/fizzle/bots/definitions.cljs` | 57 | `goldfish-spec`, `burn-spec` |
| `src/main/fizzle/bots/rules.cljs` | 180 | `evaluate-condition` (multimethod), `match-priority-rule` |
| `src/main/fizzle/bots/interceptor.cljs` | 188 | `bot-should-act?`, `bot-decide-action`, `build-bot-dispatches`, `bot-decide-handler` |
| `src/main/fizzle/events/db_effect.cljs` | 60 | `game-db-effect-handler` (chokepoint) |
| `src/main/fizzle/events/priority_flow.cljs` | 420 | `negotiate-priority`, `yield-impl`, `bot-turn-advance-one-phase` |
| `src/main/fizzle/events/phases.cljs` | 155 | `advance-phase`, `start-turn`, `next-phase` |

### Test Files

| Path | Tests | Focus |
|------|-------|-------|
| `src/test/fizzle/bots/protocol_test.cljs` | 16 | Protocol functions, specs, decks |
| `src/test/fizzle/bots/interceptor_test.cljs` | 14 | Action planning, dispatch sequence |
| `src/test/fizzle/bots/burn_integration_test.cljs` | 5 | Turn progression, full game flow |

### Critical Lines

- `interceptor.cljs:148` — Action count safety limit (>= 20)
- `interceptor.cljs:91` — Multi-color mana cost calculation (BROKEN)
- `priority_flow.cljs:155-184` — Bot turn phase advancement (one phase at a time)
- `priority_flow.cljs:218-272` — `negotiate-priority` (complex auto-pass heuristic)
- `db_effect.cljs:50-52` — Bot decision trigger via custom :db handler
- `protocol.cljs:48-56` — `bot-choose-attackers` (dead code, never called)
- `definitions.cljs:25` — `:bot/attack-strategy` (unused)

---

## Next Steps (Recommendations)

1. **Immediate**: Remove or fix the 20-action safety limit
2. **Short-term**: Implement `::declare-attackers` event handler for combat
3. **Short-term**: Fix multi-color mana cost calculation in `bot-decide-action`
4. **Medium-term**: Refactor `negotiate-priority` to extract auto-pass logic into named helper
5. **Medium-term**: Extend `bot-phase-action` to support sequential actions or exit conditions
6. **Long-term**: Add blocking/respond rules to bot specs for interactive archetypes

