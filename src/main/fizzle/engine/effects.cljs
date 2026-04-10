(ns fizzle.engine.effects
  "Effect interpreter for Fizzle.

   Dispatches on :effect/type to execute card effects.
   All functions are pure: (db, args) -> db

   Interactive effects (those requiring player selection) return tagged
   values from execute-effect-impl: {:db db :needs-selection effect}.
   Use execute-effect-checked to get the full tagged result, or
   execute-effect for backward-compatible plain db return.

   Domain-specific defmethods are registered in effects/ subdirectory modules.
   This namespace requires them to ensure multimethod dispatch works."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.conditions :as conditions]
    [fizzle.engine.zones :as zones]))


;; === Dynamic Amount Resolution ===


(defmulti resolve-dynamic-impl
  "Resolve a dynamic amount descriptor to an integer.
   Dispatches on :dynamic/type."
  (fn [_db _player-id dynamic _object-id]
    (:dynamic/type dynamic)))


(defmethod resolve-dynamic-impl :count-named-in-zone
  [db _player-id dynamic object-id]
  (let [source-card (q/get-card db object-id)
        card-name (:card/name source-card)
        zone (:dynamic/zone dynamic)
        plus (or (:dynamic/plus dynamic) 0)]
    (+ (q/count-cards-named-in-zone db card-name zone) plus)))


(defmethod resolve-dynamic-impl :chosen-x
  [db _player-id _dynamic object-id]
  (let [obj-eid (q/get-object-eid db object-id)
        chosen-x (when obj-eid
                   (d/q '[:find ?x .
                          :in $ ?obj-eid
                          :where [?e :stack-item/object-ref ?obj-eid]
                          [?e :stack-item/chosen-x ?x]]
                        db obj-eid))]
    (or chosen-x 0)))


(defmethod resolve-dynamic-impl :sacrificed-power
  [db _player-id _dynamic object-id]
  ;; object-id is the source object's UUID.
  ;; Find the stack item for this source (either via object-ref for spells,
  ;; or via stack-item/source for activated abilities) and read sacrifice-info.
  (let [obj-eid (q/get-object-eid db object-id)
        ;; Try spell path: find stack item by object-ref
        sacrifice-info (or (when obj-eid
                             (d/q '[:find ?info .
                                    :in $ ?obj-eid
                                    :where [?e :stack-item/object-ref ?obj-eid]
                                    [?e :stack-item/sacrifice-info ?info]]
                                  db obj-eid))
                           ;; Try ability path: find stack item by source UUID
                           (d/q '[:find ?info .
                                  :in $ ?src
                                  :where [?e :stack-item/source ?src]
                                  [?e :stack-item/sacrifice-info ?info]]
                                db object-id))]
    (or (:power sacrifice-info) 0)))


(defmethod resolve-dynamic-impl :cost-exiled-card-mana-value
  [db _player-id _dynamic object-id]
  ;; Reads the CMC of the card(s) exiled as an exile-library-top cost.
  ;; The CMC is stored on the source object as :object/last-exiled-cmc by pay-cost.
  (if-let [obj-eid (q/get-object-eid db object-id)]
    (or (d/q '[:find ?cmc .
               :in $ ?e
               :where [?e :object/last-exiled-cmc ?cmc]]
             db obj-eid)
        0)
    0))


(defmethod resolve-dynamic-impl :half-life-rounded-up
  [db player-id _dynamic _object-id]
  (let [life (q/get-life-total db player-id)]
    (js/Math.ceil (/ life 2))))


(defn resolve-dynamic-value
  "Resolve a potentially dynamic value to an integer.
   Static integers pass through. Maps with :dynamic/type are computed from game state."
  [db player-id value object-id]
  (cond
    (integer? value) value
    (map? value) (resolve-dynamic-impl db player-id value object-id)
    :else 0))


;; === Effect Execution ===


(defmulti execute-effect-impl
  "Internal effect executor. Dispatches on :effect/type.
   Use execute-effect or execute-effect-checked instead.

   Arguments:
     db - Datascript database value
     player-id - The player who controls this effect
     effect - Map with :effect/type and effect-specific keys
     object-id - The object that is the source of this effect (may be nil)

   Returns one of:
     db - New db with effect applied (non-interactive effects)
     {:db db :needs-selection effect} - Tagged value for interactive effects
       that require player selection before they can execute.

   Unknown effect types return db unchanged (no-op)."
  (fn [_db _player-id effect _object-id] (:effect/type effect)))


(defmethod execute-effect-impl :default
  [db _player-id _effect _object-id]
  ;; Unknown effect types are no-ops
  db)


(defn- normalize-effect-result
  "Normalize execute-effect-impl return value to a tagged map.
   Plain db -> {:db db}, tagged map -> passed through."
  [result]
  (if (and (map? result) (contains? result :db))
    result
    {:db result}))


