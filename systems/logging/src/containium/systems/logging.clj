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
  the command. For CommandLoggers are macros such as `info-all` and
  `error-command` available.

  Note that when supplying a Throwable as the message (or as the first
  value to the mentioned macros), it will be logged as a stacktrace.
  The Throwable may also be registered in another way, such as a
  monitoring application, whenever a session also implements the
  ExceptionWriter protocol.

  Finally, the SystemLogger interface allows for setting the log-level
  per module. This overrides the global log-level, although the timbre
  compile-time level (if set) still takes precedence. Throwables are
  always logged at the :error level."
  (:require [containium.systems :refer (Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [taoensso.timbre :as timbre]
            [clojure.string :refer (upper-case)]
            [clojure.java.io :refer (writer)]
            [clojure.stacktrace :refer (print-cause-trace)]
            [clansi.core :refer (style)])
  (:import [java.io PrintStream OutputStream]))


;;; Public API for apps

(defprotocol Logging
  (get-logger [this app-name]
    "Retrieve a logger for a module. This returns an AppLogger instance,
    which can be used to log messages to the console. The most
    convenient way of logging is calling one of the level macros, such
    as `info` or `warn`."))


(defprotocol AppLogger
  (log-console [this level msg-or-throwable] [this level ns msg-or-throwable]
    "Log the given message on the given level. When supplying a
    Throwable as the message, it will be logged as a stacktrace. The
    Throwable may also be registered in another way, such as a
    monitoring application. Optionally one can supply the namespace
    the log statment is executed in. This is done automatically by the
    level macros, such as `info` and `warn`."))


(defmacro log*
  "Used by the level macros and calls the `log-console` function with
  the namespace the macro is compiled in. It is not recommended to
  call this directly."
  [app-logger level & vals]
  (let [ns (str *ns*)]
    `(let [vals# [~@vals]]
       (if (instance? Throwable (first vals#))
         (timbre/with-log-level :error (log-console ~app-logger ~level ~ns (first vals#)))
         (log-console ~app-logger ~level ~ns (apply str (interpose " " vals#)))))))


(doseq [level timbre/levels-ordered]
  (eval `(defmacro ~(symbol (name level))
           ~(str "Log the given values on the " (name level) " level." \newline
                 "  If the first value is a Throwable, then only the stacktrace" \newline
                 "  of that value is written. That Throwable may also be registered" \newline
                 "  in another way, such as a monitoring application.")
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


(defn overtake-logging
  "This is useful for apps which already use another logging
  framework. This function tries to let those logging frameworks use
  the supplied AppLogger instance.

  Currently it tries to override:
    - timbre, by setting the config."
  [app-logger]
  (try (require 'taoensso.timbre)
       ;; Make sure all logging statements arrive in the AppLogger, which will check whether it
       ;; should be logged itself.
       (taoensso.timbre/set-config! :current-level :trace)
       (taoensso.timbre/set-config!
        :appenders
        {:overtaken
         {:enabled? true
          :async? false
          :fn (fn [{:keys [args level throwable ns]}]
                (log-console app-logger level ns (or throwable
                                                     (apply str (interpose " " args)))))}})
       (info app-logger "Timbre logging is configured to use the supplied containiums's AppLogger.")
       (catch Exception ex
         (debug app-logger "Could not configure Timbre logging to use AppLogger:" ex))))


;;; Systems API for systems

(defprotocol SystemLogger
  (add-session [this log-writer]
    "Add a LogWriter and/or ExceptionWriter to the console sessions.
    Make sure the underlying IO is buffered or executed on another
    thread, so it does not hinder the logging.

    The following classes are already extending LogWriter by default:
     - PrintStream")

  (remove-session [this log-writer]
    "Removes a LogWriter/ExceptionWriter from the console sessions.")

  (command-logger [this log-writer command] [this log-writer command done-fn]
    "Creates a CommandLogger instance, which can be used for logging
    messages to only the command invoker (i.e., to the given
    LogWriter), or both the console and command invoker. The command
    parameter is just a string, which is used for the formatting of
    the log statement. Optionally one can supply a function that is
    called whenever the command is done. The most convenient way of
    using the CommandLogger is to use the log macros like `info-all`
    and `error-command`.")

  (set-level [this app-name level]
    "Overrides the currently set log-level of timbre for a logger
    retrieved using the Logging protocol. If the level argument is
    nil, the override is removed."))


(defprotocol CommandLogger
  (log-command [this level msg] [this level ns msg-or-throwable]
    "Log a message to the caller of the command.")

  (log-all [this level msg] [this level ns msg-or-throwable]
    "Log a message to both the caller of the command and the console
    sessions.")

  (done [this]
    "Call this function to indicate the command is done. This will
    trigger the callback set by the creator of the CommandLogger."))


(defprotocol LogWriter
  (write-line [this line]
    "Write the String to the output."))

(defprotocol ExceptionWriter
  (write-exception [this throwable prefix ns]
    "Write the Throwable to the output, which was logged in the given
    prefix (application name or command name) and the given ns. Both
    may be nil."))


(defmacro command-log*
  "Used by the system level macros and calls the `log-command` or
  `log-all` function with the namespace the macro is compiled in. It
  is not recommended to call this directly."
  [app-logger level fn & vals]
  (let [ns (str *ns*)
        fn (symbol "containium.systems.logging" (str "log-" fn))]
    `(let [vals# [~@vals]]
       (if (instance? Throwable (first vals#))
         (timbre/with-log-level :error (~fn ~app-logger ~level ~ns (first vals#)))
         (~fn ~app-logger ~level ~ns (apply str (interpose " " vals#)))))))


(doseq [level timbre/levels-ordered
        fn ["all" "command"]]
  (eval `(defmacro ~(symbol (str (name level) "-" fn))
           ~(str "Log the given values on the " (name level) " level to " fn "." \newline
                 "  If the first value is a Throwable, then only the stacktrace" \newline
                 "  of that value is written. That Throwable may also be registered" \newline
                 "  in another way, such as a monitoring application.")
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


(def colors [:white :red :green :blue :yellow :magenta :cyan])
(def levels {:trace [:white]
             :debug [:blue]
             :info [:cyan]
             :warn [:yellow]
             :error [:red :underline]
             :fatal [:magenta :inverse]})


;;--- TODO Make ansi optional?
(defn mk-appender
  [sessions-atom ^String prefix log-writer]
  {:timestamp-pattern "yyyy-MM-dd HH:mm:ss,SSS"
   :appenders
   {:containium
    {:enabled? true
     :async? false
     :fn (fn [{:keys [args timestamp level throwable]}]
           (let [message (or throwable (first args))
                 level (apply style (format "%5S" (name level)) (get levels level))
                 raw? (and (map? message) (::raw message))
                 ^String ns (or (and (map? message) (::ns message)) nil)
                 message (or (and (map? message) (::msg message)) message)
                 throwable? (instance? Throwable message)
                 text (if-not raw?
                        (as-> message t
                              (if throwable? (with-out-str (print-cause-trace t)) t)
                              (if (or ns prefix) (str "] " t) t)
                              (if ns
                                (str (style ns (nth colors (mod (.hashCode ns)
                                                                (count colors)))) t)
                                t)
                              (if (and ns prefix) (str "@" t) t)
                              (if prefix
                                (str (style prefix (nth colors (mod (.hashCode prefix)
                                                                    (count colors)))) t)
                                t)
                              (if (or ns prefix) (str "[" t) t)
                              (str timestamp " " level " " t))
                        (str message))]
             (when log-writer
               (when (satisfies? LogWriter log-writer)
                 (write-line log-writer text))
               (when (and throwable? (satisfies? ExceptionWriter log-writer))
                 (write-exception log-writer message prefix ns)))
             (when sessions-atom
               (doseq [session-log-writer @sessions-atom]
                 (when-not (= session-log-writer log-writer)
                   (when (satisfies? LogWriter session-log-writer)
                     (write-line session-log-writer text))
                   (when (and throwable? (satisfies? ExceptionWriter session-log-writer))
                     (write-exception session-log-writer message prefix ns)))))))}}})


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


;;---TODO Read logging level(s) from config
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
