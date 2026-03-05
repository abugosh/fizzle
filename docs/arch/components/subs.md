# Subscriptions

## Responsibility

re-frame subscription layer that computes derived state from the raw Datascript database. Subscriptions answer questions like "which cards in hand are currently castable?", "what is the current mana pool?", "is threshold active?", and "what is on top of the stack?". All values are pure functions of the current db; no mutations occur here.

## Interface Contract

### IN

- Datascript db snapshots (injected automatically by re-frame whenever db changes)
- Subscription queries dispatched by Views components via `re-frame.core/subscribe`

### OUT

- Derived data maps and collections consumed by Views components: card lists, boolean flags, mana maps, zone summaries, selection state shapes

## Local Changes

Adding a new derived value (e.g., a new filter over zone contents) is local to this component as long as the raw data already exists in the Datascript schema. Changes become non-local when a new subscription requires a schema addition (owned by Data Foundation) or triggers a new UI panel (owned by Views / UI).
