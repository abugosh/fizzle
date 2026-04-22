# ADR 017: Extract Shared Game State Invariants

## Status

Accepted (design pending — see brainstorm for decomposition boundary)

## Context

Game state construction happens in three independent places:

1. **Production init** — `events/init.cljs:init-game-state` creates full game state including player entities, game entity, deck construction, and opening hand sculpting
2. **Test helpers** — `test_helpers.cljs:create-test-db` and `add-opponent` construct game state for tests with different player-ids, different attribute sets, and independent logic
3. **Snapshot restoration** — `sharing/restorer.cljs` reconstructs game state from saved snapshots, hardcoding `:opponent` in stops restoration

These three consumers independently define what a valid game state looks like. When any of them diverges (as happened with player-id `:player-2` vs `:opponent`), the others cannot detect the inconsistency. The divergence between test helpers and production init caused a 3-week silent production regression — 2939 tests passed while opponent draw was broken.

## Decision

We will extract shared invariants — the parts of game state construction that must be identical across all consumers — into a shared module. Both production init, test helpers, and snapshot restoration will consume this shared module.

Invariants include (at minimum):
- Player entity shape (required attributes and their types)
- Player-id constants (what keyword identifies each player)
- Game entity shape
- Turn-based trigger registration

Policy (what legitimately differs between consumers) includes:
- Deck construction and shuffling
- Opening hand sculpting
- Storage loading
- Mana pool initial values (tests need configurable mana)

The exact decomposition boundary will be determined via brainstorm.

## Consequences

- Player entity construction defined once — test/production divergence becomes a compile/runtime error rather than a silent behavior difference
- New shared module becomes a dependency for init, test helpers, and snapshot restoration
- Tests retain surgical control over game state (zone placement, mana, life) while sharing identity and entity structure with production
- Adding a new required player attribute updates all consumers automatically
- The `bots/interceptor.cljs` compensating pattern (trying multiple player-id keywords) can be eliminated
