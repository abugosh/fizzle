(ns fizzle.engine.effects-registry
  "Loads all effect domain modules to register their defmethods.
   Require this namespace once before any effect execution occurs.
   Typically required by the application entry point or event layer."
  (:require
    [fizzle.engine.effects.grants]
    [fizzle.engine.effects.life]
    [fizzle.engine.effects.pt-modifier]
    [fizzle.engine.effects.selection]
    [fizzle.engine.effects.simple]
    [fizzle.engine.effects.stack]
    [fizzle.engine.effects.tokens]
    [fizzle.engine.effects.zones]))
