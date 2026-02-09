(ns fizzle.engine.deck-parser-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.string :as str]
    [fizzle.engine.deck-parser :as parser]))


;; === Name Registry Tests ===

(deftest name-lookup-contains-all-cards-test
  (testing "name-lookup has an entry for every card in all-cards"
    (is (pos? (count parser/name-lookup)))
    (is (contains? parser/name-lookup "dark ritual"))
    (is (contains? parser/name-lookup "brain freeze"))
    (is (contains? parser/name-lookup "lion's eye diamond"))
    (is (contains? parser/name-lookup "lotus petal"))
    (is (contains? parser/name-lookup "city of brass"))))


(deftest name-lookup-maps-to-card-ids-test
  (testing "name-lookup values are card-id keywords"
    (is (= :dark-ritual (get parser/name-lookup "dark ritual")))
    (is (= :brain-freeze (get parser/name-lookup "brain freeze")))
    (is (= :lotus-petal (get parser/name-lookup "lotus petal")))))


(deftest name-lookup-is-lowercase-test
  (testing "all keys in name-lookup are lowercase"
    (doseq [k (keys parser/name-lookup)]
      (is (= k (str/lower-case k))))))


;; === Line Parser Tests ===

(deftest parse-line-basic-card-test
  (testing "parses standard 'QTY Name' format"
    (is (= {:card/id :dark-ritual :count 4}
           (parser/parse-line "4 Dark Ritual")))))


(deftest parse-line-single-copy-test
  (testing "parses single copy"
    (is (= {:card/id :lotus-petal :count 1}
           (parser/parse-line "1 Lotus Petal")))))


(deftest parse-line-strips-set-code-test
  (testing "strips Moxfield (SET) COLLECTOR# suffix"
    (is (= {:card/id :dark-ritual :count 4}
           (parser/parse-line "4 Dark Ritual (VIS) 72")))))


(deftest parse-line-strips-set-code-complex-test
  (testing "strips set code with multiple digits"
    (is (= {:card/id :polluted-delta :count 3}
           (parser/parse-line "3 Polluted Delta (ONS) 321")))))


(deftest parse-line-case-insensitive-test
  (testing "card name matching is case-insensitive"
    (is (= {:card/id :dark-ritual :count 4}
           (parser/parse-line "4 dark ritual")))
    (is (= {:card/id :dark-ritual :count 4}
           (parser/parse-line "4 DARK RITUAL")))))


(deftest parse-line-returns-nil-for-comment-test
  (testing "returns nil for // comment lines"
    (is (nil? (parser/parse-line "// Lands (15)")))
    (is (nil? (parser/parse-line "// Main Deck")))))


(deftest parse-line-returns-nil-for-blank-test
  (testing "returns nil for blank lines"
    (is (nil? (parser/parse-line "")))
    (is (nil? (parser/parse-line "   ")))
    (is (nil? (parser/parse-line "\t")))))


(deftest parse-line-returns-sideboard-marker-test
  (testing "returns :sideboard marker for sideboard headers"
    (is (= :sideboard (parser/parse-line "Sideboard")))
    (is (= :sideboard (parser/parse-line "sideboard")))
    (is (= :sideboard (parser/parse-line "SIDEBOARD")))
    (is (= :sideboard (parser/parse-line "Sideboard:")))))


(deftest parse-line-returns-unrecognized-for-unknown-card-test
  (testing "returns :unrecognized with card name for unknown cards"
    (is (= {:unrecognized "Ancestral Recall"}
           (parser/parse-line "4 Ancestral Recall")))))


(deftest parse-line-sb-prefix-test
  (testing "parses SB: prefix lines (Moxfield alternative)"
    (is (= {:card/id :merchant-scroll :count 2 :sideboard? true}
           (parser/parse-line "SB: 2 Merchant Scroll")))))


(deftest parse-line-sb-prefix-case-insensitive-test
  (testing "SB: prefix is case-insensitive"
    (is (= {:card/id :merchant-scroll :count 2 :sideboard? true}
           (parser/parse-line "sb: 2 Merchant Scroll")))))


(deftest parse-line-trims-whitespace-test
  (testing "handles leading/trailing whitespace"
    (is (= {:card/id :dark-ritual :count 4}
           (parser/parse-line "  4 Dark Ritual  ")))))


;; === Full Parser Tests ===

