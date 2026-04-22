# ADR 016: Separate Player Identity from Effect Target Keywords

## Status

Accepted

## Context

The keyword `:opponent` serves dual roles in the codebase:

1. **Player identity** — `{:player/id :opponent}` in `events/init.cljs:132`
2. **Effect target directive** — `{:effect/target :opponent}` in card definitions, meaning "target the other player"

When an effect handler uses `(get effect :effect/target player-id)` as the default, and `player-id` happens to be `:opponent`, the default value `:opponent` is indistinguishable from the directive "target the other player." This caused the opponent draw regression (commit 73fdfe6, undetected for 3 weeks, fixed in 1605323) where effects meant for the opponent were redirected to the human player.

The fix in 1605323 applied an explicit `(contains?)` guard to 4 effect handlers in `engine/effects/zones.cljs`. However, 2 sites remain unfixed using the old vulnerable pattern:
- `engine/effects/life.cljs:12,25`
- `events/selection/core.cljs:139`

Additionally, `:opponent` appears in 26 production files across 4 semantic roles (player-id, effect directive, targeting option, storage/UI key), making the collision surface large and growing.

## Decision

We will separate the player identity namespace from the effect target directive namespace. The player-id for the opponent will use a keyword that cannot collide with any effect target keyword. Effect target keywords (`:opponent`, `:self`, `:any-player`, `:each-player`, `:controller`) remain unchanged — they are descriptive and clear in card definitions.

The specific choice of new player-id will be determined during implementation. Candidates include `:player-2` or a namespaced keyword.

## Consequences

- The collision class is eliminated structurally — effect handlers no longer need special disambiguation logic to distinguish player-id from effect directive
- All 26 production files using `:opponent` as player-id will need updating
- All 75 test files using `:player-2` will need updating (to whatever the new id is)
- The compensating pattern in `bots/interceptor.cljs` (trying `[:player-1 :player-2 :opponent]`) can be simplified
- The hardcoded `#{:player-1 :opponent}` in `events/selection/targeting.cljs:34` needs updating
- Subscriptions in `subs/game.cljs` that hardcode `:opponent` need updating
- Card definitions using `:effect/target :opponent` remain unchanged
- Future effect handlers do not need to implement the `(contains?)` guard pattern
