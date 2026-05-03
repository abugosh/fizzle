# Ponder Agent Memory — Fizzle Project

## Project: Fizzle

**System name:** fizzle
**Arch directory:** /Users/abugosh/g/fizzle/docs/arch/
**Source root:** /Users/abugosh/g/fizzle/src/main/fizzle/ (NOT src/fizzle/)
**likec4 binary:** /usr/local/bin/likec4
**Validate command:** `likec4 validate docs/arch/` (run from /Users/abugosh/g/fizzle/)

## Model Status (2026-03-04)
10 components modeled, landscape + 6 internal views created.
Migrated from arch/ (flat single-file) to docs/arch/ (per-component files) on 2026-03-04.

Component list:
- views_ui (presentation) — src/main/fizzle/views/
- subs (presentation) — src/main/fizzle/subs/
- events_game (orchestration) — src/main/fizzle/events/game/
- events_selection (orchestration) — src/main/fizzle/events/selection/
- events_abilities (orchestration) — src/main/fizzle/events/abilities.cljs
- engine (domain) — src/main/fizzle/engine/
- cards (data) — src/main/fizzle/cards/
- db (data) — src/main/fizzle/db/
- bots (orchestration) — src/main/fizzle/bots/
- history (infrastructure) — src/main/fizzle/history/

## LikeC4 File Structure for This Repo
- docs/arch/spec.c4 — specification (element types, relationship types, colors)
- docs/arch/model.c4 — system shell + extend fizzle { top-level relationships }
- docs/arch/components/<name>.c4 — one per component, using `model { extend fizzle { ... } }`
- docs/arch/views/landscape.c4 — landscape view (include * -> *)
- docs/arch/views/<name>-internals.c4 — one per component with sub-components
- docs/arch/components/<name>.md — markdown docs for each component

## LikeC4 Per-Component File Pattern
Each component file uses this structure:
```
// Part of fizzle

model {
  extend fizzle {
    <name> = component 'Display Name' {
      description '...'
      link ./<name>.md 'Documentation'
      link ../../../src/main/fizzle/<path>/ 'Source'
      metadata {
        layer '<layer>'
        stability_state 'exploring'
      }
      // sub-components and internal relationships here
    }
  }
}
```

## LikeC4 Patterns Learned
- likec4 validate exits 0 on success, outputs "workspace: found N source files" (no "Invalid" lines)
- `extend` MUST be inside a `model {}` block — bare `extend` at top level is invalid
- Top-level inter-component relationships go in model.c4 inside `extend fizzle { }` (not inside the system block)
- Relationships inside the system block can only reference locally-defined (non-extended) elements
- The spec.c4 color block uses hex colors (exploring #90EE90, stabilizing #FFD700, stable #4169E1)
- Layer values used: presentation, orchestration, domain, data, infrastructure
- All elements bootstrapped at stability_state 'exploring'
- likec4 1.49.0 in use
