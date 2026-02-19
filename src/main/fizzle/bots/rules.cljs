(ns fizzle.bots.rules
  "Bot condition evaluator engine.

   Pure functions for evaluating conditions against game state.
   No re-frame dependency — operates on raw Datascript db.

   Conditions are EDN maps with a :check keyword that dispatches
   to the appropriate evaluator. Context is {:db game-db :player-id player-id}.

   Player references (:self/:opponent) are resolved via context."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.mana :as mana]))


(defn- resolve-player
  "Resolve :self/:opponent to actual player-id using context."
  [player-ref context]
  (case player-ref
    :self (:player-id context)
    :opponent (queries/get-other-player-id (:db context) (:player-id context))
    player-ref))


(defmulti evaluate-condition
  "Evaluate a single condition against game state.
   Dispatches on (:check condition).
   Returns true/false."
  (fn [condition _context] (:check condition)))


(defmethod evaluate-condition :zone-contains
  [condition context]
  (let [db (:db context)
        player-id (resolve-player (:player condition) context)
        zone (:zone condition)
        card-id (:card-id condition)
        objects (queries/get-objects-in-zone db player-id zone)]
    (boolean (some #(= card-id (get-in % [:object/card :card/id])) objects))))


(defmethod evaluate-condition :has-mana-for
  [condition context]
  (let [db (:db context)
        player-id (:player-id context)
        card-id (:card-id condition)
        card (d/q '[:find (pull ?e [:card/mana-cost]) .
                    :in $ ?cid
                    :where [?e :card/id ?cid]]
                  db card-id)
        cost (:card/mana-cost card)]
    (if cost
      (mana/can-pay? db player-id cost)
      true)))


(defmethod evaluate-condition :stack-empty
  [_condition context]
  (boolean (queries/stack-empty? (:db context))))


(defmethod evaluate-condition :has-untapped-source
  [condition context]
  (let [db (:db context)
        player-id (:player-id context)
        color (:color condition)
        battlefield (queries/get-objects-in-zone db player-id :battlefield)]
    (boolean
      (some (fn [obj]
              (and (not (:object/tapped obj))
                   (some (fn [ability]
                           (and (= :mana (:ability/type ability))
                                (get (:ability/produces ability) color)))
                         (get-in obj [:object/card :card/abilities]))))
            battlefield))))


(defmethod evaluate-condition :zone-count
  [condition context]
  (let [db (:db context)
        player-id (resolve-player (:player condition) context)
        zone (:zone condition)
        objects (queries/get-objects-in-zone db player-id zone)
        cnt (count (or objects []))]
    (cond
      (:gte condition) (>= cnt (:gte condition))
      (:lte condition) (<= cnt (:lte condition))
      (:eq condition) (= cnt (:eq condition))
      :else true)))


(defmethod evaluate-condition :stack-has
  [condition context]
  (let [db (:db context)
        owner-id (resolve-player (:owner condition) context)
        owner-eid (queries/get-player-eid db owner-id)
        target-type (:type condition)
        stack-items (queries/get-all-stack-items db)]
    (boolean
      (some (fn [item]
              (and (= target-type (:stack-item/type item))
                   (= owner-eid (:stack-item/controller item))))
            stack-items))))


(defmethod evaluate-condition :default
  [condition _context]
  (throw (ex-info (str "Unknown condition check: " (:check condition))
                  {:condition condition})))


(defn evaluate-conditions
  "Evaluate all conditions (implicit AND). Returns true if all pass.
   Returns true for empty conditions list."
  [conditions context]
  (every? #(evaluate-condition % context) conditions))


(defn resolve-action
  "Resolve symbolic references in an action template to concrete game objects.
   :card-id keyword → object-id (UUID) of matching card in player's hand.
   :target :opponent → opponent's player-id.
   :target :self → player's own player-id.
   Returns :pass if card not found in hand or target cannot be resolved."
  [action-template context]
  (let [db (:db context)
        player-id (:player-id context)
        card-id (:card-id action-template)
        hand (queries/get-objects-in-zone db player-id :hand)
        obj (when card-id
              (first (filter #(= card-id (get-in % [:object/card :card/id])) hand)))]
    (if (and card-id (nil? obj))
      :pass
      (let [target-ref (:target action-template)
            resolved-target (case target-ref
                              :opponent (queries/get-other-player-id db player-id)
                              :self player-id
                              target-ref)]
        (if (and (= :opponent target-ref) (nil? resolved-target))
          :pass
          (cond-> {:action (:action action-template)}
            obj (assoc :object-id (:object/id obj))
            resolved-target (assoc :target resolved-target)))))))


(defn match-priority-rule
  "Find the first matching priority rule and return its resolved action.
   Skips :interactive rules. Returns :pass if no rule matches or rules is empty."
  [rules context]
  (if (seq rules)
    (or (some (fn [rule]
                (when (and (not= :interactive (:rule/mode rule))
                           (evaluate-conditions (:rule/conditions rule) context))
                  (resolve-action (:rule/action rule) context)))
              rules)
        :pass)
    :pass))


(defn get-phase-action
  "Look up phase action from a bot spec.
   Returns {:action <value>} for listed phases, {:action :pass} otherwise."
  [spec phase]
  (if-let [action (get-in spec [:bot/phase-actions phase])]
    {:action action}
    {:action :pass}))
