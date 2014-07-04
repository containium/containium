;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.logging
  "A logging system for containium systems and modules.

  The modules simply use the Logging interface, and use the level
  macros, such as `info` and `warn`, for logging on the returned
  AppLogger.

  Systems have some more options. The AppLogger interface can be used
  directly, just as a module would. But they can also add more
  LogWriter instances, called sessions, which receive all console
  logging. LogWriter is an abstraction around types that can write a
  line to some stream or channel.

  Furthermore, systems can create CommandLogger instances, which are
  used by (other) systems for logging messages to only the caller of
  the command.

  Finally, the SystemLogger interface allows for setting the log-level
  per module. This overrides the global log-level, although the timbre
  compile-time level (if set) still takes precedence."
  (:require [containium.systems :refer (Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [taoensso.timbre :as timbre]
            [clojure.string :refer (upper-case)]
            [clojure.java.io :refer (writer)])
  (:import [java.io PrintStream OutputStream]))


;;; Public API for apps

(defprotocol Logging
  (get-logger [this app-name]
    "Retrieve a logger for a module. This returns an AppLogger instance,
    which can be used to log messages to the console. The most
    convenient way of logging is calling one of the level macros, such
    as `info` or `warn`."))


(defprotocol AppLogger
  (log-console [this level msg] [this level ns msg]
    "Log the given message on the given level. Optionally one can supply
    the namespace the log statment is executed in. This is done
    automatically by the level macros, such as `info` and `warn`."))


(defmacro log*
  "Used by the level macros and calls the `log-console` function with
  the namespace the macro is compiled in. It is not recommended to
  call this directly."
  [app-logger level & vals]
  (let [ns (str *ns*)]
    `(log-console ~app-logger ~level ~ns (apply str (interpose " " [~@vals])))))


(doseq [level timbre/levels-ordered]
  (eval `(defmacro ~(symbol (name level))
           ~(str "Log the given values on the " (name level) " level.")
           [app-logger# & vals#]
           `(log* ~app-logger# ~~level ~@vals#))))


(defn refer-logging
  "Shorthand for:
  (require
    '[containium.systems.logging :as logging
      :refer (trace debug info warn error fatal report)])"
  []
  (require
   '[containium.systems.logging :as logging
     :refer (trace debug info warn error fatal report)]))


;;; Systems API for systems

(defprotocol SystemLogger
  (add-session [this log-writer]
    "Add a LogWriter to the console sessions. Make sure the underlying
    stream IO is buffered, so it does not hinder the logging. The
    following classes are already extending LogWriter: PrintStream.")

  (remove-session [this log-writer]
    "Removes a LogWriter from the console sessions.")

  (command-logger [this log-writer command] [this log-writer command done-fn]
    "Creates a CommandLogger instance, which can be used for logging
    messages to only the command invoker (i.e., to the given
    LogWriter), or both the console and command invoker. The command
    parameter is just a string, which is used for the formatting of
    the log statement. Optionally one can supply a function that is
    called whenever the command is done.")

  (set-level [this app-name level]
    "Overrides the currently set log-level of timbre for a logger
    retrieved using the Logging protocol. If the level argument is
    nil, the override is removed."))


(defprotocol CommandLogger
  (log-command [this level msg] [this level ns msg]
    "Log a message to the caller of the command.")

  (log-all [this level msg] [this level ns msg]
    "Log a message to both the caller of the command and the console
    sessions.")

  (done [this]
    "Call this function to indicate the command is done. This will
    trigger the callback set by the creator of the CommandLogger."))


;;---TODO Add `write-exception` to this? In order to handle exceptions in a
;;        special way, such as squash.io?
(defprotocol LogWriter
  (write-line [this line]))


(defmacro command-log*
  "Used by the system level macros and calls the `log-command` or
  `log-all` function with the namespace the macro is compiled in. It
  is not recommended to call this directly."
  [app-logger level fn & vals]
  (let [ns (str *ns*)
        fn (symbol "containium.systems.logging" (str "log-" fn))]
    `(~fn ~app-logger ~level ~ns (apply str (interpose " " [~@vals])))))


(doseq [level timbre/levels-ordered
        fn ["all" "command"]]
  (eval `(defmacro ~(symbol (str (name level) "-" fn))
           ~(str "Log the given values on the " (name level) " level to " fn)
           [app-logger# & vals#]
           `(command-log* ~app-logger# ~~level ~~fn ~@vals#))))

(defn refer-command-logging
  "Shorthand for:
  (require
    '[containium.systems.logging :as logging
      :refer (trace-all debug-all info-all warn-all error-all fatal-all report-all
              trace-command debug-command info-command warn-command error-command
              fatal-command report-command)])"
  []
  (require
   '[containium.systems.logging :as logging
     :refer (trace-all debug-all info-all warn-all error-all fatal-all report-all
                       trace-command debug-command info-command warn-command error-command
                       fatal-command report-command)]))


;;; Default implementation

(extend-type java.io.PrintStream
  LogWriter
  (write-line [^PrintStream this line]
    (.println this line)))


(defn mk-appender
  [sessions-atom prefix log-writer]
  {:timestamp-pattern "yyyy-MM-dd HH:mm:ss,SSS"
   :appenders
   {:containium
    {:enabled? true
     :async? false
     :fn (fn [{:keys [args timestamp level]}]
           (let [message (first args)
                 level (format "%5S" (name level))
                 raw? (and (map? message) (::raw message))
                 ns (or (and (map? message) (::ns message)) nil)
                 message (or (and (map? message) (::msg message)) message)
                 text (if-not raw?
                        (as-> message t
                              (if (or ns prefix) (str "] " t) t)
                              (str ns t)
                              (if (and ns prefix) (str "@" t) t)
                              (str prefix t)
                              (if (or ns prefix) (str "[" t) t)
                              (str timestamp " " level " " t))
                        (str message))]
             (when log-writer
               (write-line log-writer text))
             (when sessions-atom
               (doseq [session-log-writer @sessions-atom]
                 (when-not (= session-log-writer log-writer)
                   (write-line session-log-writer text))))))}}})


;; sessions = (atom #{LogWriter})
;; levels = (atom {"app-name", :level})
(defrecord Logger [sessions levels console-appender]
  Logging
  (get-logger [this app-name]
    (let [appender (mk-appender sessions app-name nil)]
      (reify AppLogger
        (log-console [_ level msg]
          (if-let [log-level (get @levels app-name)]
            (timbre/with-log-level log-level (timbre/log appender level msg))
            (timbre/log appender level msg)))
        (log-console [_ level ns msg]
          (if-let [log-level (get @levels app-name)]
            (timbre/with-log-level log-level (timbre/log appender level {::ns ns ::msg msg}))
            (timbre/log appender level {::ns ns ::msg msg}))))))

  AppLogger
  (log-console [_ level msg]
    (timbre/log console-appender level msg))
  (log-console [_ level ns msg]
    (timbre/log console-appender level {::ns ns ::msg msg}))

  SystemLogger
  (add-session [_ log-writer]
    (swap! sessions conj log-writer))

  (remove-session [_ log-writer]
    (swap! sessions disj log-writer))

  (command-logger [_ log-writer command done-fn]
    (let [all-appender (mk-appender sessions command log-writer)
          command-appender (mk-appender nil command log-writer)]
      (reify CommandLogger
        (log-command [_ level msg]
          (timbre/log command-appender level msg))
        (log-command [_ level ns msg]
          (timbre/log command-appender level {::ns ns ::msg msg}))
        (log-all [_ level msg]
          (timbre/log all-appender level msg))
        (log-all [_ level ns msg]
          (timbre/log all-appender level {::ns ns ::msg msg}))
        (done [_]
          (when done-fn (done-fn))))))
  (command-logger [this log-writer command]
    (command-logger this log-writer command nil))

  (set-level [this app-name level]
    (swap! levels assoc app-name level)))


(def ^:private stdout (System/out))
(def ^:private linesep (System/getProperty "line.separator"))

(defn- bytes->string
  [^bytes bytes off len]
  (let [s (String. bytes off len)]
    (if (.endsWith s linesep)
      (subs s 0 (- (count s) (count linesep)))
      s)))


(defn- override-std
  "Override standard out. The type argument can be one of:

    :system - overrides the System/out and System/err streams

    :core - overrides the clojure.core/*out* and *err* bindings

    :both - both :system and :core."
  [logger type]
  (let [out (proxy [OutputStream] []
              (write [b off len]
                (let [s (bytes->string b off len)]
                  (when (seq s)
                    (log-console logger :info {::raw true ::msg (str "STDOUT: " s)})))))
        err (proxy [OutputStream] []
              (write [b off len]
                (let [s (bytes->string b off len)]
                  (when (seq s)
                    (log-console logger :error {::raw true ::msg (str "STDERR: " s)})))))]
    (when (or (= type :both) (= type :system))
      (System/setOut (PrintStream. out))
      (System/setErr (PrintStream. err)))
    (when (or (= type :both) (= type :core))
      (alter-var-root #'clojure.core/*out* (constantly (writer out)))
      (alter-var-root #'clojure.core/*err* (constantly (writer err))))))


(def logger
  (reify Startable
    (start [this systems]
      (let [sessions-atom (atom #{stdout})
            logger (Logger. sessions-atom (atom {}) (mk-appender sessions-atom nil nil))]
        (override-std logger :both)
        logger))))


(defn stdout-command-logger
  "Create a CommandLogger that logs to standard out."
  ([logger command]
     (command-logger logger stdout command))
  ([logger command done-fn]
     (command-logger logger stdout command done-fn)))


(defmacro with-log-level
  "Override the log-level for the statements within the body. This is
  only useful for systems, not for apps (as they have there own
  timbre, or not)."
  [level & body]
  `(timbre/with-log-level ~level ~@body))
