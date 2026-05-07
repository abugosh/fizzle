(ns fizzle.views.timer)


(defn format-elapsed
  [elapsed-ms]
  (let [total-s (max 0 (quot (or elapsed-ms 0) 1000))
        h (quot total-s 3600)
        m (quot (mod total-s 3600) 60)
        s (mod total-s 60)]
    (if (pos? h)
      (str h ":" (when (< m 10) "0") m ":" (when (< s 10) "0") s)
      (str m ":" (when (< s 10) "0") s))))
