(ns fizzle.events.resolution
  (:require
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.queries :as queries]
    [fizzle.engine.resolution :as engine-resolution]
    [fizzle.engine.stack :as stack]
    [fizzle.events.cleanup :as cleanup]
    [fizzle.events.selection.combat :as sel-combat]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.storm :as sel-storm]
    [re-frame.core :as rf]))


(defn- get-source-id
  "Get the source object-id for a stack-item (for selection building)."
  [game-db stack-item]
  (or (when-let [obj-ref-raw (:stack-item/object-ref stack-item)]
        (let [obj-ref (if (map? obj-ref-raw) (:db/id obj-ref-raw) obj-ref-raw)
              obj (queries/pull-safe game-db [:object/id] obj-ref)]
          (:object/id obj)))
      (:stack-item/source stack-item)))


(defn- build-selection-from-result
  "Build pending-selection from a multimethod result that has :needs-selection.
   Handles stored-player for targeted abilities (Cephalid Coliseum) and
   adjusts selection fields for abilities vs spells."
  [game-db player-id stack-item result]
  (let [stack-item-eid (:db/id stack-item)
        source-id (get-source-id game-db stack-item)
        stored-player (:stored-player result)
        sel-effect (:needs-selection result)
        use-stored-player (and stored-player
                               (= :player (:effect/selection sel-effect)))
        sel-player-id (if use-stored-player stored-player player-id)
        sel (sel-core/build-selection-for-effect
              (:db result) sel-player-id source-id
              sel-effect
              (vec (:remaining-effects result)))
        ;; Non-spell stack items (activated abilities, triggered abilities)
        ;; don't have an object-ref — use :stack-item source type so
        ;; cleanup-selection-source removes the stack-item directly.
        sel (if (not (:stack-item/object-ref stack-item))
              (-> sel
                  (dissoc :selection/spell-id)
                  (assoc :selection/stack-item-eid stack-item-eid
                         :selection/source-type :stack-item))
              sel)]
    {:db (:db result) :pending-selection sel}))


(defn- clear-peek-result
  "Clear any stale :game/peek-result from a previous resolution."
  [db]
  (let [game-state (queries/get-game-state db)]
    (if-let [old-val (:game/peek-result game-state)]
      (let [game-eid (queries/q-safe '[:find ?g . :where [?g :game/id _]] db)]
        (d/db-with db [[:db/retract game-eid :game/peek-result old-val]]))
      db)))


(defn resolve-one-item
  "Resolve the topmost stack-item. Returns {:db} or {:db :pending-selection}.
   Controller identity comes from the stack-item itself — no player-id parameter."
  [game-db]
  (let [game-db (clear-peek-result game-db)
        top (stack/get-top-stack-item game-db)]
    (if-not top
      {:db game-db}
      (let [controller (:stack-item/controller top)
            result (engine-resolution/resolve-stack-item game-db top)]
        (cond
          (:needs-storm-split result)
          (if-let [sel (sel-storm/build-storm-split-selection game-db controller top)]
            {:db game-db :pending-selection sel}
            {:db (stack/remove-stack-item game-db (:db/id top))})

          (:needs-attackers result)
          (let [eligible (:eligible-attackers result)
                archetype (bot-protocol/get-bot-archetype game-db controller)]
            (if archetype
              ;; Bot chooses attackers via configurable rules
              (let [chosen (bot-protocol/bot-choose-attackers archetype eligible)
                    sel (sel-combat/build-attacker-selection
                          eligible controller (:db/id top))
                    sel (assoc sel :selection/selected (set chosen))
                    app-db {:game/db game-db :game/pending-selection sel}
                    result-db (sel-core/confirm-selection-impl app-db)]
                {:db (:game/db result-db)})
              ;; Human gets attacker selection UI
              {:db game-db
               :pending-selection (sel-combat/build-attacker-selection
                                    eligible controller (:db/id top))}))

          (:needs-blockers result)
          (let [attackers (:attackers result)
                defender-id (:defender-id result)
                sel (sel-combat/build-blocker-selection
                      game-db attackers defender-id (:db/id top))]
            {:db game-db
             :pending-selection sel})

          (:needs-selection result)
          (build-selection-from-result game-db controller top result)

          :else
          {:db (stack/remove-stack-item (:db result) (:db/id top))})))))


(rf/reg-event-db
  ::resolve-top
  (fn [db _]
    (let [result (resolve-one-item (:game/db db))]
      (if (:pending-selection result)
        (-> db
            (assoc :game/db (:db result))
            (assoc :game/pending-selection (:pending-selection result)))
        (cleanup/maybe-continue-cleanup
          (assoc db :game/db (:db result)))))))


(rf/reg-event-fx
  ::resolve-all
  (fn [{:keys [db]} [_ initial-ids]]
    (let [game-db (:game/db db)
          initial-ids (or initial-ids
                          (set (map :db/id (queries/get-all-stack-items game-db))))]
      (if (empty? initial-ids)
        {:db db}
        (let [top (stack/get-top-stack-item game-db)]
          (if (and top (contains? initial-ids (:db/id top)))
            (let [result (resolve-one-item game-db)]
              (if (:pending-selection result)
                {:db (-> db
                         (assoc :game/db (:db result))
                         (assoc :game/pending-selection (:pending-selection result)))}
                {:db (assoc db :game/db (:db result))
                 :fx [[:dispatch [::resolve-all initial-ids]]]}))
            ;; Stack empty or new item on top — done, check cleanup
            {:db (cleanup/maybe-continue-cleanup (assoc db :game/db game-db))}))))))
