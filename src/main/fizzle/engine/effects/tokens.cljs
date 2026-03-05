(ns fizzle.engine.effects.tokens
  "Token creation effect handler.

   :create-token creates a token creature on the battlefield.
   Tokens have a synthetic card entity for type/subtype/color queries,
   and are marked with :object/is-token true."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]))


(defmethod effects/execute-effect-impl :create-token
  [db player-id effect _object-id]
  (let [token-def (:effect/token effect)
        player-eid (q/get-player-eid db player-id)
        obj-id (random-uuid)
        ;; Create a synthetic card entity for the token so that
        ;; creature?, has-keyword?, and card-based queries work.
        card-tempid -1
        card-tx (cond-> {:db/id card-tempid
                         :card/types (:token/types token-def)
                         :card/name (:token/name token-def)
                         :card/power (:token/power token-def)
                         :card/toughness (:token/toughness token-def)}
                  (:token/subtypes token-def)
                  (assoc :card/subtypes (:token/subtypes token-def))
                  (:token/colors token-def)
                  (assoc :card/colors (:token/colors token-def))
                  (:token/keywords token-def)
                  (assoc :card/keywords (:token/keywords token-def)))
        ;; Create the token object on the battlefield with creature fields
        obj-tx {:object/id obj-id
                :object/card card-tempid
                :object/zone :battlefield
                :object/owner player-eid
                :object/controller player-eid
                :object/tapped false
                :object/is-token true
                :object/power (:token/power token-def)
                :object/toughness (:token/toughness token-def)
                :object/summoning-sick true
                :object/damage-marked 0}]
    (d/db-with db [card-tx obj-tx])))
