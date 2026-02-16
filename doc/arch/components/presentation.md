# Presentation

## Volatility Axis
UX/UI changes and display logic. Changes when the user interface needs new views, modified layouts, or different interaction patterns.

## Layer: Boundary
The system boundary where users interact. Views render state and collect input; subscriptions compute derived state by joining raw data (Data Foundation) and game rules (Interpretation Core).

## Interface Contract
- IN: Re-frame subscriptions providing game state, derived computations, and UI state
- OUT: Re-frame event dispatches to Game Orchestration; user-visible HTML/CSS

## Responsibility
17 view modules render the game UI: hand, battlefield, stack, graveyard, mana pool, controls, modals, history sidebar, setup screen, etc. 4 subscription modules compute derived state by calling Interpretation Core directly for game rule queries (can-cast?, sort-cards, land-card?) per ADR-003. Views are pure Reagent components — no game logic.

## What Changes Should Be Local
- Adding a new view component
- Changing layout or styling
- Adding a new subscription for derived state
- Modifying interaction patterns (click handlers, modals)

## Modules
- `src/fizzle/views/*.cljs` (17 files: common, battlefield, hand, stack, graveyard, mana-pool, zone-counts, controls, phase-bar, modals, opponent, history, game-over, setup, opening-hand, import-modal, card-styles)
- `src/fizzle/subs/*.cljs` (4 files: game, setup, history, opening-hand)
