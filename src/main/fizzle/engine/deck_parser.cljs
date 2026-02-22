(ns fizzle.engine.deck-parser
  "Pure deck text parser. No side effects, no re-frame dependency.
   Parses Moxfield and MTGGoldfish text export formats."
  (:require
    [clojure.string :as str]
    [fizzle.engine.cards :as cards]))


;; Card name registry: lowercase name -> card-id keyword
(def name-lookup
  (into {}
        (map (fn [card]
               [(str/lower-case (:card/name card)) (:card/id card)]))
        cards/all-cards))


;; Card definition lookup: card-id -> card definition
(def ^:private card-by-id
  (into {} (map (juxt :card/id identity) cards/all-cards)))


;; Regex to strip Moxfield set code suffix: (SET) COLLECTOR#
(def ^:private set-code-re #"\s*\([A-Z0-9]+\)\s*\d+\s*$")


;; Regex to match SB: prefix
(def ^:private sb-prefix-re #"(?i)^sb:\s*")


;; Regex to match a card line: QTY CARDNAME
(def ^:private card-line-re #"^\s*(\d+)\s+(.+?)\s*$")


(defn parse-line
  "Parse a single line of deck text.
   Returns:
     {:card/id :keyword :count N} for recognized cards
     {:card/id :keyword :count N :sideboard? true} for SB: prefix lines
     :sideboard for sideboard header lines
     {:unrecognized \"Card Name\"} for unknown cards
     nil for comments, blanks, and non-card lines"
  [line]
  (let [trimmed (str/trim line)]
    (cond
      ;; Blank line
      (str/blank? trimmed)
      nil

      ;; Comment line
      (str/starts-with? trimmed "//")
      nil

      ;; Sideboard header
      (re-matches #"(?i)sideboard:?" trimmed)
      :sideboard

      ;; SB: prefix line (Moxfield alternative)
      (re-find sb-prefix-re trimmed)
      (let [rest-of-line (str/replace trimmed sb-prefix-re "")]
        (when-let [result (parse-line rest-of-line)]
          (when (map? result)
            (if (:unrecognized result)
              result
              (assoc result :sideboard? true)))))

      ;; Card line: QTY CARDNAME
      :else
      (when-let [[_ qty-str card-name] (re-matches card-line-re trimmed)]
        (let [qty (js/parseInt qty-str 10)
              ;; Strip Moxfield set code suffix
              clean-name (str/replace card-name set-code-re "")
              clean-name (str/trim clean-name)
              card-id (get name-lookup (str/lower-case clean-name))]
          (if card-id
            {:card/id card-id :count qty}
            {:unrecognized clean-name}))))))


(defn- has-explicit-sideboard-marker?
  "Pre-scan lines for explicit sideboard indicators (header or SB: prefix).
   Used to decide whether blank lines should act as sideboard separators."
  [lines]
  (some (fn [line]
          (let [t (str/trim line)]
            (or (re-matches #"(?i)sideboard:?" t)
                (re-find sb-prefix-re t))))
        lines))


(defn- has-comment-lines?
  "Pre-scan lines for // comment lines."
  [lines]
  (some #(str/starts-with? (str/trim %) "//") lines))


(defn parse-decklist
  "Parse a full decklist text into a deck map.
   Returns {:ok {:deck/main [...] :deck/side [...]}} on success.
   Returns {:error {:unrecognized [list of card name strings]}} on failure.
   When no explicit sideboard marker or comments are present, a blank line
   after card lines acts as sideboard separator (MTGGoldfish format)."
  [text]
  (if (str/blank? text)
    {:ok {:deck/main [] :deck/side []}}
    (let [lines (str/split-lines text)
          ;; MTGGoldfish uses blank line as separator; only infer this when
          ;; the text has no explicit markers and no comment lines.
          blank-sep? (and (not (has-explicit-sideboard-marker? lines))
                          (not (has-comment-lines? lines)))]
      (loop [remaining lines
             in-sideboard? false
             saw-card? false
             main []
             side []
             unrecognized []]
        (if (empty? remaining)
          ;; Done - check for errors
          (if (seq unrecognized)
            {:error {:unrecognized (vec (distinct unrecognized))}}
            ;; Consolidate duplicates
            (let [consolidate (fn [entries]
                                (->> entries
                                     (group-by :card/id)
                                     (mapv (fn [[id cards]]
                                             {:card/id id
                                              :count (reduce + (map :count cards))}))))]
              {:ok {:deck/main (consolidate main)
                    :deck/side (consolidate side)}}))
          ;; Process next line
          (let [line (first remaining)
                trimmed (str/trim line)]
            (if (str/blank? trimmed)
              ;; Blank line: sideboard separator in MTGGoldfish mode, else skip
              (if (and blank-sep? saw-card? (not in-sideboard?))
                (recur (rest remaining) true saw-card? main side unrecognized)
                (recur (rest remaining) in-sideboard? saw-card? main side unrecognized))
              ;; Non-blank line: parse normally
              (let [parsed (parse-line line)]
                (cond
                  ;; Nil (comment) - skip
                  (nil? parsed)
                  (recur (rest remaining) in-sideboard? saw-card? main side unrecognized)

                  ;; Sideboard marker
                  (= :sideboard parsed)
                  (recur (rest remaining) true saw-card? main side unrecognized)

                  ;; Unrecognized card
                  (:unrecognized parsed)
                  (recur (rest remaining) in-sideboard? true main side
                         (conj unrecognized (:unrecognized parsed)))

                  ;; SB: prefix card - always goes to sideboard
                  (:sideboard? parsed)
                  (recur (rest remaining) in-sideboard? true main
                         (conj side (dissoc parsed :sideboard?)) unrecognized)

                  ;; Normal card - main or side based on position
                  :else
                  (if in-sideboard?
                    (recur (rest remaining) true true main (conj side parsed) unrecognized)
                    (recur (rest remaining) false true (conj main parsed) side unrecognized)))))))))))


;; Type ordering for text regeneration
(def ^:private type-order
  [:land :instant :sorcery :artifact :creature :enchantment])


(def ^:private type-labels
  {:land "Lands"
   :instant "Instants"
   :sorcery "Sorceries"
   :artifact "Artifacts"
   :creature "Creatures"
   :enchantment "Enchantments"})


(defn deck->text
  "Generate formatted deck text from a deck map.
   Groups cards by type with section headers."
  [deck]
  (let [lookup card-by-id
        format-section (fn [entries]
                         (let [;; Look up card defs and attach to entries
                               enriched (map (fn [entry]
                                               (assoc entry :card-def
                                                      (get lookup (:card/id entry))))
                                             entries)
                               ;; Group by primary type
                               by-type (group-by
                                         (fn [e]
                                           (let [types (get-in e [:card-def :card/types])]
                                             (or (first (filter (set types) type-order))
                                                 :other)))
                                         enriched)
                               ;; Build sections in order
                               sections (for [t type-order
                                              :let [cards (get by-type t)]
                                              :when (seq cards)]
                                          (let [total (reduce + (map :count cards))
                                                label (get type-labels t "Other")
                                                card-lines (mapv (fn [e]
                                                                   (str (:count e) " "
                                                                        (get-in e [:card-def :card/name])))
                                                                 cards)]
                                            (str "// " label " (" total ")\n"
                                                 (str/join "\n" card-lines))))
                               ;; Check for "other" type
                               other-cards (get by-type :other)
                               other-section (when (seq other-cards)
                                               (let [total (reduce + (map :count other-cards))
                                                     card-lines (mapv (fn [e]
                                                                        (str (:count e) " "
                                                                             (get-in e [:card-def :card/name])))
                                                                      other-cards)]
                                                 (str "// Other (" total ")\n"
                                                      (str/join "\n" card-lines))))]
                           (str/join "\n//\n" (cond-> (vec sections)
                                                other-section (conj other-section)))))
        main-entries (:deck/main deck)
        side-entries (:deck/side deck)
        main-total (reduce + (map :count main-entries))
        main-text (format-section main-entries)
        header (str "// " (:deck/name deck) " - Main Deck (" main-total ")\n//")]
    (if (seq side-entries)
      (let [side-total (reduce + (map :count side-entries))
            side-lines (mapv (fn [entry]
                               (str (:count entry) " "
                                    (get-in (get lookup (:card/id entry)) [:card/name])))
                             side-entries)]
        (str header "\n" main-text "\n\nSideboard\n"
             "// " side-total " cards\n"
             (str/join "\n" side-lines)))
      (str header "\n" main-text))))
