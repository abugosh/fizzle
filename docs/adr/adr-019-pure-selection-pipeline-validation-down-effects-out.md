# ADR 019: Pure Selection Pipeline — Validation Down, Effects Out

## Status

Accepted

## Context

A `contains?`-on-vector bug in `validate-selection` shipped undetected despite 3122 passing tests. The bug made modal spell selection silently fail in production. All card tests passed because test helpers bypass the selection pipeline entirely.

Investigation revealed a structural pattern, not a one-off gap. The selection pipeline splits behavior between `-impl` functions (intended as pure, testable cores) and event handler wrappers. Validation, deferred entry processing, and side effects live exclusively in the wrappers. Any code that calls an impl directly — including test helpers — silently skips production behavior.

Three specific instances:

1. **`confirm-selection-impl`** does not validate. Validation runs only in `confirm-selection-handler`. The `confirm-selection` test helper calls the impl directly, never exercising validation. Deferred entry processing is similarly wrapper-only.

2. **`toggle-selection-impl`** calls `rf/dispatch` for auto-confirm, coupling a pure transformation to the re-frame event bus. This makes the toggle→confirm path untestable without re-frame, which is why `cast-mode-with-target` bypasses toggle entirely and writes directly to datascript.

This is a similar pattern to the bot interceptor inference problem (ADR-018): critical behavior inferred or enforced at the wrong layer, producing silent failures that tests cannot catch.

A related issue exists in `apply-continuation :cast-after-spell-mode` (casting.cljs:236), where `rf/dispatch` works around a circular dependency between casting, director, and priority-flow. This is tracked separately as fizzle-81fi — the circular dependency requires dependency graph restructuring and the workaround is documented and tested.

## Decision

We will make `-impl` functions self-contained by pushing validation and lifecycle logic down into them, and removing side effects.

`confirm-selection-impl` will absorb validation and deferred entry processing. The handler wrapper becomes a thin pass-through. Test helpers that already call the impl will exercise validation automatically.

`toggle-selection-impl` will return data indicating whether auto-confirm should fire, rather than dispatching a re-frame event. The event handler reads the signal and dispatches. Toggle becomes a pure function testable without re-frame.

Test helper `cast-mode-with-target` will be rewritten to use the selection pipeline (build → toggle → confirm) instead of direct datascript writes.

## Consequences

- `-impl` functions become genuinely self-contained. The convention "impl = pure, testable core" is enforced by the code, not by caller discipline. Pipeline bypass bugs like the `contains?` issue become structurally impossible.
- Test helpers exercise the same validation and lifecycle logic as production without needing changes — they already call the impl.
- `confirm-selection-impl` grows slightly, absorbing ~10 lines from the handler.
- The toggle return type changes, requiring all event handler callers to destructure and dispatch.
- This establishes a convention: `-impl` functions must include all logic needed for correctness. Wrappers may add orchestration (re-frame dispatch) but must not be the sole location of validation or business logic.
