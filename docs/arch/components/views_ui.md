# Views / UI

## Responsibility

Reagent components that render the current game state as a React UI. This component owns all visual presentation — the game board, player hand, stack display, mana pool readout, selection modals (for player choices), and history navigation controls. Every component is a pure render function: it subscribes to re-frame subscriptions and dispatches re-frame events; it contains no game logic.

## Interface Contract

### IN

- re-frame subscriptions: zone contents (hand, graveyard, stack, battlefield, exile), mana pool totals, castable-card flags, selection state, history branches, threshold status, phase/turn indicators
- Browser events: mouse clicks, keyboard shortcuts, form inputs

### OUT

- re-frame event dispatches: `::cast-spell`, `::toggle-selection`, `::confirm-selection`, `::activate-ability`, `::advance-phase`, `::fork-at`, `::undo`, and other game/selection/history action events

## Local Changes

Changes to layout, visual styling, component hierarchy, accessibility, or keyboard shortcut bindings are local to this component. Adding a new UI panel or modal that simply subscribes to existing subscriptions and dispatches existing events is fully local. Changes become non-local when a new game action requires a new event handler or a new derived value requires a new subscription.
