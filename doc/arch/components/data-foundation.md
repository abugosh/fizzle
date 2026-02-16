# Data Foundation

## Volatility Axis
Storage technology changes — the lowest volatility axis. Schema changes ripple everywhere, so this component changes least frequently.

## Layer: Resource Accessor
Defines the data model and provides query access. No business logic — pure Datascript schema and query functions.

## Interface Contract
- IN: Entity data from Card Pool (card definitions transacted into db)
- OUT: Schema consumed by all components; query functions (`get-hand`, `get-objects-in-zone`, `get-mana-pool`, `get-all-stack-items`, `get-grants`) called by Interpretation Core, Game Orchestration, and Presentation

## Responsibility
Define the Datascript schema (13+ entity types: cards, objects, players, game state), provide pure query functions for data access, and handle database initialization. Single interface for raw data access (ADR-003). Schema attribute keywords (`:object/id`, `:object/zone`, etc.) are implicit shared vocabulary across all components — this is an accepted architectural choice aligned with Clojure's data-driven idiom (ADR-006).

## What Changes Should Be Local
- Adding a new entity type or attribute to the schema
- Adding or modifying a query function
- Changing database initialization logic

## Modules
- `src/fizzle/db/schema.cljs` — Entity type definitions
- `src/fizzle/db/queries.cljs` — Pure Datascript query functions (fan-in: 24 importers)
- `src/fizzle/db/init.cljs` — Database initialization
