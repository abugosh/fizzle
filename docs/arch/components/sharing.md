# Snapshot Sharing

## Responsibility

State serialization and URL sharing subsystem. Extracts game state from Datascript into a compact binary representation, encodes it for URL transport, and restores snapshots into fresh Datascript databases. Uses a stable card index (integer mappings) for compact card references. Orthogonal to the core game loop — sharing reads game state but does not modify game logic.

## Interface Contract

### IN

- Datascript db (full game state to extract from)
- URL hash string (encoded snapshot to decode and restore)
- Save/load commands dispatched by Views / UI

### OUT

- URL-safe encoded snapshot string (for sharing)
- Restored Datascript db (reconstructed from decoded snapshot)
- Dispatches `::restore-from-snapshot` to Game Events for state restoration

## Dependencies

- **Data Foundation:** db/schema (for Datascript reconstruction), db/storage (for localStorage persistence)
- **Engine:** engine/cards (card lookup for card index), engine/trigger-db (trigger registration on restore), engine/turn-based (turn state reconstruction)

## Local Changes

Changing the encoding format, adding new state to snapshots, or modifying the card index are local to this component. Changes become non-local when the game state schema changes (new entity types to serialize) or when engine initialization requirements change (new state to reconstruct on restore).
