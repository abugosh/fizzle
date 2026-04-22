# ADR 028: `:card/triggers` for ETB triggered abilities, `:card/etb-effects` for replacement-style entry

Date: 2026-04-22

## Status

Accepted

## Context

Fizzle's card DSL has two parallel paths for "something happens when a permanent enters the battlefield":

- `:card/etb-effects` — a vector of effects executed directly during `move-to-zone` (consumed in `events/lands.cljs:50-60`). Does not use the stack; does not register a trigger. Currently used by one card: Gemstone Mine, which enters with three mining counters.
- `:card/triggers` with `:trigger/type :enters-battlefield` — a trigger registered on object creation (ADR-026), dispatched via `get-triggers-for-event`, and resolved through the stack. Currently used by one card: Cloud of Faeries, whose ETB untaps up to two lands.

The paths are not interchangeable. An `:etb-effects` entry is a side effect of entering — it cannot be countered, cannot be responded to, and is invisible to the priority/stack flow. An ETB trigger goes on the stack per MTG rule 603.6a and is subject to the full triggered-ability pipeline.

Most MTG oracle text distinguishes these cases with trigger-word framing: "When X enters, do Y" is a triggered ability (stack-using). Replacement-style text ("X enters with N counters," "X enters tapped," "If X would enter, instead Z") describes a replacement effect — evaluated at the moment of zone change, not as a stack trigger.

Without an explicit rule about which path each card uses, new cards risk landing on the wrong one. Tsabo's Web surfaced this: its oracle says "When this artifact enters, draw a card" (trigger-shape), but the initial brainstorm design used `:card/etb-effects` (replacement-shape) — a category error that would produce an uncountered, off-stack draw where MTG models a stack trigger.

## Decision

We will adopt this convention for new cards and retroactively when existing cards are touched:

- **Use `:card/triggers` with `:trigger/type :enters-battlefield`** for any ability whose oracle text begins with "When ... enters" or is otherwise a triggered ability by MTG rule 603. These go on the stack, are visible to priority, and can in principle be countered. Cloud of Faeries is the reference case.

- **Use `:card/etb-effects`** only for replacement effects that modify the act of entering itself: "enters with N counters," "enters tapped," "enters as a copy of Y." These are evaluated during `move-to-zone` and by MTG rules do not use the stack. Gemstone Mine is the reference case.

When a card has both a replacement-style entry modifier and a triggered ETB, both fields appear on the card: `:card/etb-effects` for the replacement, `:card/triggers` for the trigger.

For the Tsabo's Web implementation specifically, the "draw a card" ability uses `:card/triggers` with `:trigger/type :enters-battlefield`, matching its oracle text ("When this artifact enters, draw a card"). The card has no `:card/etb-effects`.

## Consequences

- New cards have a clear rule for choosing the right field. Oracle text is the guide: trigger-word framing ("When") → `:card/triggers`; replacement framing ("enters with," "enters tapped") → `:card/etb-effects`. Category errors become audit-detectable.
- Fidelity improves for responses and stack visibility — ETB triggers show up in priority flow, the same way any other triggered ability does. In a practice tool this is rarely observable today, but it matches MTG semantics.
- `:card/etb-effects` narrows to its proper scope: replacement-style entry modifiers only. Future effort to model "enters tapped" lands or "enters as a copy" effects slots into this field without collision.
- Cards already in the codebase are not audited retroactively in this ADR. Gemstone Mine's current use is correct under the new rule. Any existing card found misusing `:card/etb-effects` for a trigger-shape oracle is fixed when that card is next touched.
- The DSL reference (`docs/card-dsl.md`) should be updated to record this convention. Deferred to a doc task under the next card touching either field.
