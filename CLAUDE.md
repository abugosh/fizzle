# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fizzle is a ClojureScript-based Magic: The Gathering combo deck practice tool with fork/replay capabilities. It's designed for storm combo players to get efficient practice reps, with features like hand sculpting, simplified opponent AI, and tactics training (saving positions as puzzles).

**Target Format:** Premodern combo decks

**Key docs:**
- [fizzle-design.md](fizzle-design.md) — Design vision and technical architecture
- [fizzle-roadmap.md](fizzle-roadmap.md) — Implementation roadmap and backlog

## Build Commands

```bash
# Install dependencies
npm install

# Start development server with hot reload
npx shadow-cljs watch app

# Build for production
npx shadow-cljs release app

# Start REPL
npx shadow-cljs cljs-repl app
```

## Development Commands

Use these make commands instead of hand-rolling shadow-cljs calls:

```bash
# Development
make repl      # Start node REPL - ready to eval immediately
make dev       # Start browser dev server with hot reload
make test      # Run all tests
make clean     # Remove build artifacts (out/, .shadow-cljs/)

# Validation
make lint      # Run clj-kondo linter
make fmt-check # Check code formatting (cljstyle)
make fmt       # Auto-fix code formatting
make validate  # Run lint + format-check + tests
```

**IMPORTANT**: Always use `make test` instead of raw shadow-cljs commands.

## Tech Stack

| Layer | Technology |
|-------|------------|
| Build | shadow-cljs |
| UI Framework | Reagent (React wrapper with hiccup syntax) |
| State Management | re-frame (event sourcing, time-travel debugging) |
| Local Database | Datascript (in-memory Datalog) |
| Styling | Tailwind CSS |

## Architecture

### Why This Stack?

- **re-frame's event model** is the action log - every game action dispatches an event, enabling fork/replay
- **Datascript** enables declarative queries (e.g., "What cards in hand can I cast?" is a query, not imperative code)
- **ClojureScript's immutability** makes fork free - keeping a reference to old state uses structural sharing

### Source Layout

```
src/
└── fizzle/
    ├── core.cljs           # Entry point
    ├── db/                 # Datascript schema, queries
    ├── events/             # re-frame events (game actions, UI, tactics)
    ├── subs/               # re-frame subscriptions
    ├── engine/             # Game rules, mana, stack, effects, zones
    ├── cards/              # Card definitions as EDN data
    ├── bots/               # Simplified opponent AI (goldfish, burn, control, discard)
    ├── history/            # Fork/replay system
    ├── tactics/            # Puzzle save/load
    └── views/              # Reagent components
```

### Key Design Principles

1. **Data over code** - Cards are data, effects are data, game state is data. The engine interprets data; it doesn't encode card-specific logic.

2. **Simplify ruthlessly** - This is a practice tool, not MTGO. Skip rare/irrelevant rules interactions. Trust the player to know MTG rules.

3. **Immutability enables features** - Fork/replay is trivial because state is immutable.

### Effect System

Cards define effects as EDN data structures that the engine interprets:

```clojure
{:effect/type :add-mana
 :effect/mana {:black 3}
 :effect/condition {:condition/type :threshold}}
```

Effect types include: `:add-mana`, `:draw`, `:discard`, `:mill`, `:tutor`, `:return-from-graveyard`, `:exile-self`, `:sacrifice`, `:drain`, `:each-player`

### Fork/Replay System

Every game action is an event. Game state is a pure reduction over event history. This enables:
- **Replay**: Reduce events 0..N to reconstruct any state
- **Fork**: Branch the event list at any point
- **Undo**: Pop events off the history

### Bot System

Bots implement the `IBot` protocol with methods: `get-archetype`, `get-clock`, `should-act?`, `choose-action`, `get-deck`. Available archetypes: goldfish, burn, control, discard.

## Architecture Decision Records

ADRs capture the *why* behind architecturally significant decisions—those affecting structure, dependencies, interfaces, or non-functional characteristics. They help future developers understand rationale rather than blindly accepting or carelessly reversing past choices.

**Location:** `docs/adr/` — use the `adr` CLI tool to create and manage records.

```bash
adr new "Use Datascript for game state"
adr list
```

**When to create:** Document decisions that are architecturally significant. Not every choice needs an ADR—focus on decisions where the rationale matters for the future.

**Format:** Keep ADRs to 1-2 pages. Write in full sentences as if addressing a future developer.

- **Title** — Short noun phrase (e.g., "ADR 5: Deployment on Supabase")
- **Context** — Value-neutral description of forces at play
- **Decision** — Active voice: "We will..."
- **Status** — Proposed, Accepted, Deprecated, or Superseded by ADR-XXXX
- **Consequences** — All impacts: positive, negative, and neutral

**Conventions:**
- Number sequentially; never reuse numbers
- Superseded ADRs remain in the repository for historical context
- Store alongside code in version control

ADRs complement `fizzle-design.md` (design vision) and `fizzle-roadmap.md` (implementation plan). ADRs document decisions that refine or deviate from either during implementation.

## Conventions

- Card definitions go in `src/fizzle/cards/` as pure EDN data
- Use re-frame events for all game state changes
- Subscriptions compute derived state (e.g., castable cards, threshold status)
- Views are pure Reagent components that subscribe to state
