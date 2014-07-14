;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.commands
  "Namespace for command handling."
  (:require [clojure.java.io :refer (resource as-file)]
            [clojure.pprint :refer (pprint print-table)]
            [clojure.string :refer (split trim)]
            [containium.modules :as modules]
            [containium.systems.repl :as repl]
            [containium.systems.logging :as logging :refer (refer-logging refer-command-logging)]))
(refer-logging)
(refer-command-logging)


;;; Helper functions.

(defn parse-quoted
  "Parses quoted parts in a command string. Returns [command & args]"
  [string]
  (map (fn [[normal quoted]] (or quoted normal))
       (re-seq #"'([^']+)'|[^\s]+" (trim string))))


;;; Actual commands.

(defmulti handle-command
  "This multi-method dispatches on the command argument. It also
  receives the command arguments and the systems map. The last
  argument is a CommandLogger from the logging system."
  (fn [command args systems command-logger] command))


(defmethod handle-command :default
  [command _ _ command-logger]
  (error-command command-logger "Unknown command:" command)
  (info-command command-logger "Type 'help' for info on the available commands.")
  (logging/done command-logger))


(defmethod handle-command "help"
  [_ _ _ command-logger]
  (let [txt (str "Available commands are:"
                 "\n"
                 "\n module <list|activate|deactivate|kill|versions> [name [path]]"
                 "\n   Prints a list of installed modules, describes what is known of a module by"
                 "\n   name, activates (deploy/redeploy/swap) a module by name and path, or "
                 "\n   deactivates (undeploy) a module by name. Paths can point to a directory or"
                 "\n   to a JAR file. Killing a module is also possible, which forces the"
                 "\n   module to a halt, whatever its state. The versions actions shows the"
                 "\n   *-Version MANIFEST data in the classpath of the module."
                 "\n"
                 "\n repl <start|stop> [port]"
                 "\n   Starts an nREPL at the specified port, or stops the current one, inside"
                 "\n   the containium."
                 "\n"
                 "\n logging [module] [level]"
                 "\n   Overrides the log-level for the given module, or resets it to the global"
                 "\n   level. Valid levels are trace, debug, info, warn, error, fatal, report and"
                 "\n   reset."
                 "\n"
                 "\n shutdown"
                 "\n   Stops all boxes and systems gracefully."
                 "\n"
                 "\n threads"
                 "\n   Prints a list of all threads.")]
    (logging/with-log-level :info
      (info-command command-logger txt))
    (logging/done command-logger)))


(defmethod handle-command "repl"
  [_ args systems command-logger]
  (let [[action port-str] args]
    (case action
      "start" (if port-str
                (if-let [port (try (Integer/parseInt port-str) (catch Exception ex))]
                  (repl/open-repl (:repl systems) command-logger port)
                  (error-command command-logger "Invalid port number:" port-str))
                (repl/open-repl (:repl systems) command-logger))
      "stop" (repl/close-repl (:repl systems) command-logger)
      (error-command command-logger "Unknown action" action "- please use 'start' or 'stop'."))
    (logging/done command-logger)))


(defmethod handle-command "threads"
  [_ _ _ command-logger]
  (let [threads (keys (Thread/getAllStackTraces))]
    (logging/with-log-level :info
      (info-command command-logger (apply str "Current threads (" (count threads) "):\n  "
                                          (interpose "\n  " threads))))
    (logging/done command-logger)))


(defmethod handle-command "module"
  [_ args systems command-logger]
  (let [[action name path] args]
    (case action
      "list" (let [installed (modules/list-installed (:modules systems))]
               (logging/with-log-level :info
                 (info-command command-logger (with-out-str (print-table installed))))
               (logging/done command-logger))

      "activate" (if name
                   (let [descriptor (when path (modules/module-descriptor (as-file path)))]
                     (modules/activate! (:modules systems) name descriptor command-logger))
                   (do (error-command command-logger "Missing name argument.")
                       (logging/done command-logger)))

      "deactivate" (if name
                     (modules/deactivate! (:modules systems) name command-logger)
                     (do (error-command command-logger "Missing name argument.")
                         (logging/done command-logger)))

      "kill" (if name
               (modules/kill! (:modules systems) name command-logger)
               (do (error-command command-logger "Missing name argument.")
                   (logging/done command-logger)))

      "versions" (if name
                   (modules/versions (:modules systems) name command-logger)
                   (do (error-command command-logger "Missing name argument")
                       (logging/done command-logger)))

      (let [msg (if action
                  (str "Unknown action '" action "', see help.")
                  "Missing action argument, see help.")]
        (error-command command-logger msg)
        (logging/done command-logger)))))


(defmethod handle-command "logging"
  [_ args systems command-logger]
  (let [[name level] args]
    (if (and name (#{"trace" "debug" "info" "warn" "error" "fatal" "report" "reset"} level))
      (do (logging/set-level (:logging systems) name (when-not (= "reset" level) (keyword level)))
          (if (= "reset" level)
            (info-all command-logger "Logging for" name "reset to default")
            (info-all command-logger "Logging for" name "set to" level)))
      (error-command command-logger "Missing name, missing level, or invalid level.")))
  (logging/done command-logger))


;;; Shortcuts

(defmethod handle-command "ma"
  [_ args systems command-logger]
  (handle-command "module" (cons "activate" args) systems command-logger))

(defmethod handle-command "ml"
  [_ args systems command-logger]
  (handle-command "module" (cons "list" args) systems command-logger))
