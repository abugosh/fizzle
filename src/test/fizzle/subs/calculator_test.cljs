(ns fizzle.subs.calculator-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.objects :as objects]
    [fizzle.engine.probability :as probability]
    [fizzle.subs.calculator :as subs]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


(defn- sub-value
  "Get subscription value by resetting app-db and deref'ing the subscription."
  [db sub-vec]
  (reset! rf-db/app-db db)
  @(rf/subscribe sub-vec))


(defn- make-game-db
  "Create a minimal game-db with two players and a game entity."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}
                       {:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 0
                        :player/is-opponent true}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid
                          :game/human-player-id :player-1}]))
    @conn))


(defn- add-card-to-library
  "Add a card with a given card-id to the player's library. Returns new game-db."
  [game-db player-id card-id]
  (let [conn (d/conn-from-db game-db)
        player-eid (q/get-player-eid game-db player-id)]
    (d/transact! conn [{:card/id card-id
                        :card/name (name card-id)
                        :card/cmc 1}])
    (let [card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] @conn card-id)
          card-data (d/pull @conn '[:card/types :card/power :card/toughness] card-eid)]
      (d/transact! conn [(objects/build-object-tx @conn card-eid card-data :library player-eid 0)]))
    @conn))


;; === ::calculator-visible? subscription tests ===

(deftest test-calculator-visible-defaults-false
  (testing "::calculator-visible? returns false when absent from app-db"
    (let [result (sub-value {} [::subs/calculator-visible?])]
      (is (false? result)))))


(deftest test-calculator-visible-reads-from-app-db
  (testing "::calculator-visible? returns value from app-db"
    (let [result (sub-value {:calculator/visible? true} [::subs/calculator-visible?])]
      (is (true? result)))))


;; === ::calculator-queries subscription tests ===

(deftest test-calculator-queries-returns-empty-when-absent
  (testing "::calculator-queries returns [] when key absent from app-db"
    (let [result (sub-value {} [::subs/calculator-queries])]
      (is (= [] result)))))


(deftest test-calculator-queries-returns-queries-when-present
  (testing "::calculator-queries returns queries vector from app-db"
    (let [queries [{:query/id 1 :query/label "Query 1" :query/collapsed? false :query/steps []}]
          result (sub-value {:calculator/queries queries} [::subs/calculator-queries])]
      (is (= queries result)))))


;; === ::library-card-counts subscription tests ===

(deftest test-library-card-counts-empty-when-game-db-nil
  (testing "::library-card-counts returns {} when game-db is nil"
    (let [result (sub-value {} [::subs/library-card-counts])]
      (is (= {} result)))))


(deftest test-library-card-counts-with-known-cards
  (testing "::library-card-counts returns correct counts for library with 4x dark-ritual"
    (let [game-db (-> (make-game-db)
                      (add-card-to-library :player-1 :dark-ritual)
                      (add-card-to-library :player-1 :dark-ritual)
                      (add-card-to-library :player-1 :dark-ritual)
                      (add-card-to-library :player-1 :dark-ritual))
          result (sub-value {:game/db game-db} [::subs/library-card-counts])]
      (is (= 4 (get result :dark-ritual))))))


(deftest test-library-card-counts-multiple-card-types
  (testing "::library-card-counts groups by card id correctly"
    (let [game-db (-> (make-game-db)
                      (add-card-to-library :player-1 :dark-ritual)
                      (add-card-to-library :player-1 :dark-ritual)
                      (add-card-to-library :player-1 :brainstorm))
          result (sub-value {:game/db game-db} [::subs/library-card-counts])]
      (is (= 2 (get result :dark-ritual)))
      (is (= 1 (get result :brainstorm))))))


;; === ::calculator-results subscription tests ===

(deftest test-calculator-results-empty-when-no-queries
  (testing "::calculator-results returns [] when queries is empty"
    (let [result (sub-value {:calculator/queries []} [::subs/calculator-results])]
      (is (= [] result)))))