(deftest parse-decklist-simple-main-deck-test
  (testing "parses a simple main deck"
    (let [text "4 Dark Ritual\n4 Lotus Petal\n3 Island"
          result (parser/parse-decklist text)]
      (is (contains? result :ok))
      (is (= [{:card/id :dark-ritual :count 4}
              {:card/id :lotus-petal :count 4}
              {:card/id :island :count 3}]
             (get-in result [:ok :deck/main])))
      (is (empty? (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-with-sideboard-header-test
  (testing "parses main + sideboard separated by header"
    (let [text "4 Dark Ritual\n\nSideboard\n2 Merchant Scroll"
          result (parser/parse-decklist text)]
      (is (contains? result :ok))
      (is (= [{:card/id :dark-ritual :count 4}]
             (get-in result [:ok :deck/main])))
      (is (= [{:card/id :merchant-scroll :count 2}]
             (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-sideboard-colon-header-test
  (testing "handles 'Sideboard:' with colon"
    (let [text "4 Dark Ritual\nSideboard:\n2 Merchant Scroll"
          result (parser/parse-decklist text)]
      (is (= [{:card/id :merchant-scroll :count 2}]
             (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-sb-prefix-lines-test
  (testing "SB: prefix lines go to sideboard regardless of position"
    (let [text "4 Dark Ritual\nSB: 2 Merchant Scroll\n4 Lotus Petal"
          result (parser/parse-decklist text)]
      (is (= [{:card/id :dark-ritual :count 4}
              {:card/id :lotus-petal :count 4}]
             (get-in result [:ok :deck/main])))
      (is (= [{:card/id :merchant-scroll :count 2}]
             (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-ignores-comments-and-blanks-test
  (testing "comments and blank lines are ignored"
    (let [text "// Main Deck\n4 Dark Ritual\n\n// Artifacts\n4 Lotus Petal"
          result (parser/parse-decklist text)]
      (is (= [{:card/id :dark-ritual :count 4}
              {:card/id :lotus-petal :count 4}]
             (get-in result [:ok :deck/main]))))))


(deftest parse-decklist-strips-set-codes-test
  (testing "Moxfield format with set codes parsed correctly"
    (let [text "4 Dark Ritual (VIS) 72\n4 Lotus Petal (TMP) 294"
          result (parser/parse-decklist text)]
      (is (= [{:card/id :dark-ritual :count 4}
              {:card/id :lotus-petal :count 4}]
             (get-in result [:ok :deck/main]))))))


(deftest parse-decklist-consolidates-duplicates-test
  (testing "duplicate card names are consolidated by summing counts"
    (let [text "2 Dark Ritual\n2 Dark Ritual"
          result (parser/parse-decklist text)]
      (is (= [{:card/id :dark-ritual :count 4}]
             (get-in result [:ok :deck/main]))))))


(deftest parse-decklist-error-on-unrecognized-cards-test
  (testing "returns error with list of unrecognized card names"
    (let [text "4 Dark Ritual\n3 Ancestral Recall\n2 Black Lotus"
          result (parser/parse-decklist text)]
      (is (contains? result :error))
      (is (= #{"Ancestral Recall" "Black Lotus"}
             (set (get-in result [:error :unrecognized])))))))


(deftest parse-decklist-all-unrecognized-test
  (testing "returns error even if ALL cards are unrecognized"
    (let [text "4 Ancestral Recall\n1 Black Lotus"
          result (parser/parse-decklist text)]
      (is (contains? result :error))
      (is (= 2 (count (get-in result [:error :unrecognized])))))))


(deftest parse-decklist-moxfield-full-export-test
  (testing "handles realistic Moxfield text export"
    (let [text (str "4 Dark Ritual (VIS) 72\n"
                    "4 Cabal Ritual (TOR) 51\n"
                    "4 Lotus Petal (TMP) 294\n"
                    "4 Lion's Eye Diamond (MIR) 307\n"
                    "\n"
                    "Sideboard\n"
                    "2 Merchant Scroll (HML) 38\n")
          result (parser/parse-decklist text)]
      (is (contains? result :ok))
      (is (= 4 (count (get-in result [:ok :deck/main]))))
      (is (= [{:card/id :merchant-scroll :count 2}]
             (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-mtggoldfish-format-test
  (testing "handles MTGGoldfish text export (blank line as sideboard separator)"
    (let [text (str "4 Dark Ritual\n"
                    "4 Cabal Ritual\n"
                    "4 Lotus Petal\n"
                    "4 Lion's Eye Diamond\n"
                    "\n"
                    "2 Merchant Scroll\n")
          result (parser/parse-decklist text)]
      (is (contains? result :ok))
      (is (= 4 (count (get-in result [:ok :deck/main]))))
      (is (= [{:card/id :merchant-scroll :count 2}]
             (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-empty-text-test
  (testing "empty text returns ok with empty deck"
    (let [result (parser/parse-decklist "")]
      (is (contains? result :ok))
      (is (empty? (get-in result [:ok :deck/main])))
      (is (empty? (get-in result [:ok :deck/side]))))))


;; === Blank-Line Sideboard Separator Tests ===

(deftest parse-decklist-blank-line-separator-no-comments-test
  (testing "blank line acts as sideboard separator when no comments or markers"
    (let [text "4 Dark Ritual\n4 Cabal Ritual\n\n2 Merchant Scroll"
          result (parser/parse-decklist text)]
      (is (contains? result :ok))
      (is (= [{:card/id :dark-ritual :count 4}
              {:card/id :cabal-ritual :count 4}]
             (get-in result [:ok :deck/main])))
      (is (= [{:card/id :merchant-scroll :count 2}]
             (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-blank-line-ignored-with-comments-test
  (testing "blank line is cosmetic when comments are present (not a separator)"
    (let [text "// Main Deck\n4 Dark Ritual\n\n// Artifacts\n4 Lotus Petal"
          result (parser/parse-decklist text)]
      (is (contains? result :ok))
      (is (= [{:card/id :dark-ritual :count 4}
              {:card/id :lotus-petal :count 4}]
             (get-in result [:ok :deck/main])))
      (is (empty? (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-blank-line-ignored-with-sideboard-header-test
  (testing "blank line is cosmetic when explicit Sideboard header present"
    (let [text "4 Dark Ritual\n\n4 Lotus Petal\n\nSideboard\n2 Merchant Scroll"
          result (parser/parse-decklist text)]
      (is (contains? result :ok))
      (is (= #{{:card/id :dark-ritual :count 4}
               {:card/id :lotus-petal :count 4}}
             (set (get-in result [:ok :deck/main]))))
      (is (= [{:card/id :merchant-scroll :count 2}]
             (get-in result [:ok :deck/side]))))))


(deftest parse-decklist-leading-blank-lines-ignored-test
  (testing "blank lines before any cards don't trigger sideboard"
    (let [text "\n\n4 Dark Ritual\n4 Lotus Petal"
          result (parser/parse-decklist text)]
      (is (contains? result :ok))
      (is (= [{:card/id :dark-ritual :count 4}
              {:card/id :lotus-petal :count 4}]
             (get-in result [:ok :deck/main])))
      (is (empty? (get-in result [:ok :deck/side]))))))


;; === Text Regeneration Tests ===

(deftest deck->text-basic-test
  (testing "generates formatted text from deck data"
    (let [deck {:deck/name "Test Deck"
                :deck/main [{:card/id :dark-ritual :count 4}
                            {:card/id :island :count 3}]}
          text (parser/deck->text deck)]
      (is (string? text))
      (is (str/includes? text "Dark Ritual"))
      (is (str/includes? text "Island"))
      (is (str/includes? text "4 Dark Ritual"))
      (is (str/includes? text "3 Island")))))


(deftest deck->text-groups-by-type-test
  (testing "groups cards by type with section headers"
    (let [deck {:deck/name "Test Deck"
                :deck/main [{:card/id :dark-ritual :count 4}
                            {:card/id :island :count 3}
                            {:card/id :lotus-petal :count 4}]}
          text (parser/deck->text deck)]
      (is (str/includes? text "// Instants"))
      (is (str/includes? text "// Lands"))
      (is (str/includes? text "// Artifacts")))))


(deftest deck->text-includes-header-test
  (testing "includes deck name and total count in header"
    (let [deck {:deck/name "My Storm Deck"
                :deck/main [{:card/id :dark-ritual :count 4}]}
          text (parser/deck->text deck)]
      (is (str/includes? text "My Storm Deck"))
      (is (str/includes? text "Main Deck")))))


(deftest deck->text-includes-sideboard-test
  (testing "includes sideboard section when present"
    (let [deck {:deck/name "Test"
                :deck/main [{:card/id :dark-ritual :count 4}]
                :deck/side [{:card/id :merchant-scroll :count 2}]}
          text (parser/deck->text deck)]
      (is (str/includes? text "Sideboard"))
      (is (str/includes? text "2 Merchant Scroll")))))


(deftest deck->text-no-sideboard-when-empty-test
  (testing "omits sideboard section when side is empty"
    (let [deck {:deck/name "Test"
                :deck/main [{:card/id :dark-ritual :count 4}]
                :deck/side []}
          text (parser/deck->text deck)]
      (is (not (str/includes? text "Sideboard"))))))


(deftest deck->text-type-order-test
  (testing "types appear in correct order: Lands, Instants, Sorceries, Artifacts"
    (let [deck {:deck/name "Test"
                :deck/main [{:card/id :dark-ritual :count 4}     ; instant
                            {:card/id :careful-study :count 2}   ; sorcery
                            {:card/id :island :count 3}          ; land
                            {:card/id :lotus-petal :count 4}]}   ; artifact
          text (parser/deck->text deck)
          land-idx (str/index-of text "// Lands")
          instant-idx (str/index-of text "// Instants")
          sorcery-idx (str/index-of text "// Sorceries")
          artifact-idx (str/index-of text "// Artifacts")]
      (is (some? land-idx))
      (is (some? instant-idx))
      (is (some? sorcery-idx))
      (is (some? artifact-idx))
      (is (< land-idx instant-idx))
      (is (< instant-idx sorcery-idx))
      (is (< sorcery-idx artifact-idx)))))


;; === Round-Trip Test ===

(deftest round-trip-parse-generate-parse-test
  (testing "parse(generate(deck)) produces equivalent deck data"
    (let [deck {:deck/name "Round Trip Test"
                :deck/main [{:card/id :dark-ritual :count 4}
                            {:card/id :lotus-petal :count 4}
                            {:card/id :island :count 3}]
                :deck/side [{:card/id :merchant-scroll :count 2}]}
          text (parser/deck->text deck)
          reparsed (parser/parse-decklist text)]
      (is (contains? reparsed :ok))
      (is (= (set (get-in reparsed [:ok :deck/main]))
             (set (:deck/main deck))))
      (is (= (set (get-in reparsed [:ok :deck/side]))
             (set (:deck/side deck)))))))
