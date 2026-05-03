---
name: epic-fizzle-lvs0-task-fizzle-1oob-learnings
description: Learnings from task fizzle-1oob — Create db/game_state.cljs and update events/init.cljs
type: project
---

# Task fizzle-1oob Learnings

## What was done
- Created `src/main/fizzle/db/game_state.cljs` with: `human-player-id`, `opponent-player-id`, `empty-mana-pool`, `create-player-tx`, `create-game-entity-tx`
- Updated `events/init.cljs` to use `fizzle.db.game-state` for all player/game entity construction
- Fixed opponent player-id from `:opponent` to `:player-2` (ADR-016 compliance)
- Updated 4 test files that used `:opponent` as a player-id query key

## Critical Discovery: Test Precondition Was Wrong
The task context stated "All 75 test files already use :player-2 — zero test file changes needed". This was INCORRECT.

4 test files used `:opponent` as a player-id for database queries:
1. `src/test/fizzle/events/init_game_test.cljs` — `q/get-objects-in-zone db :opponent :zone`
2. `src/test/fizzle/events/active_player_switching_test.cljs` — `q/get-player-eid`, `q/get-hand`, `q/get-top-n-library`, `phases/advance-phase` all with `:opponent`
3. `src/test/fizzle/events/setup_test.cljs` — `bot/get-bot-archetype db :opponent`
4. `src/test/fizzle/events/game_init_test.cljs` — `q/get-objects-in-zone db :opponent :sideboard`

**How to apply:** When an epic says "test files already use :player-2", verify with grep before trusting. The rule "don't modify test files to change :player-2 references" means don't break already-correct tests — it doesn't mean don't update tests using the OLD wrong :opponent player-id.

## Storage Role Keys vs Player-IDs
`events/init.cljs` lines 145-146 use `(:player stops)` and `(:opponent stops)` — these are STORAGE ROLE KEYS from `db/storage.cljs:default-stops` (`{:player #{...} :opponent #{...}}`), NOT player-ids. Do NOT rename these to `:player-2`.

**Why:** The stops map uses `:player` and `:opponent` as semantic role keys (human vs bot), separate from the player-id system. This distinction must be preserved across all tasks in this epic.

## Factory Design
`create-player-tx` uses flat `(merge defaults overrides)`. The `:player/mana-pool` key is replaced wholesale (not deep-merged). Callers passing `{:player/mana-pool {:blue 3}}` get only `{:blue 3}` as their pool — this is intentional since callers should provide complete pools when overriding.

`create-game-entity-tx` takes `active-player-eid` (integer, not keyword) — callers must transact players first, then look up the eid, then call this factory.

## Turn-Based Triggers
`turn-based/create-turn-based-triggers-tx` takes `(player-eid player-id)` — must use `game-state/human-player-id` and `game-state/opponent-player-id` for the player-id arg (second param), now using shared constants.

## Lint Pitfall
The test file initially imported `[fizzle.db.schema :refer [schema]]` and `[fizzle.engine.cards :as cards]` that weren't needed (integration test uses `init-game-state` which handles setup internally). These caused clj-kondo warnings → lint failure. Remove unused requires before running `make validate`.