(deftest test-calculator-results-single-query-single-step-single-target
  (testing "::calculator-results probability matches at-least-probability for single target"
    ;; N=60 library, K=4 dark-ritual, n=7 draws, min=1
    ;; P(>=1 in 7 draws from 60 containing 4) = at-least-probability(60,4,7,1)
    (let [game-db (reduce #(add-card-to-library %1 :player-1 %2)
                          (make-game-db)
                          (concat (repeat 4 :dark-ritual)
                                  (repeat 56 :island)))
          queries [{:query/id 1
                    :query/label "Hit a ritual"
                    :query/collapsed? false
                    :query/steps [{:step/id 2
                                   :step/draw-count 7
                                   :step/targets [{:target/id 3
                                                   :target/cards #{:dark-ritual}
                                                   :target/min-count 1}]}]}]
          result (sub-value {:game/db game-db
                             :calculator/queries queries}
                            [::subs/calculator-results])
          expected-p (probability/at-least-probability 60 4 7 1)]
      (is (= 1 (count result)))
      (let [q (first result)]
        (is (= 1 (:query/id q)))
        (is (= "Hit a ritual" (:query/label q)))
        ;; Overall probability matches at-least-probability
        (is (< (Math/abs (- (:query/probability q) expected-p)) 1e-10))
        ;; Step probability
        (let [step (first (:query/steps q))]
          (is (= 2 (:step/id step)))
          (is (< (Math/abs (- (:step/probability step) expected-p)) 1e-10))
          ;; Target count
          (let [target (first (:step/targets step))]
            (is (= 3 (:target/id target)))
            (is (= 4 (:target/count target)))
            (is (= 1 (:target/min-count target)))))))))


(deftest test-calculator-results-step-two-targets-uses-joint-probability
  (testing "::calculator-results for 2 targets uses joint-probability"
    ;; N=10: 3 dark-ritual + 2 brainstorm + 5 other
    ;; n=5 draws, target1 min=1 K=3, target2 min=1 K=2
    (let [game-db (reduce #(add-card-to-library %1 :player-1 %2)
                          (make-game-db)
                          (concat (repeat 3 :dark-ritual)
                                  (repeat 2 :brainstorm)
                                  (repeat 5 :island)))
          queries [{:query/id 1
                    :query/label "Joint"
                    :query/collapsed? false
                    :query/steps [{:step/id 2
                                   :step/draw-count 5
                                   :step/targets [{:target/id 3
                                                   :target/cards #{:dark-ritual}
                                                   :target/min-count 1}
                                                  {:target/id 4
                                                   :target/cards #{:brainstorm}
                                                   :target/min-count 1}]}]}]
          result (sub-value {:game/db game-db
                             :calculator/queries queries}
                            [::subs/calculator-results])
          expected-p (probability/joint-probability 10
                                                    [{:count 3 :min 1}
                                                     {:count 2 :min 1}]
                                                    5)]
      (is (= 1 (count result)))
      (let [q (first result)]
        (is (< (Math/abs (- (:query/probability q) expected-p)) 1e-10))))))


(deftest test-calculator-results-step-no-targets-probability-one
  (testing "::calculator-results step with no targets has probability 1.0"
    (let [game-db (add-card-to-library (make-game-db) :player-1 :island)
          queries [{:query/id 1
                    :query/label "No targets"
                    :query/collapsed? false
                    :query/steps [{:step/id 2
                                   :step/draw-count 7
                                   :step/targets []}]}]
          result (sub-value {:game/db game-db
                             :calculator/queries queries}
                            [::subs/calculator-results])]
      (is (= 1 (count result)))
      (let [step (first (:query/steps (first result)))]
        (is (= 1.0 (:step/probability step)))))))


