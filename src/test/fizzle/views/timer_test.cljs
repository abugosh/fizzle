(ns fizzle.views.timer-test
  (:require
    [cljs.test :refer-macros [deftest is]]
    [fizzle.views.timer :as timer]))


(deftest format-elapsed-boundary-values
  (is (= "0:00" (timer/format-elapsed 0)))
  (is (= "0:00" (timer/format-elapsed 999)))
  (is (= "0:01" (timer/format-elapsed 1000)))
  (is (= "0:59" (timer/format-elapsed 59000)))
  (is (= "1:00" (timer/format-elapsed 60000)))
  (is (= "59:59" (timer/format-elapsed 3599000)))
  (is (= "1:00:00" (timer/format-elapsed 3600000)))
  (is (= "1:01:01" (timer/format-elapsed 3661000)))
  (is (= "24:00:00" (timer/format-elapsed 86400000)))
  (is (= "0:00" (timer/format-elapsed nil)))
  (is (= "0:00" (timer/format-elapsed -1000))))
