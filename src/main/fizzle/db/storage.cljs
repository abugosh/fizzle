(ns fizzle.db.storage
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


(defn save-imported-decks!
  "Persist imported decks map to localStorage as EDN."
  [decks]
  (try
    (.setItem js/localStorage "fizzle-imported-decks" (pr-str decks))
    (catch :default _)))


(defn load-imported-decks
  "Load imported decks map from localStorage. Returns {} on missing or corrupt data."
  []
  (try
    (let [raw (.getItem js/localStorage "fizzle-imported-decks")]
      (if raw
        (reader/read-string raw)
        {}))
    (catch :default _
      {})))


;; === Calculator Queries ===

(defn save-calculator-queries!
  "Persist calculator queries vector to localStorage as EDN."
  [queries]
  (try
    (.setItem js/localStorage "fizzle-calculator-queries" (pr-str queries))
    (catch :default _)))


(defn load-calculator-queries
  "Load calculator queries from localStorage. Returns [] on missing or corrupt data."
  []
  (try
    (let [raw (.getItem js/localStorage "fizzle-calculator-queries")]
      (if raw
        (let [data (reader/read-string raw)]
          (if (vector? data) data []))
        []))
    (catch :default _
      [])))


;; === Phase Stops ===

(defn default-stops
  "Return default phase stops.
   Player stops at main phases; opponent-stops is empty (human's opponent-turn preferences)."
  []
  {:player #{:main1 :main2}
   :opponent-stops #{}})


(defn toggle-stop
  "Add or remove a phase from a role's stop set.
   role is :player or :opponent. phase is a phase keyword.
   :opponent role updates the :opponent-stops key."
  [stops role phase]
  (let [key (if (= role :opponent) :opponent-stops :player)]
    (update stops key (fn [s]
                        (if (contains? s phase)
                          (disj s phase)
                          (conj s phase))))))


(defn save-stops!
  "Persist stops map to localStorage as EDN."
  [stops]
  (try
    (.setItem js/localStorage "fizzle-stops" (pr-str stops))
    (catch :default _)))


(defn- migrate-stops
  "Migrate old localStorage format {:player #{} :opponent #{}} to new format
   {:player #{} :opponent-stops #{}}.  If :opponent-stops already present, no-op."
  [stops]
  (if (and (contains? stops :opponent) (not (contains? stops :opponent-stops)))
    (-> stops
        (assoc :opponent-stops (:opponent stops))
        (dissoc :opponent))
    stops))


(defn load-stops
  "Load stops map from localStorage. Returns default-stops if missing or corrupt.
   Migrates old :opponent key to :opponent-stops on first load."
  []
  (try
    (let [raw (.getItem js/localStorage "fizzle-stops")]
      (if raw
        (migrate-stops (reader/read-string raw))
        (default-stops)))
    (catch :default _
      (default-stops))))