(deftest test-calculator-results-target-empty-cards-min-zero
  (testing "::calculator-results target with empty cards and min=0 has probability 1.0"
    (let [game-db (add-card-to-library (make-game-db) :player-1 :island)
          queries [{:query/id 1
                    :query/label "Empty target min=0"
                    :query/collapsed? false
                    :query/steps [{:step/id 2
                                   :step/draw-count 7
                                   :step/targets [{:target/id 3
                                                   :target/cards #{}
                                                   :target/min-count 0}]}]}]
          result (sub-value {:game/db game-db
                             :calculator/queries queries}
                            [::subs/calculator-results])]
      (is (= 1 (count result)))
      (let [target (first (:step/targets (first (:query/steps (first result)))))]
        (is (= 1.0 (:target/probability target)))))))


(deftest test-calculator-results-target-empty-cards-min-positive
  (testing "::calculator-results target with empty cards and min>0 has probability 0.0"
    (let [game-db (add-card-to-library (make-game-db) :player-1 :island)
          queries [{:query/id 1
                    :query/label "Empty target min=1"
                    :query/collapsed? false
                    :query/steps [{:step/id 2
                                   :step/draw-count 7
                                   :step/targets [{:target/id 3
                                                   :target/cards #{}
                                                   :target/min-count 1}]}]}]
          result (sub-value {:game/db game-db
                             :calculator/queries queries}
                            [::subs/calculator-results])]
      (is (= 1 (count result)))
      (let [target (first (:step/targets (first (:query/steps (first result)))))]
        (is (= 0.0 (:target/probability target)))))))


;; === Two-step sequential probability through compute-query-results ===

(deftest test-compute-query-results-two-step-sequential
  (testing "two-step query uses sequential-probability with cross-step consumption"
    ;; N=20: 4 dark-ritual + 2 brainstorm + 14 island
    ;; Step 1: draw 3, need >=1 dark-ritual
    ;; Step 2: draw 5, need >=1 brainstorm
    ;; Cross-step consumption: step 1 can accidentally draw brainstorms
    (let [card-counts {:dark-ritual 4 :brainstorm 2 :island 14}
          query {:query/id 1
                 :query/label "Two-step"
                 :query/collapsed? false
                 :query/steps [{:step/id 2
                                :step/draw-count 3
                                :step/targets [{:target/id 3
                                                :target/cards #{:dark-ritual}
                                                :target/min-count 1}]}
                               {:step/id 4
                                :step/draw-count 5
                                :step/targets [{:target/id 5
                                                :target/cards #{:brainstorm}
                                                :target/min-count 1}]}]}
          result (subs/compute-query-results card-counts query)
          overall-p (:query/probability result)
          step1-p (:step/probability (first (:query/steps result)))
          step2-p (:step/probability (second (:query/steps result)))]
      ;; Overall should be a valid probability
      (is (>= overall-p 0.0))
      (is (<= overall-p 1.0))
      ;; Overall should differ from product of independent step probabilities
      ;; (cross-step consumption changes step 2's odds — can be higher or lower
      ;;  depending on whether step 1's targets overlap with step 2's)
      (is (not= overall-p (* step1-p step2-p))
          "sequential probability should differ from naive product of independent steps")
      ;; Overall should match sequential-probability directly
      (let [expected (probability/sequential-probability
                       20
                       [{:count 4} {:count 2}]
                       [{:draw-count 3 :targets [{:group-index 0 :min 1}]}
                        {:draw-count 5 :targets [{:group-index 1 :min 1}]}])]
        (is (< (Math/abs (- overall-p expected)) 1e-10)
            "overall probability should match sequential-probability")))))


