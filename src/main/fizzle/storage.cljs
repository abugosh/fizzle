(ns fizzle.storage
  (:require
    [cljs.reader :as reader]))


(defn save-presets!
  "Persist presets map to localStorage as EDN."
  [presets]
  (try
    (.setItem js/localStorage "fizzle-presets" (pr-str presets))
    (catch :default _)))


(defn load-presets
  "Load presets map from localStorage. Returns {} on missing or corrupt data."
  []
  (try
    (let [raw (.getItem js/localStorage "fizzle-presets")]
      (if raw
        (reader/read-string raw)
        {}))
    (catch :default _
      {})))


(defn save-last-preset!
  "Persist MRU preset name to localStorage."
  [preset-name]
  (try
    (.setItem js/localStorage "fizzle-last-preset" (pr-str preset-name))
    (catch :default _)))


(defn load-last-preset
  "Load MRU preset name from localStorage. Returns nil if missing."
  []
  (try
    (let [raw (.getItem js/localStorage "fizzle-last-preset")]
      (when raw
        (reader/read-string raw)))
    (catch :default _
      nil)))
