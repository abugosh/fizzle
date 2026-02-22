# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fizzle is a ClojureScript-based Magic: The Gathering combo deck practice tool with fork/replay capabilities. It's designed for storm combo players to get efficient practice reps, with features like hand sculpting, configurable opponent scenarios, and tactics training (saving positions as puzzles).

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
    ├── cards/              # Card definitions (per-card files in color-identity subdirs)
    │   ├── black/          # Black cards (e.g., dark_ritual.cljs)
    │   ├── blue/           # Blue cards (e.g., brain_freeze.cljs)
    │   ├── white/          # White cards
    │   ├── red/            # Red cards
    │   ├── green/          # Green cards
    │   ├── multicolor/     # Multicolor cards
    │   ├── lands/          # Lands (e.g., city_of_brass.cljs, basic_lands.cljs)
    │   ├── artifacts/      # Colorless artifacts (e.g., lotus_petal.cljs)
    │   └── registry.cljs   # Requires all card namespaces, exports all-cards
    ├── bots/               # Opponent AI: protocol, definitions (goldfish, burn), rules, interceptor
    ├── history/            # Fork/replay system
    └── views/              # Reagent components
```

Each card file exports a `def` named `card` (or `cards` as a vector for cycles like basic lands). The registry requires all card namespaces explicitly and `engine/cards.cljs` imports only from the registry (ADR-010).

### Key Design Principles

*"What would Rich Hickey do?" — When making architectural decisions, ask whether you're adding essential complexity (inherent to the problem) or accidental complexity (artifact of your approach). Prefer values over places, data over code, composition over complecting.*

1. **Data over code** - Cards are data, effects are data, game state is data. The engine interprets data; it doesn't encode card-specific logic. If you're writing a `case` on a card name, you've gone wrong.

2. **Simplify ruthlessly** - This is a practice tool, not MTGO. Skip rare/irrelevant rules interactions. Trust the player to know MTG rules. But "simple" means decomplected (untangled), not "easy" (familiar). A simple design may take more thought upfront.

3. **Immutability enables features** - Fork/replay is trivial because state is immutable. ALL game state belongs in the immutable Datascript db. Mutable state outside the db (atoms, vars) undermines time-travel and fork correctness. If you're reaching for an atom, ask whether this is really game state in disguise.

4. **Separate mechanism from policy** - The selection system is a mechanism (show choices, collect player input). What happens with those choices is policy. Keep them apart. When adding new player-choice types, you should be registering policy with existing mechanism, not modifying mechanism.

5. **Make protocols explicit** - If the engine and event layer must agree on something (e.g., which effects need player interaction), encode that agreement in data or types, not in comments and conventions. Implicit protocols drift apart silently.

6. **No footguns** - Don't design APIs where a caller can accidentally pass the wrong value and silently get wrong behavior. If identity, context, or configuration is already available on the data being passed, read it from there — don't accept it as a separate parameter that can diverge. Prefer APIs that are impossible to misuse over APIs that are merely documented.

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

Bots implement the `IBot` multimethod protocol (`bots/protocol.cljs`) dispatching on archetype. The bot interceptor (`bots/interceptor.cljs`) drives bot turns automatically via re-frame — bots dispatch the same events as human players through the full engine path. Available archetypes: goldfish (passes on everything), burn (20 Mountain + 40 Lightning Bolt, bolts face). The priority system (`engine/priority.cljs`) handles yield/yield-all/hold-priority for both players.

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

### Card Testing Requirements

Every card must have a dedicated test file (`src/test/fizzle/cards/<card>_test.cljs`) using `fizzle.test-helpers`. Full guide: [docs/testing-strategy.md](docs/testing-strategy.md).

**Mandatory categories (every card):**
- **A. Card definition** — verify ALL fields with exact values (not `some?`/`string?` — those are tautological)
- **B. Cast-resolve happy path** — full cast/resolve cycle through production code
- **C. Cannot-cast guards** — insufficient mana, wrong zone, no valid targets
- **D. Storm count** — casting increments storm

**Conditional categories (when applicable):**
- **E.** Selection/modal tests (player choice cards)
- **F.** Targeting tests (targeted spells)
- **G.** Edge cases (2+ per card: empty zones, partial resources, zone restrictions)
- **H.** Flashback tests
- **I.** Trigger/ability tests

| Card type | Min tests |
|-----------|-----------|
| Simple spell | 5 |
| Targeted spell | 8 |
| Selection spell | 8 |
| Land with ability | 8 |
| Flashback/Storm spell | 12 |

**Top anti-patterns:**
1. Reimplementing production handlers in tests (test through `rules/cast-spell`, not manual copies)
2. Copy-pasted test variants that differ only by parameter (use `doseq`)
3. Tautological assertions (`(is (some? x))` instead of `(is (= expected x))`)
