(ns fizzle.history.interceptor
  (:require
    [fizzle.history.core :as history]
    [re-frame.core :as rf]))


(def history-interceptor
  "Global re-frame interceptor that processes :history/pending-entry from event handlers.
   Event handlers set :history/pending-entry when they complete a meaningful action.
   This interceptor moves it into the history data structure, auto-forking when
   taking action from a rewound position."
  (rf/->interceptor
    :id :history/snapshot
    :after (fn [context]
             (let [db-after (get-in context [:effects :db])]
               (if-let [pending (and db-after (:history/pending-entry db-after))]
                 (let [entry (history/make-entry (:snapshot pending) (:event-type pending)
                                                 (:description pending) (:turn pending)
                                                 (:principal pending))
                       db (dissoc db-after :history/pending-entry)]
                   (assoc-in context [:effects :db]
                             (if (or (= -1 (:history/position db)) (history/at-tip? db))
                               (history/append-entry db entry)
                               (history/auto-fork db entry))))
                 context)))))


(defn register!
  "Register the history interceptor globally. Call once during app initialization."
  []
  (rf/reg-global-interceptor history-interceptor))
