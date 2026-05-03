---
name: Cleanup-Selection-Source Silent No-Op Bug
description: ETB triggers infinite loop caused by silent failure in cleanup-selection-source when spell object not on :stack
type: project
---

**Bug**: `cleanup-selection-source` (events/selection/core.cljs:259-281) silently returns unchanged db when spell object NOT on `:stack` zone (line 281). This masked ETB trigger infinite loops.

**Why**: ETB stack-items created by trigger-dispatch have no `:stack-item/object-ref` but carry `:stack-item/source` = creature object-id. When creature enters battlefield and ETB trigger selection completes, cleanup looks up creature (finds it on :battlefield, not :stack) and silently succeeds, leaving stack-item orphaned forever.

**Three-part failure**:
1. Routing: `build-selection-from-result` routes ETB to wrong cleanup path OR uses wrong object-id
2. Silent failure: cleanup-selection-source never validates spell zone assumption
3. Infinite loop: orphaned ETB stack-item resolves again next priority cycle

**Key code**:
- `cleanup-selection-source`: events/selection/core.cljs:259-281, silent no-op at line 281
- `build-selection-from-result`: events/game.cljs:314-339, routing decision at 333-338
- `dispatch-event`: engine/trigger_dispatch.cljs:130-140, creates ETB stack-items WITHOUT `:stack-item/object-ref`
- ETB trigger registration: engine/resolution.cljs:135-151, after creature enters battlefield

**Stack-item types**: :spell (has :stack-item/object-ref, spell cleanup path), :etb/:activated-ability/:permanent-tapped (no :stack-item/object-ref, trigger cleanup path via :stack-item-eid)

**Fix**: Add validation in cleanup-selection-source to detect/reject spell-zone mismatch; verify routing in build-selection-from-result; add tests for all cleanup paths.

**Tests missing**: No existing tests for cleanup-selection-source (core_test.cljs only covers validation and auto-confirm). Need tests for both cleanup paths and ETB trigger scenario.
