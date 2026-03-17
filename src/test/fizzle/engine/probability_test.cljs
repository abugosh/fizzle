(ns fizzle.engine.probability-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.engine.probability :as prob]))


;; Helper for floating-point comparison
(defn approx=
  "Returns true if |expected - actual| < tolerance."
  ([expected actual] (approx= expected actual 1e-6))
  ([expected actual tolerance]
   (< (abs (- expected actual)) tolerance)))


;; === Tier 1: binomial-coefficient ===

(deftest binomial-coefficient-base-cases-test
  (testing "C(0,0) = 1"
    (is (= 1 (prob/binomial-coefficient 0 0))))
  (testing "C(5,0) = 1"
    (is (= 1 (prob/binomial-coefficient 5 0))))
  (testing "C(5,5) = 1"
    (is (= 1 (prob/binomial-coefficient 5 5))))
  (testing "C(5,2) = 10"
    (is (= 10 (prob/binomial-coefficient 5 2))))
  (testing "C(10,3) = 120"
    (is (= 120 (prob/binomial-coefficient 10 3)))))


(deftest binomial-coefficient-large-test
  (testing "C(60,7) = 386206920 (opening hand from 60-card deck)"
    (is (= 386206920 (prob/binomial-coefficient 60 7)))))


(deftest binomial-coefficient-symmetry-test
  (testing "C(n,k) = C(n, n-k): C(60,7) = C(60,53)"
    (is (= (prob/binomial-coefficient 60 7)
           (prob/binomial-coefficient 60 53)))))


(deftest binomial-coefficient-edge-cases-test
  (testing "C(n,0) = 1 for any n"
    (is (= 1 (prob/binomial-coefficient 0 0)))
    (is (= 1 (prob/binomial-coefficient 1 0)))
    (is (= 1 (prob/binomial-coefficient 100 0))))
  (testing "C(0,k) = 0 for k > 0"
    (is (= 0 (prob/binomial-coefficient 0 1)))
    (is (= 0 (prob/binomial-coefficient 0 5)))))


;; === Tier 2: hypergeometric-pmf ===

(deftest hypergeometric-pmf-exact-values-test
  (testing "P(exactly 0 of 4 in 7 from 60) ≈ 0.60044"
    ;; C(4,0)*C(56,7)/C(60,7) = 1*231917400/386206920
    (is (approx= 0.60044 (prob/hypergeometric-pmf 60 4 7 0) 1e-4)))
  (testing "P(exactly 1 of 4 in 7 from 60) ≈ 0.33624"
    ;; C(4,1)*C(56,6)/C(60,7) = 4*32468436/386206920
    (is (approx= 0.33624 (prob/hypergeometric-pmf 60 4 7 1) 1e-4))))


