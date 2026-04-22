# ADR 027: Consolidate untap-all-permanents into single chokepoint

Date: 2026-04-22

## Status

Accepted

## Context

Fizzle currently has two definitions of `untap-all-permanents`:

- `engine/triggers.cljs:95` — private, called from `resolve-trigger :untap-step` (line 176). This is the production path — every turn-based untap step flows through this function.
- `events/phases.cljs:72` — public, reached only by its own tests (`src/test/fizzle/events/phases_test.cljs`). No production caller.

Both are structurally identical: same Datascript query for `[:object/controller ?p] [:object/zone :battlefield] [:object/tapped true]`, same `d/db-with` transaction flipping `:object/tapped` to false.

The duplication surfaced during the intuition audit for the `:untap-restriction` static-ability design (Tsabo's Web epic). That design adds a "can this permanent untap?" predicate to the untap pipeline. With two implementations, a predicate added to one silently bypasses the other — a latent correctness gap that would activate the moment any new caller reached the `phases.cljs` version.

The deeper principle is from ADR-026 (single-point trigger registration): one chokepoint per operation. Untap-the-battlefield-for-a-player is one operation. Two implementations divide responsibility and force every future contributor to ask "which one is the real one, and does my change need to go in both?" The only way a predicate-gated untap, a trigger on untap, or any future untap-related logic stays coherent is if there's exactly one function doing the work.

## Decision

We will consolidate `untap-all-permanents` into a single definition in `engine/triggers.cljs` (the existing production path). The `events/phases.cljs:72` copy will be deleted. The three test assertions in `src/test/fizzle/events/phases_test.cljs` (tests 12, 13, 14) that call `phases/untap-all-permanents` directly will either migrate to call the production path (via the `:untap-step` trigger resolver) or be rewritten to drive the untap through events — whichever preserves the invariant being tested.

The consolidated `untap-all-permanents` in `engine/triggers.cljs` becomes the sole site where:
- The Datascript query for tapped-battlefield-permanents controlled by the active player runs.
- The `[:db/add ?e :object/tapped false]` transaction is built.
- Future untap-gating predicates (including `:untap-restriction`) are applied.

Other untap code paths (`:untap-all` effect at `effects/zones.cljs:240`, `:untap-lands` effect at `effects/zones.cljs:192`, single-permanent untap at `events/lands.cljs:79`, entry-untap at `zones.cljs:125`) are **not** consolidated into this chokepoint. They represent distinct operations (targeted effects, voluntary untap) and by MTG rules must not be subject to the "during its controller's untap step" restrictions (e.g., Tsabo's Web does not prevent Turnabout from untapping an opponent's creature).

## Consequences

- Adding a predicate check like `permanent-untap-restricted?` has exactly one insertion point. No hidden path bypasses it.
- The distinction between "turn-based untap step" (gated by restrictions) and "explicit untap effects" (not gated) becomes structural rather than conventional — it lives in which function is called, not in documentation.
- `events/phases.cljs` loses a public function. Any external tooling or future caller that expected `phases/untap-all-permanents` to exist will find it gone; the replacement is to dispatch the `:untap-step` trigger or call the consolidated chokepoint directly.
- The three phases tests that exercised the deleted function need rewriting. The invariant they test (active-player's tapped battlefield permanents become untapped, opponent's do not) is preserved; only the call site changes.
