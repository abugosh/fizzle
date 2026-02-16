# Practice Platform

## Volatility Axis
Practice features — history/fork/replay, future bots, future tactics/puzzles. Each subsystem owns its data and interpretation logic (ADR-002).

## Layer: Manager
Coordinates practice-specific features that sit above the core game engine. History intercepts game events to capture snapshots. Future bots and tactics will similarly coordinate with Game Orchestration.

## Interface Contract
- IN: Game events intercepted by history interceptor (captures Datascript db snapshots at event boundaries)
- OUT: History entries for Presentation to display; fork/replay state for navigation; future: bot actions dispatched as game events

Note: Snapshot restore is internal — Practice Platform stores and restores Datascript db values directly via `assoc :game/db`, with no Data Foundation function call. Data Foundation is involved only at capture time (interceptor calls `queries/get-game-state` for turn number).

## Responsibility
Fork/replay system via history snapshots (pure value replacement, O(1) via structural sharing). History tracking with automatic fork detection. Local storage persistence. Future: bot AI (goldfish, burn, control, discard archetypes) and tactics/puzzle save/load.

## What Changes Should Be Local
- Adding new history features (branching, comparison)
- Implementing bot archetypes
- Implementing tactics/puzzle save/load
- Changing storage backend

## Open Work
- Opponent turn refactor (ADR-004, second half) — prerequisite for bots
- Bot system implementation (Phase 5 in roadmap)
- Tactics system implementation

## Modules
- `src/fizzle/history/core.cljs` — History data structure
- `src/fizzle/history/descriptions.cljs` — Event descriptions
- `src/fizzle/history/interceptor.cljs` — Global re-frame interceptor
- `src/fizzle/history/events.cljs` — History navigation handlers
- `src/fizzle/storage.cljs` — Local storage API
