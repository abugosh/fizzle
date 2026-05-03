# Bot Decision Loop Verification Checklist

**Purpose**: Verify epic fizzle-4s2l issues are resolved
**Date**: 2026-04-02

---

## Issue #1: Race Condition (Sync Director Loop)

### Check 1.1: No Async Dispatch in Director
```bash
grep -n "dispatch\|dispatch-later\|js/setTimeout\|promise\|async" \
  src/main/fizzle/events/director.cljs
```
**Expected**: Only matches in comments about old architecture
**Result**: [RUN THIS]

### Check 1.2: Director Loop is Pure Function
```bash
grep -A 50 "defn run-to-decision" src/main/fizzle/events/director.cljs | \
  grep -E "atom|reset|swap|volatile"
```
**Expected**: No mutations (no hits)
**Result**: [RUN THIS]

### Check 1.3: bot-act Has No Side Effects
```bash
grep -A 40 "defn bot-act" src/main/fizzle/events/director.cljs | \
  grep -E "rf/dispatch|atom|reset|volatile"
```
**Expected**: No side effects (no hits)
**Result**: [RUN THIS]

### Check 1.4: Old Interceptor Removed
```bash
find src/main/fizzle -name "*interceptor*" -o -name "*bot*interceptor*"
```
**Expected**: No interceptor file
**Result**: [RUN THIS]

---

## Issue #2: Action Limit (300 Steps vs 20 Actions)

### Check 2.1: New Limit in Place
```bash
grep -n "max-director-steps\|300" src/main/fizzle/events/director.cljs
```
**Expected**: Line 38 (def max-director-steps 300) and line 323 (>= steps 300)
**Result**: [RUN THIS]

### Check 2.2: Old Limit Removed
```bash
grep -r "action-count\|bot-action-count\|>= .*20" \
  src/main/fizzle/bots/ src/main/fizzle/events/
```
**Expected**: No hits (no 20-action limit)
**Result**: [RUN THIS]

### Check 2.3: Safety Limit is Checked in Loop
```bash
grep -B 5 -A 5 "max-director-steps" src/main/fizzle/events/director.cljs | head -20
```
**Expected**: Check before each loop iteration (line 323)
**Result**: [RUN THIS]

---

## Issue #3: Multi-Color Mana (Generic Allocation)

### Check 3.1: Generic Allocation Implementation
```bash
sed -n '79,93p' src/main/fizzle/bots/decisions.cljs
```
**Expected**: Phase 3 finds generic lands and taps for first-color
**Result**: [RUN THIS]

### Check 3.2: Burn Bot Still Works (Single-Color Only)
```bash
grep -A 5 "lightning-bolt\|{:red" src/main/fizzle/bots/definitions.cljs
```
**Expected**: Lightning Bolt is {:red 1} (single color)
**Result**: [RUN THIS]

### Check 3.3: Mana Allocation Tests Pass
```bash
make test 2>&1 | grep -A 2 "mana.*test\|decisions.*test"
```
**Expected**: All tests pass
**Result**: [RUN THIS]

---

## Issue #4: Director Orchestration (Correct Flow)

### Check 4.1: bot-act Called from step-bot-action
```bash
grep -n "bot-act" src/main/fizzle/events/director.cljs
```
**Expected**: Called at line ~178 in step-bot-action
**Result**: [RUN THIS]

### Check 4.2: Priority Holder Determined Correctly
```bash
sed -n '140,150p' src/main/fizzle/events/director.cljs
```
**Expected**: current-holder-player-id function (pure)
**Result**: [RUN THIS]

### Check 4.3: Bot Decision Dispatches to Correct Branch
```bash
sed -n '340,350p' src/main/fizzle/events/director.cljs
```
**Expected**: if bot → step-bot-action, elif human → step-human-action
**Result**: [RUN THIS]

---

## Test Verification

### Test 1: Director Tests Pass
```bash
make test 2>&1 | grep -A 5 "director_test"
```
**Expected**: All tests PASS
**Result**: [RUN THIS]

### Test 2: Bot Integration Tests Pass
```bash
make test 2>&1 | grep -A 5 "burn_integration_test"
```
**Expected**: All tests PASS
**Result**: [RUN THIS]