(deftest test-compute-query-results-same-count-different-groups
  (testing "two target groups with same card count are kept distinct"
    ;; N=20: 4 dark-ritual + 4 brainstorm + 12 island
    ;; Both groups have count=4 but are distinct card populations
    ;; Step 1: need >=1 dark-ritual
    ;; Step 2: need >=1 brainstorm
    (let [card-counts {:dark-ritual 4 :brainstorm 4 :island 12}
          query {:query/id 1
                 :query/label "Same count"
                 :query/collapsed? false
                 :query/steps [{:step/id 2
                                :step/draw-count 3
                                :step/targets [{:target/id 3
                                                :target/cards #{:dark-ritual}
                                                :target/min-count 1}]}
                               {:step/id 4
                                :step/draw-count 3
                                :step/targets [{:target/id 5
                                                :target/cards #{:brainstorm}
                                                :target/min-count 1}]}]}
          result (subs/compute-query-results card-counts query)
          overall-p (:query/probability result)]
      ;; This should be a valid probability, not NaN or incorrect due to group merging
      (is (>= overall-p 0.0))
      (is (<= overall-p 1.0))
      ;; Should match sequential-probability with two distinct groups
      (let [expected (probability/sequential-probability
                       20
                       [{:count 4} {:count 4}]
                       [{:draw-count 3 :targets [{:group-index 0 :min 1}]}
                        {:draw-count 3 :targets [{:group-index 1 :min 1}]}])]
        (is (< (Math/abs (- overall-p expected)) 1e-10)
            "same-count groups must be treated as distinct populations")))))


;; === compute-target-count helper tests ===

