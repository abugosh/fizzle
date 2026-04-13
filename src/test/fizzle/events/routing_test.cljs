(ns fizzle.events.routing-test
  "Tests for screen routing - set-active-screen event.

   NOTE: The previous test (set-active-screen-preserves-game-state) was a
   tautology — it only verified the handler does not mutate game state, not
   that routing itself works. Real routing coverage should verify navigation
   side effects and screen transitions via production event dispatch.
   Pending replacement in fizzle-ds6g.")


;; Placeholder: real routing coverage is tracked in fizzle-ds6g.
;; Tests here should exercise routing via re-frame dispatch, not handler internals.
