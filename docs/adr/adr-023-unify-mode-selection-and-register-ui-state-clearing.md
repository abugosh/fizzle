# ADR 023: Unify Mode Selection and Register UI State Clearing

## Status

Accepted

## Context

Two separate tensions converge on the same app-db state:

**1. Parallel mode selection system.** Pre-cast mode choice for modal spells uses `:game/pending-mode-selection` — a separate app-db key with its own event handlers (casting.cljs), view rendering (modals.cljs), and clearing logic. All other player choices use the standard selection pipeline (`:game/pending-selection`). ADR-014 intended to unify these, and the `fizzle-s9pu` task partially integrated mode selection (`build-spell-mode-selection` + `confirm-selection-impl`), but the initial "pick a mode" step still uses the separate key.

**2. History module enumerates selection-specific keys.** `clear-stale-ui-state` in history/core.cljs:70-79 directly dissocs 3 selection-owned keys (`:game/pending-selection`, `:game/selected-card`, `:game/pending-mode-selection`) plus 2 history-owned keys. This creates a hidden dependency: adding new selection UI state requires updating the history module. The history system otherwise has no import from or reference to the selection system.

Unifying mode selection eliminates one of the 3 keys. Switching to registration-based clearing eliminates the enumeration entirely.

## Decision

We will:

1. **Unify mode selection into the standard selection pipeline.** The pre-cast mode choice becomes a standard selection type (`:spell-mode-choice`) using `build-selection-for-effect` and `execute-confirmed-selection`. `:game/pending-mode-selection` is removed. This completes the intent of ADR-014.

2. **Switch to registration-based UI state clearing.** The selection system and casting system register clear-state callbacks with the history system at initialization. History calls all registered callbacks during navigation (undo, redo, jump) instead of enumerating keys directly. The registration mechanism lives in the history module; registrants push their clear-fn to it.

## Consequences

**Positive:**
- One selection system, one app-db key (`:game/pending-selection`), one event handling path for all player choices.
- History module no longer enumerates selection-specific keys — adding new selection UI state keys does not require updating history.
- The hidden dependency between history and selection is replaced by an explicit registration protocol.

**Negative:**
- Mode selection must fit the standard selection pipeline's lifecycle model (:standard/:finalized/:chaining). If mode choice has unique requirements (e.g., preview of mode effects before confirming), the pipeline may need a new lifecycle pattern.
- Registration introduces initialization ordering: registration must happen before the first history navigation. Currently covered by re-frame's module-loading order.

**Neutral:**
- Views that render mode selection may need updating to use the standard selection subscription instead of reading `:game/pending-mode-selection` directly.
- `:game/selected-card` remains as a separate key (not a selection type — it's a UI highlight, not a player choice).
