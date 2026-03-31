# Game Events

## Responsibility

re-frame event handlers that orchestrate the top-level game flow. This component is decomposed into 16+ focused modules: `casting`, `resolution`, `phases`, `priority_flow`, `director`, `db_effect`, `init`, `lands`, `opening_hand`, `cleanup`, `cycling`, `ui`, `setup`, `calculator`, `abilities`, and `selection/`. The `director` module is the central orchestration seam — a synchronous `run-to-decision` loop that drives bot turns inline, resolves the stack, advances phases, and evaluates auto-pass logic after every player action.

Event handlers coordinate between the Engine (for rules) and the Data Foundation (for state reads/writes) but contain no game rules themselves. The `db_effect` module is the chokepoint through which all game-db mutations flow, including the re-frame bot-decide event handler triggers.

## Interface Contract

### IN

- re-frame events dispatched by Views / UI and Opponent AI: `::cast-spell`, `::resolve-top`, `::advance-phase`, `::start-turn`, `::pass-priority`, `::play-land`, `::yield`, and related game action keywords across the decomposed modules
- Datascript db (via re-frame cofx) containing current game state

### OUT

- Updated Datascript db (via re-frame fx) reflecting new game state after the action
- Downstream event dispatches: may chain to selection events when an action requires player input
- `run-to-decision` result: `{:app-db, :reason}` where reason is `:await-human`, `:pending-selection`, `:game-over`, or `:safety-limit`
- History interceptor observations (transparent, not an explicit output)

## Local Changes

Adding a new top-level game action (a new event keyword with its orchestration handler in one of the focused modules) is local to this component as long as it delegates rule evaluation to the Engine and state persistence to the Data Foundation. Changes become non-local when a new action requires a new engine function, a new Datascript attribute, or a new UI element. Extending the director loop to handle a new decision point type is local to `director.cljs`.
