# Card Pool

## Volatility Axis
New card definitions — the most frequent change in the system. Each new card adds EDN data (effects, abilities, triggers, keywords) interpreted by Interpretation Core multimethods.

## Layer: Resource Accessor
Card definitions are pure data consumed by the engine. No business logic, no computation. Cards define *what* to do; the engine decides *how*.

## Interface Contract
- IN: None (leaf component, no dependencies)
- OUT: Card entity maps consumed via Datascript queries and multimethod dispatch

## Responsibility
Define all playable cards as EDN data structures. Each card specifies mana cost, types, effects, abilities, triggers, and keywords. The engine interprets this data — cards never encode behavior.

## What Changes Should Be Local
- Adding a new card definition
- Modifying a card's effects, costs, or abilities
- Adding a new card category file

## Modules
- `src/fizzle/cards/*.cljs` (~9 files, 58 card definitions)
