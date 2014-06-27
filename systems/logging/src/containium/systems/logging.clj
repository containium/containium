;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.logging
  (:require [containium.systems :refer (Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [taoensso.timbre :as timbre])
  (:import [java.io PrintStream]))


;;; Public API for apps

(defprotocol Logging
  (get-logger [this app-name]))


(defprotocol AppLogger
  (log-console [this level msg] [this level ns msg]))


(defmacro log*
  [app-logger level & vals]
  (let [ns (str *ns*)]
    `(log-console ~app-logger ~level ~ns (apply str (interpose " " [~@vals])))))


(doseq [level timbre/levels-ordered]
  (eval `(defmacro ~(symbol (name level))
           ~(str "Log the given values on the " (name level) " level.")
           [app-logger# & vals#]
           `(log* ~app-logger# ~~level ~@vals#))))


;;; Systems API for systems

(defprotocol SystemLogger
  (add-session [this out])
  (remove-session [this out])
  (get-command-logger [this out]))

(defprotocol CommandLogger
  (log-command [this level msg])
  (log-all [this level msg]))


;;; Default implementation

(defn mk-appender
  [sessions-atom prefix ^PrintStream out]
  {:timestamp-pattern "yyyy-MMM-dd HH:mm:ss ZZ"
   :appenders
   {:containium
    {:enabled? true
     :async? false
     :fn (fn [{:keys [args timestamp]}]
           (let [message (first args)
                 prefixed (if-let [ns (and (map? message) (::ns message))]
                            (str "[" (when prefix (str prefix "@")) ns "] " (::msg message))
                            (str (when prefix (str "[" prefix "] ")) message))]
             (when out
               (.println out (str timestamp " " prefixed)))
             (when sessions-atom
               (doseq [^PrintStream out @sessions-atom]
                 (.println out (str timestamp " " prefixed))))))}}})


;; sessions = (atom #{PrintStream})
(defrecord Logger [sessions console-appender]
  Logging
  (get-logger [this app-name]
    (let [appender (mk-appender sessions app-name nil)]
      (reify AppLogger
        (log-console [_ level msg]
          (timbre/log appender level msg))
        (log-console [_ level ns msg]
          (timbre/log appender level {::ns ns ::msg msg})))))

  AppLogger
  (log-console [_ level msg]
    (timbre/log console-appender level msg))
  (log-console [_ level ns msg]
    (timbre/log console-appender level {::ns ns ::msg msg}))

  SystemLogger
  (add-session [_ out]
    (swap! sessions conj out))

  (remove-session [_ out]
    (swap! sessions disj out))

  (get-command-logger [this out]
    (let [all-appender (mk-appender sessions nil out)
          command-appender (mk-appender nil nil out)]
      (reify CommandLogger
        (log-command [_ level msg]
          (timbre/log command-appender level msg))
        (log-all [_ level msg]
          (timbre/log all-appender level msg))))))


(def logger
  (reify Startable
    (start [this systems]
      (let [sessions-atom (atom #{System/out})]
        (Logger. sessions-atom (mk-appender sessions-atom nil nil))))))
