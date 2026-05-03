---
name: Bot system data flows investigation
description: Complete module-level data flow analysis; director is true orchestrator, not dispatch-based; interceptor code unreachable
type: reference
---

## Key Findings

**Two active paths (only director used):**
1. **Director path (CURRENT)**: `director/run-to-decision` → `bot-act` → inline engine calls (pure, sync)
2. **Interceptor path (LEGACY)**: `bots/interceptor` handlers `::bot-decide` (unreachable)

**Architecture mismatches:**
- Model says "bots dispatch events" → Reality: director calls handler functions directly
- Model declares `bots -> events_game` → Reality: only `director -> events_game`; bots don't import events modules
- Events layer used as pure function library, not via re-frame dispatch

**Critical bot behaviors:**
1. Land play: `bot-act` → `lands/play-land` → SBA check → loop
2. Spell cast: `bot-decide-action` → mana tapping → `casting/cast-spell-handler` → SBA check
3. Attacker selection: `resolution.cljs` pre-fills bot's selection via `bot-choose-attackers`, auto-confirms
4. Interactive selections: Director stops and waits for human (bots cannot auto-confirm effects during resolution)
5. Priority: Bots follow identical priority passing as humans via `priority/*` functions

**SBA execution is implicit:** After every action, director calls `sba/check-and-execute-sbas`. Not documented in architecture model.

**Code to remove:**
- `bots/interceptor.cljs` lines 252-261: unreachable event handlers
- `bots/interceptor.cljs` lines 177-249: unreachable handler functions

**Ordering constraints enforced by director (not by module structure):**
- Mana tapping before spell cast (sequential reduce)
- SBA checks after every action (explicit calls in director)
- Selection confirmation before loop restart (if needed)

Full report: `/Users/abugosh/g/fizzle/BOT_SYSTEM_DATA_FLOWS_INVESTIGATION.md`
