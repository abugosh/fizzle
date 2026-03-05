# Card Pool

## Responsibility

Pure EDN data definitions for every card in the system. Each card is a Clojure map describing its name, mana cost, type, zone, effects, costs, conditions, and keyword mechanics. Files are organized by color identity (black, blue, white, red, green, multicolor, lands, artifacts). Cycle files (basic lands, pain lands, fetch lands) export a `cards` vector; individual card files export a `card` def. The registry (`registry.cljs`) requires all card namespaces and exports a single aggregated card collection. The engine imports only from the registry — never from individual card files.

## Interface Contract

### IN

- No runtime inputs. Card data is static at compile time.
- New card additions: a new `.cljs` file in the appropriate color subdirectory, plus a require in `registry.cljs`.

### OUT

- A map of card-name keyword → card definition map, consumed by the Engine via `engine/cards.cljs`
- Effect data structures interpreted by the Engine's effect multimethod dispatch

## Local Changes

Adding a new card, updating a card's effect data, or adding a new color-identity subdirectory are fully local to this component. Changes become non-local when a new card introduces an effect type not yet handled by the Engine, or a new cost type not yet handled by the cost-validation system.
