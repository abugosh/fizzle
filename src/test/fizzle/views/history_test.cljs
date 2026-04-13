(ns fizzle.views.history-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.history.core :as history]
    [fizzle.views.history :as views]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


(defn- make-db-with-entries
  "Create an app-db with N history entries on main timeline."
  [n]
  (reduce (fn [db i]
            (let [snapshot (keyword (str "db-" i))
                  entry (history/make-entry snapshot (keyword (str "evt-" i))
                                            (str "Entry " i) 1)]
              (-> db
                  (assoc :game/db snapshot)
                  (history/append-entry entry))))
          (merge {:game/db :db-init} (history/init-history))
          (range n)))


(defn- make-db-with-fork
  "Create an app-db with entries on main + a fork, on the fork branch.
   Returns [db fork-id]."
  []
  (let [db (make-db-with-entries 3)
        rewound (history/step-to db 1)
        fork-entry (history/make-entry :db-fork :evt-fork "Fork entry" 1)
        forked (history/auto-fork rewound fork-entry)
        fork-id (:history/current-branch forked)]
    [forked fork-id]))


(defn- hiccup-children
  "Extract child elements from a hiccup node, handling both vectors and
   lazy sequences (produced by for) nested in the hiccup tree."
  [node]
  (when (vector? node)
    (let [elems (if (map? (second node))
                  (drop 2 node)
                  (drop 1 node))]
      (mapcat (fn [elem]
                (cond
                  (vector? elem) [elem]
                  (seq? elem) (filter vector? elem)
                  :else []))
              elems))))


(defn- find-in-hiccup
  "Recursively search hiccup tree for a node matching pred.
   Returns the first matching node, or nil."
  [hiccup pred]
  (cond
    (not (vector? hiccup)) nil
    (pred hiccup) hiccup
    :else (some #(find-in-hiccup % pred)
                (hiccup-children hiccup))))


(defn- find-all-in-hiccup
  "Recursively find all nodes matching pred in hiccup tree."
  [hiccup pred]
  (cond
    (not (vector? hiccup)) []
    (pred hiccup) (into [hiccup]
                        (mapcat #(find-all-in-hiccup % pred)
                                (hiccup-children hiccup)))
    :else (into []
                (mapcat #(find-all-in-hiccup % pred)
                        (hiccup-children hiccup)))))


(defn- node-text
  "Get the text content of a hiccup node (last string element)."
  [node]
  (last (filter string? node)))


(defn- node-on-click
  "Get the :on-click handler from a hiccup node's props map."
  [node]
  (when (and (vector? node) (>= (count node) 2) (map? (second node)))
    (:on-click (second node))))


(defn- capture-dispatched-event
  "Call on-click-fn with re-frame/dispatch intercepted.
   Returns the event vector that was dispatched, or nil if nothing dispatched."
  [on-click-fn]
  (let [captured (atom nil)]
    (with-redefs [rf/dispatch (fn [event] (reset! captured event))]
      (on-click-fn #js {}))
    @captured))


;; === fork-list branch switching tests ===

(deftest test-fork-list-main-entry-has-click-handler-when-on-fork
  (testing "When on a fork branch, the 'main' entry in fork-list should have an on-click handler"
    (let [[db _fork-id] (make-db-with-fork)]
      (reset! rf-db/app-db db)
      (let [hiccup (views/fork-list-data)
            main-node (find-in-hiccup hiccup
                                      (fn [n]
                                        (and (vector? n)
                                             (= "main" (node-text n)))))
            on-click  (node-on-click main-node)]
        (is (some? main-node) "Should find a 'main' text node")
        (is (some? on-click)
            "The 'main' entry should have an on-click handler when on a fork branch")
        ;; Bug caught: (some? on-click) passes for any non-nil fn, including one that
        ;; dispatches the WRONG event (e.g. delete-fork instead of switch-branch nil)
        ;; Assert the on-click dispatches [::switch-branch nil] to go back to main
        (let [dispatched (capture-dispatched-event on-click)]
          (is (= :fizzle.history.events/switch-branch (first dispatched))
              "on-click must dispatch ::switch-branch event to switch branches")
          (is (nil? (second dispatched))
              "on-click must dispatch switch-branch with nil (main branch id)"))))))


(deftest test-fork-list-main-entry-no-click-handler-when-on-main
  (testing "When on main branch, the 'main' entry should NOT have an on-click handler"
    (let [db (make-db-with-entries 3)]
      (reset! rf-db/app-db db)
      (let [hiccup (views/fork-list-data)
            main-node (find-in-hiccup hiccup
                                      (fn [n]
                                        (and (vector? n)
                                             (= "main" (node-text n)))))]
        (is (some? main-node) "Should find a 'main' text node")
        (is (nil? (node-on-click main-node))
            "The 'main' entry should NOT have an on-click handler when already on main")))))


(deftest test-fork-list-fork-entries-have-click-handlers
  (testing "Non-active fork entries in fork-list should have on-click handlers"
    (let [[db fork-id] (make-db-with-fork)
          ;; Switch back to main so the fork entry is non-active
          db-main (history/switch-branch db nil)]
      (reset! rf-db/app-db db-main)
      (let [hiccup (views/fork-list-data)
            fork-nodes (find-all-in-hiccup
                         hiccup
                         (fn [n]
                           (and (vector? n)
                                (string? (node-text n))
                                (not= "main" (node-text n))
                                (not= "Branches" (node-text n)))))
            on-click  (node-on-click (first fork-nodes))]
        (is (= 1 (count fork-nodes)) "Should find exactly one fork entry")
        (is (some? on-click)
            "Non-active fork entry should have an on-click handler")
        ;; Bug caught: (some? on-click) passes for any fn; the fork on-click must dispatch
        ;; ::switch-branch with the fork's id (not nil, not a wrong branch id)
        (let [dispatched (capture-dispatched-event on-click)]
          (is (= :fizzle.history.events/switch-branch (first dispatched))
              "Fork on-click must dispatch ::switch-branch to switch to the fork")
          (is (= fork-id (second dispatched))
              "Fork on-click must dispatch the correct fork-id (not nil or wrong branch)"))))))


(deftest test-fork-list-active-fork-has-no-click-handler
  (testing "The currently active fork entry should NOT have an on-click handler"
    (let [[db _fork-id] (make-db-with-fork)]
      ;; db is already on the fork branch
      (reset! rf-db/app-db db)
      (let [hiccup (views/fork-list-data)
            fork-nodes (find-all-in-hiccup
                         hiccup
                         (fn [n]
                           (and (vector? n)
                                (string? (node-text n))
                                (not= "main" (node-text n))
                                (not= "Branches" (node-text n)))))]
        (is (= 1 (count fork-nodes)) "Should find exactly one fork entry")
        (is (nil? (node-on-click (first fork-nodes)))
            "Active fork entry should NOT have an on-click handler")))))
