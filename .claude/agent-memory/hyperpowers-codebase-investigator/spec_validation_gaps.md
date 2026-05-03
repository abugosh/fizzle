---
name: Spec Validation Gaps — 7 Unvalidated Data Boundaries
description: Complete audit of data creation points lacking spec validation; 3 HIGH-risk, 4 MEDIUM-risk gaps identified with exact file locations and bug surface analysis
type: reference
---

## 7 Unvalidated Data Boundaries (April 2026)

### HIGH RISK (3)

1. **Game State Init — Mana Pools**
   - File: `db/game_state.cljs:30-32, 51-57`
   - Creation: `empty-mana-pool` constant, `create-player-tx` builder
   - Shape: `{:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}`
   - Risk: Missing color key causes `merge-with +` to silently drop, mana calculations fail
   - Consumers: `engine/mana.cljs`, `sharing/restorer.cljs`

2. **Mana Pool Operations**
   - File: `engine/mana.cljs:23, 38, 71`
   - Creation: `resolve-x-cost`, `add-mana`, `empty-pool` functions
   - Risk: Card definitions use raw `:effect/mana` maps — typo `:generic` instead of `:colorless` silently breaks
   - No guard: `merge-with +` on incomplete pools leaves missing colors nil

3. **Object Creation (Restoration & Zone Entry)**
   - Files: `sharing/restorer.cljs:77-111`, `events/init.cljs:60-69`, `engine/zones.cljs:96-104`
   - Creation: `object-tx-for-zone` builder, `objects-tx` function, creature field init
   - Risk: Snapshot portable maps not validated; malformed creature stats corrupt combat
   - Zone entry assumes creatures have power/toughness — divergence breaks game logic

### MEDIUM-HIGH RISK (1)

4. **Trigger/Ability Data**
   - File: `engine/trigger_db.cljs:91-129`
   - Creation: `create-triggers-for-card-tx` maps `:trigger/type` to `:trigger/event-type`
   - Risk: Card `:card/triggers` are raw EDN; typo `:trigger/type` silently uses identity mapping
   - No validation: `:trigger/type` multimethod has :default that treats unknown types as valid

### MEDIUM RISK (3)

5. **Grant Maps**
   - Files: `engine/effects/grants.cljs:42-50, 67-77, 90-95, 111-116`
   - Creation: Effect handlers create `:grant/type`, `:grant/expires`, `:grant/data`
   - Risk: No per-type validation; typo `:expires/phase :cleanup_step` silently expires at wrong time
   - Shape depends on `:grant/type` (alternate-cost, ability, delayed-effect, restriction)

6. **History Entry Data**
   - Files: `history/descriptions.cljs:18-26`, `events/casting.cljs:266-291`, `events/priority_flow.cljs:137-141`
   - Creation: `build-pending-entry`, deferred-entry maps in event handlers
   - Risk: Deferred entry `:type` not enum-validated; typo `:cast_and_yield` produces wrong description
   - Snapshot validity not checked before storing

7. **Continuation System**
   - Files: `events/priority_flow.cljs:137`, `events/selection/core.cljs:66-95`
   - Creation: Handlers return `{:then keyword-value}` maps
   - Risk: No enum for valid `:then` keywords; typo `:resolve_one_and_stop` silently drops continuation
   - Implicit protocol — not encoded in data

---

## Implementation Pattern

Follow existing specs (card_spec.cljs, selection_spec.cljs):
- Multi-spec with defmethods per type
- Dev-only validation via `goog.DEBUG`
- Validation at creation point (chokepoint)
- Describe EXISTING shapes, no new requirements
- ~100-200 lines per spec file

## Validation Chokepoints (Existing + Proposed)

| Boundary | Existing | File | Proposed |
|----------|----------|------|----------|
| Card loading | ✅ | `engine/card_spec.cljs` | — |
| Selection creation | ✅ | `events/selection/spec.cljs` | — |
| Stack item creation | ✅ | `engine/stack_spec.cljs` | — |
| Bot action | ✅ | `bots/action_spec.cljs` | — |
| Player creation | ❌ | — | `db/player_spec.cljs` |
| Mana pool | ❌ | — | `engine/mana_spec.cljs` |
| Object creation | ❌ | — | `engine/object_spec.cljs` |
| Trigger data | ❌ | — | `engine/trigger_spec.cljs` |
| Grant creation | ❌ | — | `engine/grant_spec.cljs` |
| History entries | ❌ | — | `history/entry_spec.cljs` |
| Continuations | ❌ | — | `events/continuation_spec.cljs` |
