---
name: Bot System Quality Issues and Gaps
description: Critical bugs, design limitations, and quality issues found in bot protocol and execution
type: project
---

## Critical Issues Requiring Fixes

**Issue 1: Action Count Safety Limit (interceptor.cljs:148)**
- Bot forced to pass after 20 actions, even with valid plays remaining
- Impacts burn bot: may cast only 5 bolts before forced pass
- Counter increments per action (play-land, cast), not per turn
- No comment explaining why 20 is the limit
- **Fix**: Remove or implement as real turn-boundary check

**Issue 2: Multi-Color Mana Cost Calculation (interceptor.cljs:91)**
- Line: `needed-mana (reduce + 0 (vals mana-cost))`
- **Wrong**: Works for `:red 1` but fails for `:white 1 :blue 1`
- **Impact**: Any dual-color spell in future burn deck would silently fail to cast
- Burn spec currently 20 Mountains + 40 Bolts (all single-red), so no current failure
- **Fix**: Sum costs properly (or use existing mana/can-pay? which is correct)

**Issue 3: Incomplete Turn Cycle (protocol.cljs:48, no caller)**
- Function `bot-choose-attackers` exists but NEVER CALLED anywhere
- Burn spec has `:bot/attack-strategy :all` but it's dead code
- Combat infrastructure exists (engine/combat.cljs) but bot integration missing
- **Result**: Bot never attacks, even if creatures on battlefield
- **Fix**: Implement `::declare-attackers` event handler

## Design Limitations

**Phase Action One-Per-Phase** (protocol.cljs:37-45)
- `bot-phase-action` returns single action `{:action :play-land}`
- Can't express "play land, then cast spells in main phase"
- Both goldfish and burn specs only map `:main1 -> :play-land`, nothing else
- **Issue**: Bot never casts in main2, never enters combat, never responds on opponent's turn

**Priority Auto-Pass Heuristic** (priority_flow.cljs:218-272)
- Complex nested condition: `or (and (not (player-is-bot?)) (or (and ...)))`
- Hard to reason about all paths; fragile to changes
- Re-evaluates `bot-would-pass?` on every yield (expensive query)
- **Risk**: If `bot-would-pass?` returns false (race condition), opponent doesn't auto-pass

**Condition Evaluation Only from Hand** (rules.cljs:130)
- `resolve-action` searches hand for cards by :card-id
- Doesn't search library (relevant after tutor)
- Bot can't execute rule action if card is in library

**Interactive Rules Silently Filtered** (rules.cljs:152)
- `match-priority-rule` skips `:interactive` mode rules without warning
- If bot spec accidentally has `:interactive`, no error — silently ignored

## Missing Features (Dead Code)

- Combat/attack declaration: `bot-choose-attackers` function exists but never called
- Block/respond logic: No blocking rules, no priority rules for burn to cast during opponent turn
- Reactive bots: No :interactive rule mode for human-controlled responses
- Sequential actions: Can't express "do X, then do Y in same phase"

## Test Coverage Gaps

**Not Tested**:
- Safety limit activation (action-count >= 20)
- Combat/attack scenarios
- Bot blocking/defending
- Multi-turn gameplay (damage accumulation)
- Multi-color spell casting
- Phase transitions with bot
- Condition evaluation edge cases

**Well Tested**:
- Protocol function dispatch (get-bot-archetype, bot-priority-decision)
- Deck composition
- Action planning and dispatch building
- Turn progression via yield-impl