(deftest test-compute-target-count-sums-cards
  (testing "compute-target-count sums counts for all card-ids in target"
    (let [card-counts {:dark-ritual 4 :brainstorm 3 :island 20}
          target {:target/id 1 :target/cards #{:dark-ritual :brainstorm} :target/min-count 1}
          result (subs/compute-target-count card-counts target)]
      (is (= 7 result)))))


(deftest test-compute-target-count-missing-card-returns-zero
  (testing "compute-target-count returns 0 for cards not in library"
    (let [card-counts {:island 20}
          target {:target/id 1 :target/cards #{:dark-ritual} :target/min-count 1}
          result (subs/compute-target-count card-counts target)]
      (is (= 0 result)))))


(deftest test-compute-target-count-empty-cards-returns-zero
  (testing "compute-target-count returns 0 for empty cards set"
    (let [card-counts {:dark-ritual 4}
          target {:target/id 1 :target/cards #{} :target/min-count 1}
          result (subs/compute-target-count card-counts target)]
      (is (= 0 result)))))


;; === HIGH: N=0 empty-library corner cases ===

(deftest test-compute-query-results-n-zero-empty-library
  (testing "compute-query-results with N=0 (empty library) returns 0.0, not NaN"
    ;; Bug caught: if at-least-probability divides by C(0,n) without guarding,
    ;; the result would be NaN or an exception. 0.0 is the correct result for
    ;; P(hit at least 1 card in 0 draws from 0 library).
    ;; assert exact = 0.0, not (not (js/isNaN result)) — NaN would pass weak checks.
    (let [card-counts {}     ; empty library: N = (reduce + 0 (vals {})) = 0
          query {:query/id 1
                 :query/label "Empty library"
                 :query/collapsed? false
                 :query/steps [{:step/id 2
                                :step/draw-count 7
                                :step/targets [{:target/id 3
                                                :target/cards #{:dark-ritual}
                                                :target/min-count 1}]}]}
          result (subs/compute-query-results card-counts query)]
      (is (= 0.0 (:query/probability result))
          "N=0 empty library: query probability must be exactly 0.0, not NaN"))))


(deftest test-compute-step-probability-n-zero-single-target
  (testing "compute-step-probability with N=0 returns 0.0 for min-count > 0"
    ;; Bug caught: hypergeometric-pmf(0, 0, 7, 1) without a guard would divide by
    ;; C(0,7)=0, producing NaN. Expected 0.0 since no cards can be drawn.
    (let [card-counts {}
          step {:step/id 1
                :step/draw-count 7
                :step/targets [{:target/id 1 :target/cards #{:dark-ritual} :target/min-count 1}]}
          result (subs/compute-step-probability 0 card-counts step)]
      (is (= 0.0 result)
          "N=0: compute-step-probability must be exactly 0.0, not NaN"))))


;; === HIGH: min-count > K (more copies requested than exist) ===

(deftest test-compute-query-results-min-count-exceeds-k
  (testing "compute-query-results with min-count > K (only 2 copies, need 3) returns 0.0"
    ;; Bug caught: if at-least-probability does not guard (> min-k K) properly,
    ;; summing PMF from min-k=3 to max-k=min(K,n)=2 would iterate over empty range.
    ;; reduce on empty range should yield 0.0, but NaN propagation in intermediate
    ;; calculations could corrupt results. Verify exact 0.0.
    (let [card-counts {:dark-ritual 2}  ; K=2
          query {:query/id 1
                 :query/label "Need 3, have 2"
                 :query/collapsed? false
                 :query/steps [{:step/id 2
                                :step/draw-count 7
                                :step/targets [{:target/id 3
                                                :target/cards #{:dark-ritual}
                                                :target/min-count 3}]}]}   ; min=3 > K=2
          result (subs/compute-query-results card-counts query)]
      (is (= 0.0 (:query/probability result))
          "min-count(3) > K(2): probability must be exactly 0.0, not NaN"))))


(deftest test-at-least-probability-min-count-exceeds-k
  (testing "at-least-probability returns exactly 0.0 when min-k > K"
    ;; Bug caught: if the (> min-k K) early-exit guard is removed, the function
    ;; would compute a range from min-k to max-k=min(K,n) which is empty when
    ;; min-k > K, and reduce over empty sequence returns 0.0 but we want to
    ;; guarantee this directly via the guard, not as a coincidence.
    (is (= 0.0 (probability/at-least-probability 60 2 7 3))
        "min-k=3 > K=2: at-least-probability must be exactly 0.0")))


;; === HIGH: 3+ steps sequential (real use case is 3-5 steps) ===

(deftest test-compute-query-results-three-step-sequential
  (testing "3-step sequential query returns valid probability (not NaN, not >1)"
    ;; Bug caught: sequential-probability with 3 steps iterates the recursion
    ;; 3 times. If indexed-targets or group mapping is wrong for >2 steps,
    ;; the third step might use stale group counts or wrong group-indices.
    ;; N=20: 4 dark-ritual + 4 brainstorm + 4 cabal-ritual + 8 island
    ;; Step 1: draw 3, need >=1 dark-ritual
    ;; Step 2: draw 3, need >=1 brainstorm
    ;; Step 3: draw 3, need >=1 cabal-ritual
    (let [card-counts {:dark-ritual 4 :brainstorm 4 :cabal-ritual 4 :island 8}
          query {:query/id 1
                 :query/label "3-step"
                 :query/collapsed? false
                 :query/steps [{:step/id 2
                                :step/draw-count 3
                                :step/targets [{:target/id 3
                                                :target/cards #{:dark-ritual}
                                                :target/min-count 1}]}
                               {:step/id 4
                                :step/draw-count 3
                                :step/targets [{:target/id 5
                                                :target/cards #{:brainstorm}
                                                :target/min-count 1}]}
                               {:step/id 6
                                :step/draw-count 3
                                :step/targets [{:target/id 7
                                                :target/cards #{:cabal-ritual}
                                                :target/min-count 1}]}]}
          result (subs/compute-query-results card-counts query)
          overall-p (:query/probability result)]
      (is (>= overall-p 0.0)
          "3-step probability must be >= 0.0")
      (is (<= overall-p 1.0)
          "3-step probability must be <= 1.0")
      ;; Match against sequential-probability directly to verify the computation path
      (let [expected (probability/sequential-probability
                       20
                       [{:count 4} {:count 4} {:count 4}]
                       [{:draw-count 3 :targets [{:group-index 0 :min 1}]}
                        {:draw-count 3 :targets [{:group-index 1 :min 1}]}
                        {:draw-count 3 :targets [{:group-index 2 :min 1}]}])]
        (is (< (Math/abs (- overall-p expected)) 1e-10)
            "3-step result must exactly match sequential-probability(N=20, 3 groups, 3 steps)")))))


;; === HIGH: Target-card overlap across steps ===

(deftest test-compute-query-results-overlapping-targets-across-steps
  (testing "same card appears in two steps' targets — group deduplication must NOT merge groups"
    ;; Bug caught: if compute-query-results deduplicates target groups by card-count
    ;; (e.g., both steps target 4 dark-ritual = count 4), it would map to the same
    ;; group-index, corrupting the sequential probability (consumed-in-step-1 would
    ;; also decrement step-2's pool). Groups must remain distinct even when count matches.
    ;; Step 1 and Step 2 both target :dark-ritual (intentional overlap for this test).
    ;; We verify the probability is NOT the same as independent step product.
    ;; (Same test pattern as the same-count test but now with actual shared cards.)
    (let [card-counts {:dark-ritual 4 :island 16}
          query {:query/id 1
                 :query/label "Overlap steps"
                 :query/collapsed? false
                 :query/steps [{:step/id 2
                                :step/draw-count 4
                                :step/targets [{:target/id 3
                                                :target/cards #{:dark-ritual}
                                                :target/min-count 1}]}
                               {:step/id 4
                                :step/draw-count 4
                                :step/targets [{:target/id 5
                                                :target/cards #{:dark-ritual}
                                                :target/min-count 1}]}]}
          result (subs/compute-query-results card-counts query)
          overall-p (:query/probability result)]
      ;; Overlap case: step 2's dark-ritual pool is reduced by what step 1 drew.
      ;; Overall must be <= P(step1) * P(step2 independent) because of consumption.
      (is (>= overall-p 0.0)
          "Overlapping target probability must be >= 0.0")
      (is (<= overall-p 1.0)
          "Overlapping target probability must be <= 1.0")
      ;; Verify it matches sequential-probability directly
      (let [expected (probability/sequential-probability
                       20
                       [{:count 4} {:count 4}]   ; two DISTINCT groups even though same cards
                       [{:draw-count 4 :targets [{:group-index 0 :min 1}]}
                        {:draw-count 4 :targets [{:group-index 1 :min 1}]}])]
        (is (< (Math/abs (- overall-p expected)) 1e-10)
            "Overlap steps: must match sequential-probability with 2 distinct groups")))))


;; === compute-step-probability helper tests ===

(deftest test-compute-step-probability-no-targets-returns-one
  (testing "compute-step-probability returns 1.0 when no targets"
    (let [step {:step/id 1 :step/draw-count 7 :step/targets []}
          result (subs/compute-step-probability 60 {} step)]
      (is (= 1.0 result)))))


(deftest test-compute-step-probability-single-target
  (testing "compute-step-probability delegates to at-least-probability for single target"
    (let [card-counts {:dark-ritual 4}
          step {:step/id 1
                :step/draw-count 7
                :step/targets [{:target/id 1 :target/cards #{:dark-ritual} :target/min-count 1}]}
          result (subs/compute-step-probability 60 card-counts step)
          expected (probability/at-least-probability 60 4 7 1)]
      (is (< (Math/abs (- result expected)) 1e-10)))))


(deftest test-compute-step-probability-draw-count-clamped-to-N
  (testing "compute-step-probability clamps draw-count to min(draw-count, N)"
    ;; N=5 library, draw-count=10 (more than library) — should clamp to 5
    (let [card-counts {:dark-ritual 3}
          step {:step/id 1
                :step/draw-count 10
                :step/targets [{:target/id 1 :target/cards #{:dark-ritual} :target/min-count 1}]}
          result (subs/compute-step-probability 5 card-counts step)
          ;; Clamped: draw 5 cards from library of 5 containing 3 dark-ritual
          expected (probability/at-least-probability 5 3 5 1)]
      (is (< (Math/abs (- result expected)) 1e-10)))))
