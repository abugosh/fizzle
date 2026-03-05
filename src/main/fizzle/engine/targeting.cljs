(ns fizzle.engine.targeting
  "Targeting system for spell and ability targets.

   Provides:
   - get-targeting-requirements: Extract targeting from cards/abilities
   - find-valid-targets: Find objects/players matching criteria
   - has-valid-targets?: Check if any valid target exists
   - target-still-legal?: Check if chosen target is still valid
   - all-targets-legal?: Check all targets on resolution"
  (:require
    [fizzle.db.queries :as q]))


(defn get-targeting-requirements
  "Extract targeting requirements from card or ability.
   Returns vector of target requirement maps, or empty vector if no targeting.

   Checks :card/targeting for cards and :ability/targeting for abilities."
  [card-or-ability]
  (or (:card/targeting card-or-ability)
      (:ability/targeting card-or-ability)
      []))


(defn find-valid-targets
  "Find all objects/players that are valid targets for a requirement.
   Returns vector of valid target IDs (object UUIDs or player keywords).

   For ability targeting:
   - Finds activated and triggered abilities on the stack (excludes mana abilities)"
  [db player-id target-requirement]
  (let [target-type (:target/type target-requirement)]
    (cond
      ;; Player targeting
      (= :player target-type)
      (let [options (set (:target/options target-requirement))
            opponent-id (q/get-opponent-id db player-id)]
        (cond
          ;; If :any-player or both :self and :opponent, return both
          (or (contains? options :any-player)
              (and (contains? options :self) (contains? options :opponent)))
          [player-id opponent-id]

          ;; Only self
          (contains? options :self)
          [player-id]

          ;; Only opponent
          (contains? options :opponent)
          [opponent-id]

          :else []))

      ;; Object targeting
      (= :object target-type)
      (let [zone (:target/zone target-requirement)
            controller-filter (:target/controller target-requirement)
            criteria (:target/criteria target-requirement)
            ;; Get objects in the target zone
            objects-in-zone (case controller-filter
                              :self (q/get-objects-in-zone db player-id zone)
                              :opponent (let [opponent-id (q/get-opponent-id db player-id)]
                                          (q/get-objects-in-zone db opponent-id zone))
                              :any (concat (q/get-objects-in-zone db player-id zone)
                                           (q/get-objects-in-zone db (q/get-opponent-id db player-id) zone))
                              (q/get-objects-in-zone db player-id zone))]
        (->> objects-in-zone
             (filter #(q/matches-criteria? % criteria))
             ;; Shroud: objects with shroud cannot be targeted
             (remove (fn [obj]
                       (let [card-keywords (set (or (:card/keywords (:object/card obj)) #{}))]
                         (contains? card-keywords :shroud))))
             (mapv :object/id)))

      ;; Ability targeting
      (= :ability target-type)
      (let [all-stack-items (q/get-all-stack-items db)]
        (->> all-stack-items
             ;; Include activated and triggered abilities, exclude mana abilities
             (filter (fn [item]
                       (let [item-type (:stack-item/type item)
                             ability-type (:stack-item/ability-type item)]
                         (and (or (= item-type :activated-ability)
                                  (= item-type :triggered-ability))
                              ;; Exclude mana abilities
                              (not= ability-type :mana)))))
             ;; Use stack-item entity ID as the target (db/id)
             (mapv :db/id)))

      :else [])))


(defn- mode-has-valid-targets?
  "Check if a single mode has valid targets for ALL its required targeting."
  [db player-id mode]
  (let [requirements (or (:mode/targeting mode) [])]
    (every? (fn [req]
              (if (:target/required req)
                (seq (find-valid-targets db player-id req))
                true))
            requirements)))


(defn has-valid-targets?
  "Check if at least one valid target exists for EACH required target.
   Returns true only if ALL required targets have valid options.

   For modal cards (:card/modes), returns true if ANY mode has valid targets."
  [db player-id card-or-ability]
  (if-let [modes (:card/modes card-or-ability)]
    ;; Modal card: castable if any mode has valid targets
    (boolean (some #(mode-has-valid-targets? db player-id %) modes))
    ;; Non-modal: check standard targeting
    (let [requirements (get-targeting-requirements card-or-ability)]
      (if (empty? requirements)
        true  ; No targeting required
        (every? (fn [req]
                  (if (:target/required req)
                    (seq (find-valid-targets db player-id req))
                    true))
                requirements)))))


(defn target-still-legal?
  "Check if a chosen target is still legal (zone, attributes match).
   Used on resolution to check if target moved/changed.

   For player targets, always returns true (players don't move zones).
   For object targets, checks zone and criteria still match.
   For ability targets, checks ability still exists on the stack.
   Returns false if target doesn't exist."
  [db target-id target-requirement]
  (let [target-type (:target/type target-requirement)]
    (cond
      ;; Player targets are always legal
      (= :player target-type)
      true

      ;; Object targets - check zone, criteria, and shroud
      (= :object target-type)
      (if-let [obj (q/get-object db target-id)]
        (let [required-zone (:target/zone target-requirement)
              current-zone (:object/zone obj)
              criteria (:target/criteria target-requirement)
              card-keywords (set (or (:card/keywords (:object/card obj)) #{}))]
          (and (= current-zone required-zone)
               (q/matches-criteria? obj criteria)
               (not (contains? card-keywords :shroud))))
        ;; Object not found - target is illegal
        false)

      ;; Ability targets - check ability still exists on stack
      (= :ability target-type)
      ;; target-id is a Datascript entity ID from the stack-item
      ;; Check if a stack item with this entity ID still exists
      (boolean (some #(= (:db/id %) target-id) (q/get-all-stack-items db)))

      :else false)))


(defn all-targets-legal?
  "Check if ALL chosen targets are still legal on resolution.

   Arguments:
     db           - Datascript database value
     targets      - Map of target-ref keyword to target-id (from :stack-item/targets)
     requirements - Vector of targeting requirement maps (from get-targeting-requirements)

   Returns true if:
   - No targets stored (non-targeting spell/ability)
   - All stored targets are still legal per their requirements"
  [db targets requirements]
  (if (or (nil? targets) (empty? targets))
    true
    (every? (fn [req]
              (let [target-ref (:target/id req)
                    target-id (get targets target-ref)]
                (if target-id
                  (target-still-legal? db target-id req)
                  (not (:target/required req)))))
            requirements)))