(defn execute-effect
  "Execute an effect, checking condition first if present.
   Returns plain db (extracts :db from tagged results for backward compat).

   Arguments:
     db - Datascript database value
     player-id - The player who controls this effect
     effect - Map with :effect/type and effect-specific keys
     object-id - (Optional) The object that is the source of this effect.

   Note: :self targets MUST be resolved to object-id by caller before
   calling this function. See events/game.cljs play-land for pattern."
  ([db player-id effect]
   (execute-effect db player-id effect nil))
  ([db player-id effect object-id]
   (if-let [condition (:effect/condition effect)]
     (let [enriched (cond-> condition
                      (:effect/target effect)
                      (assoc :condition/target (:effect/target effect)))]
       (if (conditions/check-condition db player-id enriched)
         (:db (normalize-effect-result (execute-effect-impl db player-id effect object-id)))
         db))
     (:db (normalize-effect-result (execute-effect-impl db player-id effect object-id))))))


(defn execute-effect-checked
  "Execute an effect with full tagged result.
   Returns {:db db'} for non-interactive effects, or
   {:db db :needs-selection effect} for interactive effects.

   When :effect/condition is present and not met, returns {:db db}
   (no :needs-selection, since the effect is skipped).

   Arguments:
     db - Datascript database value
     player-id - The player who controls this effect
     effect - Map with :effect/type and effect-specific keys
     object-id - (Optional) The object that is the source of this effect."
  ([db player-id effect]
   (execute-effect-checked db player-id effect nil))
  ([db player-id effect object-id]
   (if-let [condition (:effect/condition effect)]
     (let [enriched (cond-> condition
                      (:effect/target effect)
                      (assoc :condition/target (:effect/target effect)))]
       (if (conditions/check-condition db player-id enriched)
         (normalize-effect-result (execute-effect-impl db player-id effect object-id))
         {:db db}))
     (normalize-effect-result (execute-effect-impl db player-id effect object-id)))))


(defn reduce-effects
  "Execute effects sequentially, pausing when an interactive effect is encountered.

   Returns:
     {:db db'} - All effects executed successfully (no interactive effects)
     {:db db' :needs-selection effect :remaining-effects [...]} -
       Paused at an interactive effect. :db has all effects before
       the interactive one applied. :remaining-effects are the effects
       after the interactive one that still need execution.

   Arguments:
     db - Datascript database value
     player-id - The player who controls these effects
     effects - Sequence of effect maps
     object-id - (Optional) The object that is the source of these effects."
  ([db player-id effects]
   (reduce-effects db player-id effects nil))
  ([db player-id effects object-id]
   (loop [db db
          [effect & remaining] (seq effects)]
     (if-not effect
       {:db db}
       (let [result (execute-effect-checked db player-id effect object-id)]
         (if (:needs-selection result)
           (assoc result :remaining-effects (vec remaining))
           (recur (:db result) remaining)))))))


(defn counter-target-spell
  "Counter a spell on the stack — move it to the appropriate zone.

   Zone transitions:
   - Copies cease to exist (removed from db)
   - Flashback spells go to exile (MTG rule)
   - All other spells go to graveyard (including permanents)

   Stack-item cleanup is handled automatically by zones/move-to-zone
   and zones/remove-object when the object leaves the :stack zone.

   Returns db. No-op if target is nil, doesn't exist, or not on stack."
  [db target-id]
  (if-not target-id
    db
    (if-let [obj (q/get-object db target-id)]
      (if (not= :stack (:object/zone obj))
        db
        (if (:object/is-copy obj)
          (zones/remove-object db target-id)
          (let [cast-mode (:object/cast-mode obj)
                mode-destination (:mode/on-resolve cast-mode)
                destination (if (= :exile mode-destination) :exile :graveyard)]
            (zones/move-to-zone* db target-id destination))))
      db)))


;; === Domain Module Registration ===
;; Domain-specific defmethods are in effects/ subdirectory modules:
;;   effects/zones.cljs    - mill, draw, exile-self, discard-hand, return-from-graveyard,
;;                           sacrifice, destroy, exile-zone, bounce
;;   effects/life.cljs     - lose-life, gain-life, deal-damage, gain-life-equal-to-cmc
;;   effects/grants.cljs   - grant-flashback, grant-delayed-draw, add-restriction,
;;                           grant-mana-ability, add-counters
;;   effects/stack.cljs    - counter-spell, counter-ability, chain-bounce
;;   effects/selection.cljs - discard, tutor, scry, peek-and-select, peek-and-reorder,
;;                            discard-from-revealed-hand
;;   effects/simple.cljs   - add-mana, peek-random-hand
;;
;; These modules are required by engine/effects_registry.cljs, which must be
;; loaded before any effect execution occurs.
