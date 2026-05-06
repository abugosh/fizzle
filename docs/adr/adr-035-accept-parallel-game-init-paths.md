# ADR 035: Accept Parallel Game Init Paths with Shared Callees

## Status

Accepted

## Context

Game state construction exists in two independent functions: `init-game-state` (events/init.cljs:93-141) for normal game setup and `restore-game-state` (sharing/restorer.cljs:131-183) for snapshot restoration. Both execute the same 7-step sequence: create Datascript connection, transact card registry, create both players via `create-complete-player`, load stops, build zone objects via `build-object-tx`, and create the game entity.

The Scenario Builder feature (epic fizzle-xrwe) introduces a third init path: `init-from-scenario`, which performs the same shared steps but with custom zone placement (user-specified hand, graveyard, battlefield cards), top-N library ordering, and custom mana/life/phase.

An intuition audit (2026-05-06) identified this as a complection tension: the shared DB setup (~10 lines of boilerplate) is braided with entry-point-specific zone placement logic (the bulk of each function). Extracting a shared "game DB construction" core was considered.

## Decision

We accept the parallel init paths. The shared boilerplate is ~10 lines per path, and the divergent zone-placement logic is the majority of each function. Extracting a shared core would save modest duplication at the cost of an indirection layer and a callback/parameterization mechanism for zone placement.

Three paths sharing low-level callees (create-complete-player, build-object-tx, create-game-entity-tx) is sufficient. Each path has full control over its own flow.

## Consequences

- Scenario builder writes its own init function that calls the same shared building blocks
- If a fourth init path emerges (e.g., tactics puzzle loader, tournament replay), revisit this decision — four paths repeating the same boilerplate may justify extraction
- All init paths must respect the ordering convention: transact cards → create players → build objects (see ADR-036)
