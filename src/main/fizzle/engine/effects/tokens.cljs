(ns fizzle.engine.effects.tokens
  "Token creation effect handler.

   :create-token creates a token creature on the battlefield.
   Tokens have a synthetic card entity for type/subtype/color queries,
   and are marked with :object/is-token true."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.objects :as objects]))


(defmethod effects/execute-effect-impl :create-token
  [db player-id effect _object-id]
  (let [token-def  (:effect/token effect)
        player-eid (q/get-player-eid db player-id)
        ;; Create a synthetic card entity for the token so that
        ;; creature?, has-keyword?, and card-based queries work.
        card-tempid -1
        card-tx    (cond-> {:db/id          card-tempid
                            :card/types     (:token/types token-def)
                            :card/name      (:token/name token-def)
                            :card/power     (:token/power token-def)
                            :card/toughness (:token/toughness token-def)}
                     (:token/subtypes token-def)
                     (assoc :card/subtypes (:token/subtypes token-def))
                     (:token/colors token-def)
                     (assoc :card/colors (:token/colors token-def))
                     (:token/keywords token-def)
                     (assoc :card/keywords (:token/keywords token-def)))
        ;; Adapt token-def to card-data shape for build-object-tx
        card-data  {:card/types     (:token/types token-def)
                    :card/power     (:token/power token-def)
                    :card/toughness (:token/toughness token-def)}
        ;; Build base object via shared chokepoint, then add token-specific fields.
        ;; Tokens are placed directly on battlefield (no zone transition),
        ;; so caller sets summoning-sick and damage-marked here.
        obj-tx     (-> (objects/build-object-tx card-tempid card-data :battlefield player-eid 0)
                       (assoc :object/is-token      true
                              :object/summoning-sick true
                              :object/damage-marked  0))]
    (d/db-with db [card-tx obj-tx])))