### Test 3: Mana Tests Pass
```bash
make test 2>&1 | grep -A 5 "decisions_test\|mana_test"
```
**Expected**: All tests PASS
**Result**: [RUN THIS]

### Test 4: Full Test Suite Passes
```bash
make test 2>&1 | tail -5
```
**Expected**: All tests pass (check final line count)
**Result**: [RUN THIS]

---

## Manual Verification

### Scenario 1: Single Burn Bolt Cast
1. Start fresh game
2. Bot T1 main1 phase
3. Bot should play one Mountain (phase action)
4. Bot should cast one Lightning Bolt (priority rule matches)
5. Verify: Bolt on stack, Mountain tapped, red mana pool empty

### Scenario 2: Multiple Casts in One Turn
1. Set up: Bot has 5 Mountains on hand, 10 Lightning Bolts
2. Run director to completion
3. Verify: Bot casts until Mountain supply exhausted
4. Verify: Director returns :await-human (not :safety-limit)

### Scenario 3: Safety Limit Graceful Handling
1. Create pathological case (infinite loop bug)
2. Run director
3. Verify: Director returns :safety-limit (not crash)
4. Verify: App-db is consistent and playable

---

## Code Quality Checks

### Check Q1: No Dead Code
```bash
grep -r "bot-action-count\|bot-decide\|bot-choose-attackers" \
  src/main/fizzle/events/ src/main/fizzle/bots/interceptor.cljs 2>/dev/null
```
**Expected**: Only in protocol.cljs (unused function) and protocol_test.cljs
**Result**: [RUN THIS]

### Check Q2: All Imports Valid
```bash
grep -n "require.*decisions\|require.*director" \
  src/main/fizzle/events/*.cljs src/main/fizzle/bots/*.cljs
```
**Expected**: director required only in calling module (likely priority_flow.cljs)
**Result**: [RUN THIS]

### Check Q3: Linter Passes
```bash
make lint 2>&1 | grep -E "error|warning" | head -10
```
**Expected**: No errors related to director/decisions/bot flow
**Result**: [RUN THIS]

---

## Performance Baseline (Optional)

### Performance Check 1: Director Loop Speed
```bash
# Run a turn of burn bot, measure time
time (make test 2>&1 | grep -A 20 "burn_integration")
```
**Expected**: Completes in <5s
**Result**: [RUN THIS]

### Performance Check 2: Step Count
Add logging to director:
```clojure
(println "Director completed in" steps "steps, reason:" reason)
```
Then run burn bot T1:
**Expected**: Steps < 50 (generous for single turn)
**Result**: [RUN THIS]

---

## Final Verification Checklist

- [ ] Check 1.1: No async dispatch in director ✓
- [ ] Check 1.2: Director loop is pure ✓
- [ ] Check 1.3: bot-act has no side effects ✓
- [ ] Check 1.4: Old interceptor removed ✓
- [ ] Check 2.1: New 300-step limit in place ✓
- [ ] Check 2.2: Old 20-action limit removed ✓
- [ ] Check 2.3: Safety limit checked in loop ✓
- [ ] Check 3.1: Generic allocation implemented ✓
- [ ] Check 3.2: Burn bot single-color ✓
- [ ] Check 3.3: Mana tests pass ✓
- [ ] Check 4.1: bot-act called from director ✓
- [ ] Check 4.2: Priority holder determined correctly ✓
- [ ] Check 4.3: Bot branch dispatches correctly ✓
- [ ] Test 1: Director tests pass ✓
- [ ] Test 2: Bot integration tests pass ✓
- [ ] Test 3: Mana tests pass ✓
- [ ] Test 4: Full suite passes ✓
- [ ] Manual Scenario 1: Single cast works ✓
- [ ] Manual Scenario 2: Multiple casts work ✓
- [ ] Manual Scenario 3: Safety limit works ✓
- [ ] Q1: No dead code ✓
- [ ] Q2: All imports valid ✓
- [ ] Q3: Linter passes ✓

---

## Sign-Off

**All checks pass**: [YES/NO]
**Ready to close epic**: [YES/NO]
**Remaining issues**: [NONE / list below]

---

## Notes

(Space for manual test results and observations)
