# Fizzle

**Fizzle, fork, try again.**

A combo deck practice tool for [Premodern](https://premodernmagic.com/) Magic: The Gathering. Goldfish your storm turns, fork game state to explore different lines, and replay to build muscle memory.

> **Early development** — the engine is solid, the card pool is growing, and the sharp edges are getting filed down.

**[Try it live](https://alexbugosh.com/fizzle/)**

---

## What Is This?

If you've ever wanted to practice storm turns without shuffling 60 cards and tracking a pile of dice, Fizzle is for you. It handles the bookkeeping — mana pool, storm count, graveyard tracking, threshold — so you can focus on sequencing.

The killer feature is **fork/replay**. Made a mistake three spells ago? Fork the game state, try a different line, compare results. It's like `git branch` for your combo turn.

### Features

- **Fork & Replay** — Branch game state at any point, explore alternate lines, undo freely
- **Hand Sculpting** — Set up the exact opening hand you want to practice with
- **Configurable Opponents** — Goldfish (does nothing) or Burn bot (20 Mountains, 40 Lightning Bolts, bolts your face) — bots play through the full engine, no shortcuts
- **Storm Tracking** — Automatic storm count, copies on the stack, the whole deal
- **Mana Management** — Full mana pool with color tracking, cost reduction, threshold awareness
- **Priority System** — Yield, yield-all, hold-priority controls for both players
- **Hypergeometric Calculator** — Built-in probability calculator for draw odds

### Card Pool

Fizzle started life as a way to practice [Iggy Pop](https://www.tcdecks.net/archetype.php?archetype=Iggy+Pop&format=Premodern&src=all) (the Tendrils of Agony storm deck), and the card pool is expanding into other Premodern combo archetypes.

Currently 90+ cards across all colors, including:

- **Combo pieces** — Dark Ritual, Cabal Ritual, Lion's Eye Diamond, Lotus Petal, Tendrils of Agony (via Brain Freeze/storm), Ill-Gotten Gains
- **Card selection** — Brainstorm (Impulse, Opt, Sleight of Hand), Intuition, Merchant Scroll, Cunning Wish, Burning Wish
- **Interaction** — Counterspell, Daze, Duress, Stifle, Abeyance, Orim's Chant
- **Mana base** — Fetch lands, pain lands, City of Brass, City of Traitors, Gemstone Mine, Cephalid Coliseum
- **Mechanics** — Storm, flashback, threshold, alternate costs, triggered abilities, activated abilities

---

## Getting Started

### Use It Now

**[alexbugosh.com/fizzle](https://alexbugosh.com/fizzle/)** — no install needed.

### Run Locally

Prerequisites: [Node.js](https://nodejs.org/) and [Java](https://adoptium.net/) (for shadow-cljs/ClojureScript compilation).

```bash
git clone https://github.com/abugosh/fizzle.git
cd fizzle
npm install
make dev
```

This starts the shadow-cljs dev server and Tailwind CSS watcher. Open `http://localhost:8080` in your browser.

### Other Commands

```bash
make test       # Run the test suite (~3000 tests)
make lint       # Run clj-kondo linter
make fmt        # Auto-fix formatting (cljstyle)
make validate   # lint + format check + tests (the full pre-commit gauntlet)
make repl       # Start a ClojureScript REPL
make release    # Production build
make clean      # Remove build artifacts
```

---

## Tech Stack

| Layer | Choice | Why |
|-------|--------|-----|
| Language | ClojureScript | Immutable data structures make fork/replay trivial — keeping a reference to old state is free (structural sharing) |
| Build | shadow-cljs | Best-in-class ClojureScript build tool, hot reload, npm interop |
| UI | Reagent | React wrapper with hiccup syntax — concise, reactive, functional |
| State | re-frame | Event sourcing baked in — every game action is an event, enabling time-travel |
| Database | Datascript | In-memory Datalog — "which cards in hand can I cast?" is a query, not imperative code |
| Styling | Tailwind CSS | Utility-first, no context switching to CSS files |

## Architecture

The design philosophy is "what would Rich Hickey do?" — prefer values over places, data over code, composition over complecting. If something feels tangled, untangle it.

### Core Ideas

**Everything is data.** Cards are EDN maps, effects are EDN maps, game state lives in Datascript. The engine interprets data; it doesn't encode card-specific logic. If there's a `case` on a card name somewhere, something went wrong.

**Events are the source of truth.** Every game action dispatches a re-frame event. Game state is a pure reduction over event history. This is what makes fork/replay work — it's not a feature bolted on, it's a consequence of the architecture.

**Fork is free.** ClojureScript's persistent data structures use structural sharing. "Save a copy of the entire game state" is just keeping a reference. No cloning, no serialization, no cost.

### Effect System

Cards declare their effects as pure data:

```clojure
;; Dark Ritual
{:effect/type :add-mana
 :effect/mana {:black 3}}

;; Cabal Ritual (with threshold)
{:effect/type :add-mana
 :effect/mana {:black 3}
 :effect/condition {:condition/type :threshold}
 :effect/upgraded-mana {:black 5}}
```

The engine has a multimethod-based effect executor that interprets these declarations. Adding a new effect type means registering a new method, not modifying existing code. There are 30+ effect types covering mana generation, card draw, tutoring, zone movement, storm copies, and more.

### Selection System

Interactive effects (tutoring, discarding, scrying) use a builder/executor pattern with multimethods:

1. **Builder** constructs a selection state map (what choices are available, validation rules, lifecycle)
2. **UI** renders the selection and collects player input
3. **Executor** processes the confirmed selection and updates game state

Selection types are dispatched via `derive`-based hierarchies grouped into patterns: zone-pick (choose cards from a zone), accumulator (allocate mana/storm copies), and reorder (scry/peek-and-reorder). New selection types register methods on existing multimethods — the mechanism doesn't change, only the policy.

### Bot System

Bots implement the `IBot` multimethod protocol and dispatch the same re-frame events as human players — there's no parallel "bot engine." The interceptor in `bots/interceptor.cljs` fires after every game-state mutation where the bot holds priority, queuing the bot's decision through the full engine path.

### Source Layout

```
src/main/fizzle/
  core.cljs              # Entry point
  db/                    # Datascript schema and queries
  engine/                # Game rules: mana, stack, effects, zones, priority
    effects/             # Effect executors (organized by domain)
  events/                # re-frame event handlers
    selection/           # Selection system (6 modules)
  cards/                 # Card definitions as EDN data
    black/ blue/ red/    # Organized by color identity
    white/ green/
    artifacts/ lands/
    multicolor/
    registry.cljs        # Card registry (sole import point)
  bots/                  # Opponent AI: protocol, definitions, rules, interceptor
  history/               # Fork/replay system
  views/                 # Reagent UI components
    selection/           # Selection UI (zone-pick, accumulator, reorder)
  subs/                  # re-frame subscriptions (derived state)
```

### Architecture Decision Records

Significant design decisions are documented as ADRs in `docs/adr/`. These capture the *why* behind choices — useful if you're wondering "why is it done this way?" rather than "what does it do?"

For the full architectural deep dive, see [fizzle-design.md](fizzle-design.md).

---

## Testing

Fizzle has a comprehensive test suite (~3000 tests, ~9000 assertions) covering cards, engine mechanics, event handlers, and integration scenarios.

Every card has a dedicated test file with mandatory coverage categories: card definition verification, cast/resolve happy paths, cannot-cast guards, storm count tracking, and edge cases. Tests use production code paths via test helpers — no reimplementing handlers in tests.

```bash
make test       # Run everything
make validate   # The full gauntlet: lint + format + tests
```

---

## Contributing

Contributions are welcome! But please **open a GitHub issue or reach out on Discord first** before starting work. This is an opinionated codebase with specific architectural patterns, and I'd rather help you find the right approach than review a PR that fights the grain.

Good starting points:
- [fizzle-design.md](fizzle-design.md) — The design vision and technical architecture
- [CLAUDE.md](CLAUDE.md) — Development conventions and patterns (yes, it's named for the AI — it doubles as a contributor guide)
- `docs/adr/` — Architecture decision records

---

## License

[MIT](LICENSE)

---

Built by [Alex Bugosh](https://github.com/abugosh)
