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


(defn- matches-criteria?
  "Check if an object's card matches the targeting criteria.
   For :card/types, uses OR logic: object matches if it has ANY of the specified types.
   E.g., {:card/types #{:artifact :enchantment}} matches artifacts OR enchantments."
  [obj criteria]
  (let [card (:object/card obj)
        card-types (set (or (:card/types card) #{}))
        required-types (get criteria :card/types #{})]
    ;; OR logic: object matches if any required type is in card's types
    ;; This matches MTG: 'target artifact or enchantment' means either type
    (if (empty? required-types)
      true  ; No type restriction
      (some card-types required-types))))


(defn find-valid-targets
  "Find all objects/players that are valid targets for a requirement.
   Returns vector of valid target IDs (object UUIDs or player keywords)."
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
             (filter #(matches-criteria? % criteria))
             (mapv :object/id)))

      :else [])))


(defn has-valid-targets?
  "Check if at least one valid target exists for EACH required target.
   Returns true only if ALL required targets have valid options."
  [db player-id card-or-ability]
  (let [requirements (get-targeting-requirements card-or-ability)]
    (if (empty? requirements)
      true  ; No targeting required
      (every? (fn [req]
                (if (:target/required req)
                  (seq (find-valid-targets db player-id req))
                  true))
              requirements))))


(defn target-still-legal?
  "Check if a chosen target is still legal (zone, attributes match).
   Used on resolution to check if target moved/changed.

   For player targets, always returns true (players don't move zones).
   For object targets, checks zone and criteria still match.
   Returns false if target doesn't exist."
  [db target-id target-requirement]
  (let [target-type (:target/type target-requirement)]
    (cond
      ;; Player targets are always legal
      (= :player target-type)
      true

      ;; Object targets - check zone and criteria
      (= :object target-type)
      (if-let [obj (q/get-object db target-id)]
        (let [required-zone (:target/zone target-requirement)
              current-zone (:object/zone obj)
              criteria (:target/criteria target-requirement)]
          (and (= current-zone required-zone)
               (matches-criteria? obj criteria)))
        ;; Object not found - target is illegal
        false)

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
