---
name: Director Human-Bot Path Asymmetry Investigation
description: Structural analysis of human vs bot player paths through director—reveals history recording bypass and event handler asymmetry
type: reference
---

# Director System: Human vs Bot Path Asymmetry

## The Asymmetry at a Glance

**Human path:** Re-frame event handler → pure function → `:history/pending-entry` → history recorded
**Bot path:** Director → pure function directly → NO `:history/pending-entry` → history SKIPPED

This is not an implementation detail—it's a structural difference in how actions enter the system.

## Entry Points: 6 Human, 1 Bot

### Human Entry Points (All Re-frame events, all create pending-entry)
1. `::cast-spell` (events/casting.cljs:309) — calls `cast-spell-handler` directly; sets pending-entry if cast succeeds immediately, or defers via `:history/deferred-entry` if selection needed
2. `::play-land` (events/lands.cljs:72) — calls pure `play-land`, sets pending-entry (lines 71-81)
3. `::activate-mana-ability` (events/abilities.cljs:27) — calls pure `activate-mana-ability`, sets pending-entry (lines 26-36)
4. `::yield` (events/priority_flow.cljs:63) — calls `director/run-to-decision` with human-yielded? true; sets pending-entry if game-db changed (lines 62-78)
5. `::yield-all` (events/priority_flow.cljs:82) — calls `director/run-to-decision` with yield-all? true; sets pending-entry if game-db changed (lines 81-97)
6. `::cast-and-yield` (events/priority_flow.cljs:172) — special UX handler; sets deferred-entry overriding cast-spell's deferred-entry (lines 121-160)

**Pattern**: ALL human actions go through event handlers. ALL set either pending-entry or deferred-entry.

### Bot Entry Point (Single: director loop)
- `director/run-to-decision` (events/director.cljs:290) — main loop
  - Dispatches to `step-bot-action` when bot has priority (director.cljs:343-344)
  - `step-bot-action` calls:
    - `director/bot-act` for decisions (line 178)
    - `bot-act` calls pure functions directly:
      - `lands/play-land` (director.cljs:105) — **NO pending-entry**
      - `casting/cast-spell-handler` (director.cljs:115) — **NO pending-entry**
      - `mana-activation/activate-mana-ability` (director.cljs:112-114) — **NO pending-entry**

**Pattern**: Bot actions are called via pure function invocation. NO event handlers. NO pending-entry.

## Code Paths: Both Converge on Engine Functions

Both human and bot call the SAME pure functions:
- `casting/cast-spell-handler` — used by human `::cast-spell` AND director bot-act
- `lands/play-land` — used by human `::play-land` AND director find-bot-land-to-play
- `mana-activation/activate-mana-ability` — used by human `::activate-mana-ability` AND director tap loop

**Engine rules are identical** — both paths validate with:
- `rules/can-cast?`
- `rules/can-play-land?`
- `rules/can-cast-mode?`

## The History Recording Asymmetry

### Human Actions: Recorded
```
event handler sets :history/pending-entry
↓
global history interceptor (history/interceptor.cljs) observes it
↓
history/interceptor processes the entry after `:db` effect
↓
entry appended to :history/entries (or auto-fork if rewound)
```

### Bot Actions: NOT Recorded
```
director calls pure function directly
↓
function returns {:game/db ...}
↓
step-bot-action applies result to app-db (director.cljs:182)
↓
NO :history/pending-entry set
↓
history interceptor NEVER sees the action
↓
action is INVISIBLE to history system
```

**Verification**: Check director_test.cljs — tests initialize history (line 22: `history/init-history`) but NEVER assert that history entries are created. This confirms the gap is untested.

## Selection System Interaction

### When Human Spell Needs Targeting
1. Event handler calls `cast-spell-handler`
2. Handler detects targeting requirement
3. Returns `{:game/db ... :game/pending-selection sel}`
4. Event handler sets `:game/pending-selection` on app-db (casting.cljs:162)
5. Event handler also defers history entry to `:history/deferred-entry` (casting.cljs:265-270)
6. UI pauses, user confirms selection
7. `::confirm-selection` event → `execute-confirmed-selection` → activation of deferred-entry

### When Bot Spell Needs Targeting
1. Director calls `casting/cast-spell-handler` (director.cljs:115)
2. Handler detects targeting requirement
3. Returns `{:game/db ... :game/pending-selection sel}`
4. `bot-act` wraps it and returns to `step-bot-action` (director.cljs:122-125)
5. `step-bot-action` returns `{:done {:reason :pending-selection}}` (director.cljs:186-189)
6. **Director pauses** — awaits human input
7. UI shows selection
8. **Human must confirm**
9. `::confirm-selection` event → continuation chain → director invoked again

