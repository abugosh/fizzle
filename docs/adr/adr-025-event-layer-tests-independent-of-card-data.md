# ADR 025: Event Layer Tests Must Be Independent of Card Data

## Status

Accepted

## Context

Historically, correctness of event-layer handlers (`reg-event-db` / `reg-event-fx` registrations) and selection-layer multimethods (`defmethod`s on `build-selection-for-effect`, `execute-confirmed-selection`, `build-chain-selection`, `apply-continuation`) has been proved primarily through card tests. A handler's branches get exercised as side effects of `cast-and-resolve`, `cast-with-target`, `cast-mode-with-target`, and `confirm-selection` calls in ~59 card test files. Dedicated event-layer tests exist for only a handful of modules (phases, abilities, lands, costs, storm, mana-allocation via epic fizzle-87pw and fizzle-wnqj), and even those focus on wiring, not exhaustive branch coverage.

This produces a structural coupling: event-layer mechanism correctness is only demonstrable through data instances (specific cards). Several problems follow.

First, it violates the "data over code" principle documented in CLAUDE.md. Cards are data; the event layer is a mechanism that interprets data. A mechanism whose correctness is only provable through data instances is a mechanism that is not truly separable from its data — which is the definition of complection in the Rich Hickey sense.

Second, it blocks dynamic card loading. The target future for the card library is not a statically bundled registry but a runtime-loaded catalog (see roadmap: "Card Expansion (~100-200 cards)" and the broader goal of opening the tool to player-contributed card data). If event-layer correctness requires the presence of specific cards, the event layer cannot be validated independently of whatever card set happens to be loaded at runtime.

Third, it makes failure diagnosis harder. A wiring regression in `::cast-spell` currently surfaces as a confusing downstream card-test failure — the test reports that Dark Ritual didn't produce mana, not that `::cast-spell` stopped consuming `:history/pending-entry`. Localized event-layer failures are easier to diagnose and fix.

Fourth, it hides branches that card tests happen not to exercise. A `reg-event-db` handler with six branches might have only three exercised by the card suite because no card happens to trigger the other three. Those branches carry silent regression risk.

## Decision

Event-layer tests must prove handler and defmethod correctness independently of card tests. The concrete bar is the **deletion test**:

> If `src/test/fizzle/cards/**` were deleted entirely, event-layer tests must still cover every behaviorally distinct branch in every `reg-event-db` handler and every `defmethod` in the `events/` tree.

This applies to both shapes of event-layer code:

**Pattern A — `reg-event-db` / `reg-event-fx` wiring** (handlers under `events/`): tested via `rf/dispatch-sync` against a test app-db, asserting observable state changes. Every behaviorally distinct branch — happy path, every guard, every state-dependent fork, every failure mode — has at least one dedicated test.

**Pattern B — Multimethod registration** (`defmethod`s on selection pipeline multimethods): tested via production-path entry (`sel-spec/set-pending-selection` → `sel-core/confirm-selection-impl` or `th/confirm-selection`), asserting observable DB effects. Every registered method is exercised for every behaviorally distinct input shape it handles.

Tests must enter through production dispatch paths. Direct invocation of handler functions or defmethods with fabricated input is prohibited — fabricated input risks drifting from spec-validated production input, and direct invocation bypasses the chokepoint validation established by ADR-019.

Card tests remain valuable for card correctness (oracle fidelity, card-specific interactions, storm counting, etc.) but must not be load-bearing for event-layer correctness. The two test suites become orthogonal: card tests prove cards are correct; event tests prove the mechanism is correct; neither depends on the other for branch coverage.

## Consequences

- Event-layer correctness becomes provable without bundled card data. This is a precondition for dynamic card loading: the mechanism can be validated independently of whatever catalog is loaded at runtime.
- Some intentional duplication of coverage between card-layer and event-layer tests. The layers prove different things at different abstraction levels — a handler branch covered by both a card test (end-to-end behavior) and an event test (mechanism wiring) is not redundant.
- Higher total test count in the `events/` tree. Epic fizzle-z9ep is the initial implementation effort, closing the gap for `casting.cljs` and the 6 remaining selection submodules.
- Reviewers enforce the deletion test as part of test review for any new event-layer work. When reviewing an event-layer test file, the question is: "If card tests disappeared, would this file still prove the handlers and defmethods work?"
- Failure diagnosis improves. Wiring regressions surface as localized event-test failures rather than confusing downstream card-test failures.
- Future event-layer refactors have a real safety net. A refactor of `selection/core.cljs` (ADR-024 notes its high rate of change) can be validated without running the full card suite — event-layer tests cover the mechanism independently.
- This ADR complements ADR-019 (pure selection pipeline): ADR-019 ensures validation lives in the impl so tests exercise it automatically; ADR-025 ensures tests actually exercise the impl without leaning on card-suite coverage.
- The "data over code" principle is enforced structurally, not just in the production code. Tests are now organized by mechanism (events) vs data (cards), matching the production split.