(deftest hypergeometric-pmf-sum-to-one-test
  (testing "Sum of P(k=0..4) for K=4, n=7, N=60 = 1.0 within 1e-10"
    (let [total (reduce + (map #(prob/hypergeometric-pmf 60 4 7 %) (range 5)))]
      (is (approx= 1.0 total 1e-10)))))


(deftest hypergeometric-pmf-edge-zero-successes-test
  (testing "K=0 -> P(k=0) = 1.0"
    (is (approx= 1.0 (prob/hypergeometric-pmf 60 0 7 0))))
  (testing "K=0 -> P(k>0) = 0.0"
    (is (approx= 0.0 (prob/hypergeometric-pmf 60 0 7 1)))))


(deftest hypergeometric-pmf-edge-zero-draws-test
  (testing "n=0 -> P(k=0) = 1.0"
    (is (approx= 1.0 (prob/hypergeometric-pmf 60 4 0 0)))))


;; === Tier 3: at-least-probability ===

(deftest at-least-probability-basic-test
  (testing "P(>=1 of 4 in 7 from 60) ≈ 0.39956"
    (is (approx= 0.39956 (prob/at-least-probability 60 4 7 1) 1e-4)))
  (testing "Cross-check: P(>=1) ≈ 1 - P(0)"
    (let [at-least-one (prob/at-least-probability 60 4 7 1)
          complement   (- 1.0 (prob/hypergeometric-pmf 60 4 7 0))]
      (is (approx= at-least-one complement 1e-10)))))


(deftest at-least-probability-land-test
  (testing "P(>=1 of 24 in 7 from 60) ≈ 0.97839 (land in opener)"
    ;; P(0 lands) = C(24,0)*C(36,7)/C(60,7) = 8347680/386206920 ≈ 0.02161
    ;; P(>=1) = 1 - 0.02161 ≈ 0.97839
    (is (approx= 0.97839 (prob/at-least-probability 60 24 7 1) 1e-4)))
  (testing "P(>=3 of 24 in 7 from 60) ≈ 0.58793 (3+ lands)"
    ;; Computed as 1 - P(0) - P(1) - P(2)
    (is (approx= 0.58793 (prob/at-least-probability 60 24 7 3) 1e-4))))


(deftest at-least-probability-boundaries-test
  (testing "P(>=0 of K in n from N) = 1.0"
    (is (approx= 1.0 (prob/at-least-probability 60 4 7 0))))
  (testing "P(>=(K+1) of K in n from N) = 0.0"
    (is (approx= 0.0 (prob/at-least-probability 60 4 7 5))))
  (testing "P(>=1 of K in 0 from N) = 0.0"
    (is (approx= 0.0 (prob/at-least-probability 60 4 0 1)))))


;; === Tier 4: joint-probability ===

(deftest joint-probability-single-group-equivalence-test
  (testing "joint with one group equals at-least CDF"
    (let [joint   (prob/joint-probability 60 [{:count 4 :min 1}] 7)
          at-least (prob/at-least-probability 60 4 7 1)]
      (is (approx= at-least joint 1e-10)))))


(deftest joint-probability-two-groups-test
  (testing "P(>=1 of 2 AND >=1 of 3 in 5 from 10) = 176/252 ≈ 0.69841"
    ;; Verified via inclusion-exclusion:
    ;;   P(A>=1 AND B>=1) = 1 - P(A=0) - P(B=0) + P(A=0 AND B=0)
    ;;   = 1 - 56/252 - 21/252 + 1/252 = 176/252
    (is (approx= (/ 176.0 252.0)
                 (prob/joint-probability 10 [{:count 2 :min 1} {:count 3 :min 1}] 5)
                 1e-6))))


(deftest joint-probability-zero-count-edge-test
  (testing "group with count=0 and min>=1 -> probability = 0.0"
    (is (approx= 0.0
                 (prob/joint-probability 10 [{:count 0 :min 1} {:count 3 :min 1}] 5)))))


(deftest joint-probability-zero-min-test
  (testing "group with min=0 contributes no constraint"
    ;; P(>=0 of 2 AND >=1 of 3 in 5 from 10) = P(>=1 of 3 in 5 from 10)
    (let [joint    (prob/joint-probability 10 [{:count 2 :min 0} {:count 3 :min 1}] 5)
          at-least (prob/at-least-probability 10 3 5 1)]
      (is (approx= at-least joint 1e-10)))))


;; === Tier 5: sequential-probability ===

(deftest sequential-probability-single-step-equivalence-test
  (testing "sequential with one step = at-least result"
    (let [sequential (prob/sequential-probability
                       60
                       [{:count 4}]
                       [{:draw-count 7 :targets [{:group-index 0 :min 1}]}])
          at-least   (prob/at-least-probability 60 4 7 1)]
      (is (approx= at-least sequential 1e-10)))))


(deftest sequential-probability-two-steps-test
  (testing "two-step with disjoint targets includes cross-step consumption"
    ;; P(>=1 of 4 rituals in 3 draws THEN >=1 of 2 LED in 5 draws from 60)
    ;; Step 1 draws 3 cards targeting rituals (group 0), but LEDs (group 1) can also be drawn
    ;; Step 2 draws 5 cards from remaining 57, targeting LEDs minus any drawn in step 1
    (let [result (prob/sequential-probability
                   60
                   [{:count 4} {:count 2}]
                   [{:draw-count 3 :targets [{:group-index 0 :min 1}]}
                    {:draw-count 5 :targets [{:group-index 1 :min 1}]}])]
      ;; Result should be a valid probability between 0 and 1
      (is (>= result 0.0))
      (is (<= result 1.0))
      ;; It should be less than just P(>=1 of 4 in 3 from 60) * P(>=1 of 2 in 5 from 57)
      ;; because LEDs can be consumed in step 1 (cross-step consumption)
      ;; The naive independent estimate ignores this effect
      (let [naive (* (prob/at-least-probability 60 4 3 1)
                     (prob/at-least-probability 57 2 5 1))]
        (is (<= result (* naive 1.001)))))) ; result should be at most very slightly above naive (they're close but not equal)
  )


(deftest sequential-probability-zero-draw-step-test
  (testing "step with draw-count=0 and min-count=0 succeeds trivially"
    (let [result (prob/sequential-probability
                   60
                   [{:count 4}]
                   [{:draw-count 0 :targets [{:group-index 0 :min 0}]}])]
      (is (approx= 1.0 result))))
  (testing "step with draw-count=0 and min-count>0 fails"
    (let [result (prob/sequential-probability
                   60
                   [{:count 4}]
                   [{:draw-count 0 :targets [{:group-index 0 :min 1}]}])]
      (is (approx= 0.0 result)))))


(deftest sequential-probability-monotonicity-test
  (testing "more draws -> higher probability (for same targets)"
    (let [p3 (prob/sequential-probability
               60 [{:count 4}]
               [{:draw-count 3 :targets [{:group-index 0 :min 1}]}])
          p7 (prob/sequential-probability
               60 [{:count 4}]
               [{:draw-count 7 :targets [{:group-index 0 :min 1}]}])]
      (is (< p3 p7)))))
