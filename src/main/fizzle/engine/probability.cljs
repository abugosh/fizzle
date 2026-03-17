(ns fizzle.engine.probability
  "Pure math functions for hypergeometric probability calculations.

   All functions are pure: (numbers) -> number
   Zero dependencies on re-frame, Datascript, or any framework.

   ## Preconditions
   - Groups within a step MUST be disjoint (a card belongs to exactly one group).
     If groups overlap, results will be mathematically wrong but won't crash.
   - N is the total population (library size), K is the number of target cards,
     n is the number of draws, k is the number of successes.

   ## Edge cases handled
   - K > N, n > N, min-k > K, negative values: all return 0.0 (impossible draws)
   - n = 0: P(k=0) = 1.0 always (drawing nothing succeeds at k=0)
   - K = 0: P(k=0) = 1.0, P(k>0) = 0.0 (no targets → never draw one)")


(defn binomial-coefficient
  "Compute C(n, k) iteratively, avoiding overflow.

   Uses the identity C(n,k) = C(n, n-k) to minimize multiplications.
   Returns an integer for all valid inputs within JavaScript safe integer range.

   Edge cases:
   - C(0,0) = 1
   - C(n,0) = 1 for any n
   - C(0,k) = 0 for k > 0
   - C(n,k) = 0 for k > n or k < 0"
  [n k]
  (cond
    (neg? k) 0
    (neg? n) 0
    (> k n)  0
    (zero? k) 1
    (= k n)  1
    :else
    ;; Use symmetry: C(n,k) = C(n, n-k) — pick the smaller k to minimize iterations
    (let [k' (min k (- n k))]
      ;; Iterative: C(n,k) = n*(n-1)*...*(n-k+1) / k!
      ;; Compute by interleaving multiply and divide to keep values small
      (loop [result 1
             i      0]
        (if (= i k')
          result
          (recur (-> result
                     (* (- n i))
                     (/ (+ i 1)))
                 (+ i 1)))))))


(defn hypergeometric-pmf
  "Compute P(X = k): probability of drawing exactly k successes in n draws
   from a population of N containing K target cards.

   P(X=k) = C(K,k) * C(N-K, n-k) / C(N,n)

   Parameters:
   - N: population size (library size)
   - K: number of target cards in population
   - n: number of cards drawn
   - k: number of target cards we want (exact)

   Returns 0.0 for impossible configurations (K > N, n > N, k > min(K,n), etc.)."
  [N K n k]
  (cond
    (or (neg? N) (neg? K) (neg? n) (neg? k)) 0.0
    (> K N)          0.0
    (> n N)          0.0
    (> k K)          0.0
    (> k n)          0.0
    (< (- n k) 0)    0.0
    (> (- n k) (- N K)) 0.0
    :else
    (let [denom (binomial-coefficient N n)]
      (if (zero? denom)
        (if (zero? k) 1.0 0.0)
        (/ (* (binomial-coefficient K k)
              (binomial-coefficient (- N K) (- n k)))
           denom)))))


(defn at-least-probability
  "Compute P(X >= min-k): probability of drawing at least min-k successes
   in n draws from a population of N containing K target cards.

   Computed by summing PMF from min-k to min(K, n).

   Parameters:
   - N: population size
   - K: number of target cards
   - n: number of draws
   - min-k: minimum number of successes required

   Returns:
   - 1.0 if min-k <= 0
   - 0.0 if min-k > K or n = 0 (when min-k > 0)"
  [N K n min-k]
  (cond
    (neg? min-k) 1.0
    (zero? min-k) 1.0
    (> min-k K)  0.0
    (zero? n)    0.0
    :else
    (let [max-k (min K n)]
      (if (> min-k max-k)
        0.0
        (reduce + (map #(hypergeometric-pmf N K n %) (range min-k (inc max-k))))))))


(defn- enumerate-combinations
  "Enumerate all valid combinations (k1, k2, ..., kM) for M groups where each
   ki satisfies: min-ki <= ki <= Ki and sum(ki) <= n and (n - sum(ki)) <= other-count.

   groups: [{:count K :min min-k} ...]
   n: total draws
   other-count: N - sum(all Ki), the count of non-target cards

   Returns a lazy sequence of [k1 k2 ...] vectors."
  [groups n other-count]
  (if (empty? groups)
    [[]]
    (let [{:keys [count min]} (first groups)
          K     (or count 0)
          min-k (or min 0)
          rest-groups (rest groups)]
      (for [k      (range min-k (inc K))
            rest-ks (enumerate-combinations rest-groups (- n k) other-count)
            :let   [total-k (reduce + k rest-ks)]
            :when  (and (<= total-k n)
                        (<= (- n total-k) other-count))]
        (into [k] rest-ks)))))


(defn joint-probability
  "Compute P(X1 >= min1 AND X2 >= min2 AND ...) for disjoint groups in one draw step.

   Uses multivariate hypergeometric distribution via enumeration.

   Parameters:
   - N: total population size
   - groups: [{:count K :min min-k} ...] — each group specifies count in population and min hits
   - n: number of draws

   Groups MUST be disjoint (no card belongs to two groups).
   'Other' count = N - sum(group counts).

   Formula: sum over valid (k1,...,kM) of C(K1,k1)*...*C(KM,kM)*C(other, n-sum(ki)) / C(N,n)"
  [N groups n]
  (let [total-K    (reduce + (map :count groups))
        other-count (- N total-K)
        denom      (binomial-coefficient N n)]
    (cond
      (> total-K N) 0.0
      (> n N)       0.0
      (zero? denom) 1.0
      :else
      (let [combos (enumerate-combinations groups n other-count)]
        (reduce
          (fn [acc ks]
            (let [sum-k   (reduce + ks)
                  others  (- n sum-k)
                  numer   (* (reduce * (map (fn [{:keys [count]} k]
                                              (binomial-coefficient count k))
                                            groups ks))
                             (binomial-coefficient other-count others))]
              (+ acc (/ numer denom))))
          0.0
          combos)))))


(defn sequential-probability
  "Compute P(all steps succeed) for a sequential draw sequence.

   CRITICAL: Each step's draw enumerates ALL target groups (across all steps), not
   just the current step's targets. This correctly models cross-step consumption
   (step 1 can accidentally draw step 2's targets, reducing their availability).

   Parameters:
   - N: initial population size
   - all-groups: [{:count K} ...] — all unique target groups across all steps
   - steps: [{:draw-count n :targets [{:group-index i :min min-k} ...]} ...]
             :targets specifies which groups must hit and how many

   Returns the joint probability that all steps achieve their target minimums.

   Algorithm:
   For each step, enumerate all possible (k1, k2, ..., kM) draw outcomes for ALL groups.
   For outcomes satisfying current step's min-counts, multiply combo probability by
   recursive probability of remaining steps with updated group counts."
  [N all-groups steps]
  (if (empty? steps)
    ;; Base case: no more steps, all previous steps succeeded
    1.0
    (let [step          (first steps)
          rest-steps    (rest steps)
          draw-count    (:draw-count step)
          targets       (:targets step)
          ;; Build min requirements for this step (groups not targeted have min=0)
          step-mins     (into {} (map (fn [{:keys [group-index min]}]
                                        [group-index min])
                                      targets))
          ;; Build groups with min requirements for this step's enumeration
          groups-with-mins (map-indexed (fn [i g]
                                          (assoc g :min (get step-mins i 0)))
                                        all-groups)
          total-K       (reduce + (map :count all-groups))
          other-count   (- N total-K)
          denom         (binomial-coefficient N draw-count)]
      (cond
        ;; Can't draw more than exist
        (> draw-count N) 0.0
        ;; Zero draws: check if all step mins can be satisfied
        (zero? draw-count)
        (if (every? #(<= (:min %) 0)
                    (map (fn [i] {:min (get step-mins i 0)}) (range (count all-groups))))
          ;; All targets have min=0, trivially succeed; recurse with unchanged state
          (sequential-probability N all-groups rest-steps)
          ;; Some target requires at least 1 draw but we draw 0
          0.0)
        :else
        (let [combos (enumerate-combinations groups-with-mins draw-count other-count)]
          (reduce
            (fn [acc ks]
              (let [sum-k    (reduce + ks)
                    others   (- draw-count sum-k)
                    ;; Probability of this specific combo in this step's draw
                    combo-p  (/ (* (reduce * (map (fn [{:keys [count]} k]
                                                    (binomial-coefficient count k))
                                                  all-groups ks))
                                   (binomial-coefficient other-count others))
                                denom)
                    ;; Update group counts for next step
                    updated-groups (map-indexed
                                     (fn [i g]
                                       (update g :count - (nth ks i)))
                                     all-groups)
                    ;; Updated other count after this step's draws
                    updated-N     (- N draw-count)
                    ;; Recurse on remaining steps with updated state
                    rest-p  (sequential-probability updated-N updated-groups rest-steps)]
                (+ acc (* combo-p rest-p))))
            0.0
            combos))))))
