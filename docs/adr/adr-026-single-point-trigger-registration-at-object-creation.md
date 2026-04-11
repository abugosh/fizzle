# ADR 026: Single-Point Trigger Registration at Object Creation

## Status

Accepted

## Context

Card triggers (`:card/triggers`) define triggered abilities that fire in response to game events — a land entering the battlefield, a permanent becoming tapped, a card moving between zones. The trigger system has two responsibilities: registration (creating trigger entities in the database linked to their source object) and dispatch (querying triggers when events occur and deciding which fire).

Prior to this decision, trigger registration was performed at five separate sites: `events/init.cljs` (blanket registration at game start), `events/lands.cljs` (play-land), `engine/resolution.cljs` (permanent spell resolution), `engine/effects/zones.cljs` (effect-driven zone changes), and `sharing/restorer.cljs` (snapshot restore). Each site independently checked `(seq (:card/triggers card))` and called `trigger-db/create-triggers-for-card-tx`.

This scattered registration produced a concrete bug (fizzle-lmro): commit d2675e9 added blanket init-time registration to support Gaea's Blessing's library trigger, but the four ETB registration sites remained. Every card with triggers — City of Traitors, City of Brass, Cloud of Faeries, Xantid Swarm — had its triggers registered twice: once at init, once when the card entered the battlefield. City of Traitors' sacrifice trigger fired twice when a second land was played.

The deeper issue is architectural. Registration scattered across five sites violates the "one place for one concern" principle. Each site independently reimplements the same pattern (check for triggers, call the registration function, transact). Adding a new entry path for objects — tokens, copies, reanimation effects — requires discovering and updating the correct registration site, or creating a sixth one.

A separate but related concern: the trigger dispatch system (`get-triggers-for-event`) queries all triggers matching an event type and applies filter matching, but performs no zone-relevance check. If a trigger is registered while its source object is in hand (which now happens at object creation), observer triggers could fire from the wrong zone. MTG rules state that permanent abilities only function on the battlefield, with explicit exceptions for abilities that say they work elsewhere (Gaea's Blessing from library, Squee from graveyard). The dispatch system must respect this.

## Decision

We will register triggers at exactly one point: object creation, via `build-object-tx` in `engine/objects.cljs`. When a card has `:card/triggers`, `build-object-tx` calls `create-triggers-for-card-tx` and embeds the resulting trigger entities in the returned transaction data via the `:object/triggers` component attribute. All five scattered registration sites are removed.

To prevent triggers from firing when their source is in the wrong zone, `get-triggers-for-event` in `engine/trigger_db.cljs` applies a zone-relevance check using two categories:

**Self-scoped triggers** — those whose `:trigger/filter` or `:trigger/match` contains `{:event/object-id :self}` — skip the zone check. The match-map already encodes zone relevance: Gaea's Blessing's match-map requires `:event/from-zone :library`, a Parallax Wave LTB trigger requires `:event/from-zone :battlefield`, and cycling triggers require `:event/from-zone :hand`. The `:event/object-id :self` constraint ensures the trigger only fires for events involving the trigger's own object, and the zone fields in the match-map constrain which transitions are relevant.

**Observer triggers** — those without `{:event/object-id :self}` in filter or match-map, such as City of Traitors watching for other lands entering — must pass a zone check: the source object's current zone must match the trigger's `:trigger/active-zone` (default `:battlefield`). This field can be overridden in card trigger data for cards like Squee, Goblin Nabob or Genesis that have graveyard-active observer triggers.

Game-rule triggers (`:trigger/always-active? true`, no source object) skip the zone check entirely.

## Consequences

- `build-object-tx` is the sole registration chokepoint. Any code path that creates a game object — init, token creation, snapshot restore, future copy effects — automatically registers triggers. The category of "forgot to register triggers at a new entry point" bugs is eliminated.
- `build-object-tx`'s interface widens: it now requires a `db` parameter for trigger filter resolution (`:self-controller` → player-id mapping). All callers (init, tokens, restorer, test helpers) pass the database. This is appropriate — game objects exist in the context of the game.
- The dispatch-time zone check adds one query per candidate trigger in `get-triggers-for-event`. For the current Premodern card pool (5 cards with triggers), this is negligible. A future card pool with many observer triggers would want to profile this path.
- Self-scoped triggers work correctly across all zone transitions without modification: ETB, LTB, dies, cycling, and mill triggers all rely on match-map zone fields, not the zone check. Adding a new self-scoped trigger type requires only card data — no dispatch changes.
- Observer triggers default to battlefield-active. Future cards with graveyard-active observer triggers (Squee, Genesis, Dragon Breath) declare `:trigger/active-zone :graveyard` in their card trigger data. The dispatch system reads this field, defaulting to `:battlefield` when absent.
- The five removed registration sites no longer need to be kept in sync. Spell resolution (`move-resolved-spell`), land play (`play-land`), and effect-driven zone changes are simpler — they move objects and dispatch events, without also managing trigger registration.
