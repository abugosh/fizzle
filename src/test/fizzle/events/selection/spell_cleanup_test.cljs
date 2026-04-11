(ns fizzle.events.selection.spell-cleanup-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection.spell-cleanup :as spell-cleanup]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; cleanup-selection-source tests
;; =====================================================

(deftest test-cleanup-spell-not-on-stack-returns-db-unchanged
  (testing "spell in exile returns game-db unchanged (exile-self already cleaned up)"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :exile :player-1)
          selection {:selection/spell-id obj-id}
          result (spell-cleanup/cleanup-selection-source db selection)]
      (is (= db result)
          "Should return db unchanged when spell already in exile"))))


(deftest test-cleanup-spell-on-battlefield-warns-and-returns-db
  (testing "spell on battlefield (routing bug scenario) returns db unchanged and warns"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :battlefield :player-1)
          warnings (atom [])
          original-warn js/console.warn]
      ;; Capture console.warn calls
      (set! js/console.warn (fn [& args] (swap! warnings conj (apply str args))))
      (try
        (let [selection {:selection/spell-id obj-id}
              result (spell-cleanup/cleanup-selection-source db selection)]
          (is (= db result)
              "Should return db unchanged")
          (is (= 1 (count @warnings))
              "Should have produced exactly one warning")
          (is (re-find #"not :stack" (first @warnings))
              "Warning should mention spell not being on stack"))
        (finally
          (set! js/console.warn original-warn))))))


(deftest test-cleanup-nil-spell-id-returns-db-unchanged
  (testing "nil spell-id returns game-db unchanged (no spell context)"
    (let [db (th/create-test-db)
          selection {:selection/spell-id nil}
          result (spell-cleanup/cleanup-selection-source db selection)]
      (is (= db result)
          "Should return db unchanged when no spell-id"))))
