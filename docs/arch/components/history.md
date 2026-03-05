# History / Fork-Replay

## Responsibility

Fork and replay system that enables branched practice sessions. A re-frame interceptor observes every game-state-changing event and records it in an event history log. State at any point is reconstructible by reducing the event log from the beginning (or from a snapshot). Forking creates a new branch by copying the event log up to a chosen point; replay fast-forwards to any position. Undo pops the last event. ClojureScript's structural sharing makes snapshot storage and fork operations low-cost. This component is orthogonal to the game flow — it intercepts events transparently without being called by the event layer.

## Interface Contract

### IN

- re-frame event stream (observed transparently via interceptor — no explicit calls from Game Events)
- Fork/undo/replay commands dispatched by Views / UI: `::fork-at`, `::undo`, `::replay-to`
- Datascript db snapshots at fork points

### OUT

- Branch state written to a history data structure in the db (branch list, current branch pointer, event log per branch)
- Restored Datascript db values when the user navigates to a fork point or replays
- No side effects on the game rules path — purely observational until the user invokes a history command

## Local Changes

Changing snapshot frequency, adding branch metadata, or modifying the history navigation UI are local to this component. Changes become non-local when a history command (e.g., fork) needs to interact with bot state (to reset the bot's turn position) or with the selection system (to cancel in-progress selections before branching).
