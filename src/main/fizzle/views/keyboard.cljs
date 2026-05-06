(ns fizzle.views.keyboard
  "Keyboard shortcut infrastructure for the game screen.

   Provides:
     keymap          — data literal [context key-string] → action-keyword
     derive-context  — pending-selection → context keyword
     action-dispatch — action-keyword × app-state-map → dispatch vector or nil
     hint-for-action — (context, action) → human-readable key string or nil
     use-keyboard-shortcuts — hook that adds/removes document keydown listener

   Context keywords:
     :normal           — no pending selection (cast, yield, undo, cycle)
     :binary-choice    — :binary-choice mechanism (1/2 choose)
     :pick-mode        — :pick-mode mechanism (1/2/3 choose)
     :accumulate       — :accumulate mechanism (W/S increment/decrement, Space confirm)
     :allocate-resource — :allocate-resource mechanism (1-5 color, Space confirm)
     :storm-split      — :accumulate mechanism + :storm-split domain (chord-based target allocation)
     :modal            — modal mechanisms (shortcuts suppressed)

   Modal mechanisms (shortcuts suppressed): :pick-from-zone, :reorder, :n-slot-targeting"
  (:require
    [clojure.string]
    [fizzle.db.game-state :as game-state]
    [fizzle.engine.sorting :as sorting]
    [fizzle.events.casting :as casting-events]
    [fizzle.events.cycling :as cycling-events]
    [fizzle.events.lands :as lands-events]
    [fizzle.events.priority-flow :as priority-flow-events]
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.events.selection.storm :as storm-events]
    [fizzle.history.events :as history-events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


;; === Modal mechanism set ===
;; Mechanisms that render full modal overlays — shortcuts suppressed during these.

(def ^:private modal-mechanisms
  #{:reorder})


;; === Mana color order for allocate-resource context ===
;; Number keys 1-5 map positionally to colors available in the pool.
;; This matches the order in mana_pool.cljs color-order.

(def ^:private color-order
  [:white :blue :black :red :green :colorless])


;; === Keymap ===
;;
;; Maps [context-keyword normalized-key-string] → action-keyword.
;; Key strings use normalized form: " " → "Space", shift state composed as "Shift+Space".
;; See normalize-key for the normalization contract.

(def keymap
  {;; Normal context: cast, yield, undo, cycle
   [:normal "e"]           :cast-and-yield
   [:normal "f"]           :cast
   [:normal "Space"]       :yield
   [:normal "Shift+Space"] :yield-all
   [:normal "q"]           :undo
   [:normal "r"]           :cycle

   ;; binary-choice context: 1/2 select Nth choice
   [:binary-choice "1"] :choose-1
   [:binary-choice "2"] :choose-2

   ;; pick-mode context: 1/2/3 select Nth mode
   [:pick-mode "1"] :choose-1
   [:pick-mode "2"] :choose-2
   [:pick-mode "3"] :choose-3

   ;; accumulate context: W/S increment/decrement, Space confirm
   [:accumulate "w"]     :increment
   [:accumulate "s"]     :decrement
   [:accumulate "Space"] :confirm

   ;; allocate-resource context: 1-5 allocate Nth color, Space confirm
   [:allocate-resource "1"]     :allocate-1
   [:allocate-resource "2"]     :allocate-2
   [:allocate-resource "3"]     :allocate-3
   [:allocate-resource "4"]     :allocate-4
   [:allocate-resource "5"]     :allocate-5
   [:allocate-resource "Space"] :confirm

   ;; storm-split context: chord-based target allocation
   ;; First key selects target (1 or 2), second key selects action.
   ;; "1"/"2" alone → :chord-start (prefix mode, wait for second key)
   ;; Composed chords: "1>w" = add-all to target 1, "1>s" = clear target 1, etc.
   ;; Shift+W = +1 (increment), Shift+S = -1 (decrement)
   [:storm-split "1"]         :chord-start
   [:storm-split "2"]         :chord-start
   [:storm-split "1>w"]       :storm-add-all-1
   [:storm-split "1>s"]       :storm-clear-1
   [:storm-split "1>Shift+W"] :storm-inc-1
   [:storm-split "1>Shift+S"] :storm-dec-1
   [:storm-split "2>w"]       :storm-add-all-2
   [:storm-split "2>s"]       :storm-clear-2
   [:storm-split "2>Shift+W"] :storm-inc-2
   [:storm-split "2>Shift+S"] :storm-dec-2
   [:storm-split "Space"]     :confirm

   ;; zone-pick context: pick card(s) from zone
   ;; Number keys 1-9 → :chord-start (pile selection, wait for second key)
   ;; Second key selects card within pile via dynamic zone-pick-toggle-dispatch resolution
   [:zone-pick "1"]     :chord-start
   [:zone-pick "2"]     :chord-start
   [:zone-pick "3"]     :chord-start
   [:zone-pick "4"]     :chord-start
   [:zone-pick "5"]     :chord-start
   [:zone-pick "6"]     :chord-start
   [:zone-pick "7"]     :chord-start
   [:zone-pick "8"]     :chord-start
   [:zone-pick "9"]     :chord-start
   [:zone-pick "Space"]  :confirm
   [:zone-pick "Escape"] :secondary

   ;; flat-targeting context: n-slot targeting
   [:flat-targeting "1"]      :select-1
   [:flat-targeting "2"]      :select-2
   [:flat-targeting "3"]      :select-3
   [:flat-targeting "4"]      :select-4
   [:flat-targeting "5"]      :select-5
   [:flat-targeting "6"]      :select-6
   [:flat-targeting "7"]      :select-7
   [:flat-targeting "8"]      :select-8
   [:flat-targeting "9"]      :select-9
   [:flat-targeting "Space"]  :confirm
   [:flat-targeting "Escape"] :secondary})


;; === Key normalization ===

(defn normalize-key
  "Normalize a JS KeyboardEvent to a keymap key string.
   event.key \" \" (spacebar) → \"Space\"; otherwise use event.key as-is.
   If event.shiftKey is true, prepend \"Shift+\" to the normalized key."
  [event]
  (let [raw-key (.-key event)
        normalized (if (= raw-key " ") "Space" raw-key)
        shift? (.-shiftKey event)]
    (if shift?
      (str "Shift+" normalized)
      normalized)))


;; === Context derivation ===

(defn derive-context
  "Derive keyboard context from pending-selection.
   nil → :normal (no active selection)
   Modal mechanisms (:reorder only) → :modal (suppressed)
   :pick-from-zone mechanism → :zone-pick
   :n-slot-targeting mechanism → :flat-targeting
   :accumulate mechanism + :storm-split domain → :storm-split (chord-based target allocation)
   Other mechanisms → the mechanism keyword itself (e.g. :binary-choice, :pick-mode)"
  [pending-selection]
  (if (nil? pending-selection)
    :normal
    (let [mechanism (:selection/mechanism pending-selection)]
      (cond
        (contains? modal-mechanisms mechanism)
        :modal

        (and (= mechanism :accumulate)
             (= :storm-split (:selection/domain pending-selection)))
        :storm-split

        (= mechanism :pick-from-zone)
        :zone-pick

        (= mechanism :n-slot-targeting)
        :flat-targeting

        :else
        mechanism))))


;; === Allocate-resource helper ===

(defn- allocate-nth-color
  "Resolve the Nth color from the remaining-pool in pending-selection.
   Returns a dispatch vector for ::cost-events/allocate-mana-color or nil."
  [pending-selection n]
  (when pending-selection
    (let [remaining-pool (:selection/remaining-pool pending-selection)
          ;; Filter color-order to colors present in pool with positive amounts
          available-colors (filterv (fn [c]
                                      (let [amt (get remaining-pool c)]
                                        (and (some? amt) (pos? amt))))
                                    color-order)
          color (nth available-colors n nil)]
      (when color
        [::cost-events/allocate-mana-color color]))))


;; === Storm-split chord helper ===

(defn- storm-target-dispatch
  "Dispatch a storm-split action for the Nth target (0-indexed idx).
   Returns [::storm-events/adjust-storm-split target-id delta] or nil.

   action types:
     :add-all — remaining delta: copy-count minus total-already-allocated
     :clear   — negative current allocation (removes all from this target)
     :inc     — +1
     :dec     — -1"
  [pending-selection idx action-type]
  (when pending-selection
    (let [valid-targets (:selection/valid-targets pending-selection)
          target-id     (nth valid-targets idx nil)]
      (when target-id
        (let [allocation (:selection/allocation pending-selection)
              current    (get allocation target-id 0)
              copy-count (:selection/copy-count pending-selection)
              total      (apply + (vals allocation))
              delta      (case action-type
                           :add-all (- copy-count total)
                           :clear   (- current)
                           :inc     1
                           :dec     -1)]
          [::storm-events/adjust-storm-split target-id delta])))))


;; === Zone-pick chord helper ===

(defn- zone-pick-toggle-dispatch
  "Resolve a zone-pick chord to a toggle-selection dispatch vector.
   prefix-str is the pile number (1-based), key-str is the card number (1-based).
   Returns [::selection-events/toggle-selection object-id] or nil.

   Piles are ordered as returned by sorting/group-by-cmc:
   [:lands [land-objs...]] first (if non-empty), then [cmc [objs...]] in ascending order.

   Does NOT filter by valid-targets — dispatches for any card in pile regardless
   of validity (matching click behavior; event layer enforces validity).

   Returns nil if:
   - prefix-str or key-str are non-numeric (NaN from js/parseInt)
   - pile index (prefix-n - 1) is out of bounds
   - card index (key-n - 1) is out of bounds within the pile
   - selection-cards is nil or empty"
  [selection-cards _pending-selection prefix-str key-str]
  (let [pile-n (js/parseInt prefix-str 10)
        card-n (js/parseInt key-str 10)]
    (when-not (or (js/isNaN pile-n) (js/isNaN card-n))
      (let [pile-idx (dec pile-n)
            card-idx (dec card-n)]
        (when (and (>= pile-idx 0) (>= card-idx 0))
          (let [piles        (sorting/group-by-cmc (or selection-cards []))
                pile-entry   (nth piles pile-idx nil)
                cards-vec    (second pile-entry)
                card         (nth cards-vec card-idx nil)]
            (when card
              [::selection-events/toggle-selection (:object/id card)])))))))


;; === Flat-targeting helper ===

(defn- select-nth-candidate
  "Resolve the Nth selectable element in flat-targeting context.
   Returns [::selection-events/toggle-selection id] or nil.

   Detects player targets by scanning valid-targets for player-id keywords.
   Ordered list: player keywords first (sorted), then card objects from selection-cards.
   Does NOT filter by valid-targets for card numbering."
  [selection-cards pending-selection n]
  (let [valid-targets (or (:selection/valid-targets pending-selection) [])
        ;; Extract player keywords from valid-targets
        player-ids (filterv (fn [t]
                              (or (= t game-state/human-player-id)
                                  (= t game-state/opponent-player-id)))
                            valid-targets)
        ;; Build ordered candidates: sorted players first, then cards
        ordered-candidates (into (vec (sort player-ids))
                                 (or selection-cards []))
        candidate (nth ordered-candidates n nil)]
    (when candidate
      [::selection-events/toggle-selection
       (if (keyword? candidate)
         candidate
         (:object/id candidate))])))


;; === Action dispatch ===

(defn action-dispatch
  "Resolve an action keyword to a re-frame dispatch vector, or nil if guard fails.

   app-state-map must contain:
     :selected-card    — currently selected card object-id (or nil)
     :can-cast?        — boolean, whether selected card can be cast
     :can-play-land?   — boolean, whether selected card can be played as land
     :can-cycle?       — boolean, whether selected card can be cycled
     :stack            — current stack items (seq or nil)
     :pending-selection — current pending selection map (or nil)
     :selection-cards  — vec of candidate objects for flat-targeting (or nil)

   Returns a dispatch vector [event-kw & args], a {:dispatch-n [...]} map for
   multi-dispatch, or nil if the guard fails."
  [action {:keys [selected-card can-cast? can-play-land? can-cycle? pending-selection selection-cards]}]
  (case action
    :cast
    (cond
      can-cast?      [::casting-events/cast-spell {:object-id selected-card}]
      can-play-land? [::lands-events/play-land selected-card]
      :else          nil)

    :cast-and-yield
    (cond
      can-cast?      [::priority-flow-events/cast-and-yield {:object-id selected-card}]
      can-play-land? [::lands-events/play-land selected-card]
      :else          nil)

    :yield
    [::priority-flow-events/yield]

    :yield-all
    [::priority-flow-events/yield-all]

    :undo
    [::history-events/pop-entry]

    :cycle
    (if can-cycle?
      [::cycling-events/cycle-card selected-card]
      nil)

    ;; binary-choice / pick-mode: dispatch toggle + confirm for the Nth choice
    :choose-1
    (let [choices (:selection/choices pending-selection)
          choice  (first choices)]
      (when choice
        (let [action-mode? (boolean (:selection/valid-targets pending-selection))
              toggle-val   (if action-mode? (:choice/action choice) choice)]
          {:dispatch-n [[::selection-events/toggle-selection toggle-val]
                        [::selection-events/confirm-selection]]})))

    :choose-2
    (let [choices (:selection/choices pending-selection)
          choice  (second choices)]
      (when choice
        (let [action-mode? (boolean (:selection/valid-targets pending-selection))
              toggle-val   (if action-mode? (:choice/action choice) choice)]
          {:dispatch-n [[::selection-events/toggle-selection toggle-val]
                        [::selection-events/confirm-selection]]})))

    :choose-3
    (let [choices (:selection/choices pending-selection)
          choice  (nth choices 2 nil)]
      (when choice
        (let [action-mode? (boolean (:selection/valid-targets pending-selection))
              toggle-val   (if action-mode? (:choice/action choice) choice)]
          {:dispatch-n [[::selection-events/toggle-selection toggle-val]
                        [::selection-events/confirm-selection]]})))

    ;; accumulate context
    :increment
    [::cost-events/increment-x-value]

    :decrement
    [::cost-events/decrement-x-value]

    :confirm
    [::selection-events/confirm-selection]

    ;; allocate-resource: allocate the Nth color from the pool
    :allocate-1 (allocate-nth-color pending-selection 0)
    :allocate-2 (allocate-nth-color pending-selection 1)
    :allocate-3 (allocate-nth-color pending-selection 2)
    :allocate-4 (allocate-nth-color pending-selection 3)
    :allocate-5 (allocate-nth-color pending-selection 4)

    ;; storm-split: chord-based target allocation
    ;; :chord-start is handled in handle-keydown (prefix mode), not here
    :storm-add-all-1 (storm-target-dispatch pending-selection 0 :add-all)
    :storm-clear-1   (storm-target-dispatch pending-selection 0 :clear)
    :storm-inc-1     (storm-target-dispatch pending-selection 0 :inc)
    :storm-dec-1     (storm-target-dispatch pending-selection 0 :dec)
    :storm-add-all-2 (storm-target-dispatch pending-selection 1 :add-all)
    :storm-clear-2   (storm-target-dispatch pending-selection 1 :clear)
    :storm-inc-2     (storm-target-dispatch pending-selection 1 :inc)
    :storm-dec-2     (storm-target-dispatch pending-selection 1 :dec)

    ;; flat-targeting: select the Nth candidate
    :select-1 (select-nth-candidate selection-cards pending-selection 0)
    :select-2 (select-nth-candidate selection-cards pending-selection 1)
    :select-3 (select-nth-candidate selection-cards pending-selection 2)
    :select-4 (select-nth-candidate selection-cards pending-selection 3)
    :select-5 (select-nth-candidate selection-cards pending-selection 4)
    :select-6 (select-nth-candidate selection-cards pending-selection 5)
    :select-7 (select-nth-candidate selection-cards pending-selection 6)
    :select-8 (select-nth-candidate selection-cards pending-selection 7)
    :select-9 (select-nth-candidate selection-cards pending-selection 8)

    ;; secondary action: domain-appropriate action (Find Nothing, Random, Clear/Cancel)
    ;; mirrors zone_pick.cljs:54-68 secondary-button logic exactly
    :secondary
    (when pending-selection
      (let [domain (:selection/domain pending-selection)]
        (case domain
          :tutor
          {:dispatch-n [[::selection-events/cancel-selection]
                        [::selection-events/confirm-selection]]}
          :pile-choice
          [::selection-events/select-random-pile-choice]
          :exile-cost
          [::cost-events/cancel-exile-cards-selection]
          ;; default: cancel-selection
          [::selection-events/cancel-selection])))

    ;; Unknown action
    nil))


;; === Hint reverse-lookup ===

(defn- format-chord-key
  "Format a chord key string (e.g., \"1>w\" or \"1>Shift+W\") as a human-readable display string.

   A chord key contains two parts separated by \">\": prefix (target number) and suffix (action key).

   Returns: \"<prefix> <formatted-suffix>\"
   where formatted-suffix is:
     - uppercase single letter (\"w\" → \"W\")
     - shift indicator with uppercase (\"Shift+w\" → \"⇧W\")"
  [key-str]
  (let [parts (clojure.string/split key-str ">")]
    (if (= 2 (count parts))
      (let [prefix (first parts)
            suffix (second parts)]
        (str prefix " "
             (if (clojure.string/starts-with? suffix "Shift+")
               ;; Format: "Shift+w" → "⇧W"
               (str "⇧" (.toUpperCase (subs suffix 6)))
               ;; Format: "w" → "W"
               (.toUpperCase suffix))))
      ;; Fallback for non-chord keys
      key-str)))


(defn hint-for-action
  "Returns a human-readable key display string for the given context+action pair,
   or nil if no binding exists.

   Examples:
     (hint-for-action :normal :yield)       → \"Space\"
     (hint-for-action :accumulate :increment) → \"W\"
     (hint-for-action :normal :cast)        → \"E\"
     (hint-for-action :storm-split :storm-add-all-1) → \"1 W\"
     (hint-for-action :storm-split :storm-inc-1) → \"1 ⇧W\""
  [context action]
  (some (fn [[[ctx key-str] act]]
          (when (and (= ctx context) (= act action))
            ;; Convert internal key string to human-readable display string
            (if (clojure.string/includes? key-str ">")
              ;; Chord key: format as "N <action>"
              (format-chord-key key-str)
              ;; Non-chord key
              (case key-str
                "Space"       "Space"
                "Shift+Space" "Shift+Space"
                ;; Single letter: display uppercase
                (if (= 1 (count key-str))
                  (.toUpperCase key-str)
                  key-str)))))
        keymap))


;; === Keydown handler ===

(defn text-input-target?
  "Returns true when the event target is a text input element (INPUT or TEXTAREA).
   Keyboard shortcuts are suppressed on these elements."
  [event]
  (let [tag (some-> event .-target .-tagName .toLowerCase)]
    (or (= tag "input") (= tag "textarea"))))


(defn- dispatch-result!
  "Fire a dispatch result: single vector or {:dispatch-n [...]}"
  [result]
  (when result
    (if (map? result)
      (doseq [v (:dispatch-n result)]
        (rf/dispatch v))
      (rf/dispatch result))))


(defn- handle-keydown
  "Handle a keydown event with optional chord-prefix support.

   chord-prefix-ref — atom holding the current chord prefix string (e.g. \"1\"), or nil.
   selection-cards-ref — atom holding the current selection-cards value.

   Chord flow:
     1. If prefix is active: compose \"<prefix>><normalized-key>\" and look it up.
        - Found → dispatch action, clear prefix.
        - Not found → clear prefix, fall through and process normalized-key as standalone.
     2. If no prefix: look up [context normalized-key].
        - Action is :chord-start → store normalized-key as new prefix (no dispatch).
        - Otherwise → dispatch normally."
  [event pending-selection-ref app-state-ref chord-prefix-ref selection-cards-ref]
  (when-not (text-input-target? event)
    (let [pending-selection @pending-selection-ref
          context           (derive-context pending-selection)]
      (when-not (= context :modal)
        (let [normalized-key (normalize-key event)
              prefix         @chord-prefix-ref]
          (if prefix
            ;; --- Chord second-key path ---
            (let [composed-key (str prefix ">" normalized-key)
                  composed-action (get keymap [context composed-key])]
              (reset! chord-prefix-ref nil)
              (if composed-action
                ;; Composed chord found → dispatch and stop
                (do (.preventDefault event)
                    (let [app-state @app-state-ref
                          result    (action-dispatch composed-action
                                                     (assoc app-state :pending-selection pending-selection
                                                            :selection-cards @selection-cards-ref))]
                      (dispatch-result! result)))
                ;; Composed chord NOT found → check zone-pick dynamic resolution first,
                ;; then fall through to standalone key lookup
                (let [zone-pick-dispatch
                      (when (= context :zone-pick)
                        (zone-pick-toggle-dispatch @selection-cards-ref pending-selection
                                                   prefix normalized-key))]
                  (if zone-pick-dispatch
                    ;; Zone-pick dynamic chord resolved → dispatch and stop
                    (do (.preventDefault event)
                        (dispatch-result! zone-pick-dispatch))
                    ;; Fall through to standalone key lookup
                    (let [standalone-action (get keymap [context normalized-key])]
                      (when standalone-action
                        (.preventDefault event)
                        (if (= standalone-action :chord-start)
                          ;; Second key is itself a chord-start → store new prefix
                          (reset! chord-prefix-ref normalized-key)
                          (let [app-state @app-state-ref
                                result    (action-dispatch standalone-action
                                                           (assoc app-state :pending-selection pending-selection
                                                                  :selection-cards @selection-cards-ref))]
                            (dispatch-result! result)))))))))
            ;; --- No prefix: standalone key path ---
            (let [action (get keymap [context normalized-key])]
              (when action
                (.preventDefault event)
                (if (= action :chord-start)
                  ;; Store prefix for next key
                  (reset! chord-prefix-ref normalized-key)
                  (let [app-state @app-state-ref
                        result    (action-dispatch action (assoc app-state :pending-selection pending-selection
                                                                 :selection-cards @selection-cards-ref))]
                    (dispatch-result! result)))))))))))


;; === Hook ===

(defn use-keyboard-shortcuts
  "Subscribes to game state and attaches a keydown listener to js/document.
   Returns a cleanup function that removes the listener.

   Intended to be called from a Reagent component lifecycle (e.g., use-effect).
   The returned cleanup fn should be called on component unmount."
  []
  ;; Use atoms to hold current subscription values so the handler closure
  ;; always reads the latest state without re-subscribing on every keypress.
  (let [pending-selection-ref (atom @(rf/subscribe [::subs/pending-selection]))
        app-state-ref         (atom {:selected-card  @(rf/subscribe [::subs/selected-card])
                                     :can-cast?       @(rf/subscribe [::subs/can-cast?])
                                     :can-play-land?  @(rf/subscribe [::subs/can-play-land?])
                                     :can-cycle?      @(rf/subscribe [::subs/can-cycle?])
                                     :stack           @(rf/subscribe [::subs/stack])})
        selection-cards-ref   (atom @(rf/subscribe [::subs/selection-cards]))
        ;; Chord prefix: keyboard-local state (not in app-db). Holds first key of an
        ;; in-progress chord sequence (e.g. "1"), nil when no chord is active.
        chord-prefix-ref      (atom nil)
        ;; Watch subscriptions and keep atoms up to date
        pending-sel-sub       (rf/subscribe [::subs/pending-selection])
        selected-card-sub     (rf/subscribe [::subs/selected-card])
        can-cast-sub          (rf/subscribe [::subs/can-cast?])
        can-play-land-sub     (rf/subscribe [::subs/can-play-land?])
        can-cycle-sub         (rf/subscribe [::subs/can-cycle?])
        stack-sub             (rf/subscribe [::subs/stack])
        selection-cards-sub   (rf/subscribe [::subs/selection-cards])
        update-state!         (fn []
                                (reset! pending-selection-ref @pending-sel-sub)
                                (reset! app-state-ref
                                        {:selected-card @selected-card-sub
                                         :can-cast?      @can-cast-sub
                                         :can-play-land? @can-play-land-sub
                                         :can-cycle?     @can-cycle-sub
                                         :stack          @stack-sub})
                                (reset! selection-cards-ref @selection-cards-sub))
        handler               (fn [event]
                                (update-state!)
                                (handle-keydown event pending-selection-ref app-state-ref chord-prefix-ref selection-cards-ref))]
    (.addEventListener js/document "keydown" handler)
    (fn cleanup
      []
      (.removeEventListener js/document "keydown" handler))))