**Key asymmetry**: Bot spell + targeting = director pauses, human decides. Bot action is NOT recorded in history.

## Duplicate Code: find-bot-land-to-play

**Location 1**: `director.cljs:82-87`
```clojure
(defn- find-bot-land-to-play
  [game-db player-id]
  (some (fn [obj] ...)
        (queries/get-hand game-db player-id)))
```

**Location 2**: `bots/interceptor.cljs:163-174`
```clojure
(defn- find-bot-land-to-play
  [game-db player-id]
  (let [hand (queries/get-hand game-db player-id)]
    (some (fn [obj] ...)
          hand)))
```

Both are identical. The interceptor copy is **vestigial** — the old architecture (dispatch-later + db_effect interceptor) is dead, but this function was never removed. Current code path uses director's copy only.

## Director as Boundary Violator

The director is positioned as a "pure orchestrator," but it actually:

### Is
- A game loop (runs until human decision point)
- Pure (testable, deterministic)
- Synchronous (no dispatch-later complexity)

### Does
- Calls engine functions directly (bypass event handler layer)
- Skips history system entirely for bot actions
- Mixes two responsibilities:
  1. Running the game loop (orchestration)
  2. Dispatching bot actions (action dispatch without event handlers)

### Should Be
If the intent is "pure game orchestrator," it should:
- Record bot actions in history (currently skipped)
- OR document that history/undo is not supported for bot actions
- OR refactor to use event dispatch for bot actions

If the intent is "bot dispatcher," the current architecture is reasonable, but it's mislabeled as a game orchestrator.

## Testing Gap

**director_test.cljs** initializes history but never verifies history entries. No tests check:
- Do bot actions appear in history?
- Can you undo after bot actions?
- Do replayed positions with bot actions match original positions?

This gap suggests the history interaction was not a design goal.

## Structural Implications

### Scenario: Undo with Bot Actions
```
1. Human casts a spell (recorded in :history/entries as entry-1)
2. :yield-all dispatches → director runs
   - Bot plays a land (NOT recorded)
   - Bot casts a spell (NOT recorded)
   - Bot passes
3. Director returns, human gets priority
4. State: board has 2 bot permanents, stack empty
5. Human clicks Undo (to step back 1 entry)
6. History replays to entry-1 (human's spell cast)
7. State: board has 0 bot permanents (the bot's lands/spells are gone)
8. If human clicks Redo (forward):
   - Director runs again
   - Bot plays land again, casts spell again
   - Now board has 2 bot permanents again
9. INCONSISTENCY: History log shows 1 human entry, but board state differs
   depending on whether director has been invoked (bot actions are conditional)
```

This is a **correctness bug**, not just a feature gap.

## Why This Matters Architecturally

### The Question: Is the Director a Peer or a Layer?

**If director is a peer to event handlers:**
- It's orchestration at the same level as priority_flow
- Should go through the same history mechanism
- Should be observable in the event stream

**If director is a layer above event handlers:**
- It's a runtime that executes events
- But then why doesn't it use the event dispatch mechanism?
- Why bypass the history interceptor?

**Current reality**: Director is both, which is the root cause of the asymmetry.

### The Bot Path Asymmetry Reveals a Design Gap

The director was designed to replace dispatch-later (epic fizzle-bcz9). It fixed:
- ✓ Eliminated :yield/epoch, :yield/step-count, :bot/action-pending? complexity
- ✓ Eliminated db_effect bot-decide queuing
- ✓ Made bot decisions testable (pure functions)

But it broke:
- ✗ Bot action history recording (now invisible to history system)
- ✗ Consistency between history log and game state
- ✗ Undo/fork with mixed human-bot games

**The Old Architecture (dispatch-later)**:
- Bot actions went through event dispatch
- Event handlers set pending-entry
- History recorded bot actions
- But: complex yield state management, hard to test

**The New Architecture (director)**:
- Director is pure and testable
- But: bot actions bypass history system
- Tradeoff: simplicity gained, correctness lost

## Conclusion

The human vs bot asymmetry is **not inherent to the domain**. Both paths use the same engine functions. The asymmetry exists because:

1. **Human path** uses re-frame event handlers as the entry point (brings history recording as a side effect)
2. **Bot path** uses the director as the entry point (pure function calls, no history recording)

This is a structural design choice, not a domain constraint. The fix would be:
- **Option A**: Director sets pending-entry for bot actions (simplest, maintains current architecture)
- **Option B**: Bot actions dispatch re-frame events (reintroduces complexity, fixes history)
- **Option C**: Accept limitation and document no history support for bot actions (current behavior, undocumented)

Currently: Option C (undocumented), with growing correctness concerns if undo/fork becomes a feature.
