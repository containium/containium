;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.commands
  "Namespace for command handling."
  (:require [clojure.core.async :as async]
            [clojure.java.io :refer (resource as-file)]
            [clojure.pprint :refer (pprint print-table)]
            [clojure.string :refer (split trim)]
            [containium.utils.async :as async-util]
            [containium.modules :as modules]
            [containium.systems.repl :as repl]
            [containium.logging :refer (log-command)]))


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
  argument is an async channel. On this channel, command specific
  messages must be posted (possibly using the `log-command`
  function). When the command is completely finished, this channel
  must be closed. The result of this multi-method is undefined."
  (fn [command args systems commandc] command))


(defmethod handle-command :default
  [command _ _ commandc]
  (log-command commandc "Unknown command:" command)
  (log-command commandc "Type 'help' for info on the available commands.")
  (async/close! commandc))


(defmethod handle-command "help"
  [_ _ _ commandc]
  (let [txt (str "Available commands are:"
                 "\n"
                 "\n module <list|describe|activate|deactivate|kill|versions> [name [path]]"
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
                 "\n shutdown"
                 "\n   Stops all boxes and systems gracefully."
                 "\n"
                 "\n threads"
                 "\n   Prints a list of all threads.")]
    (log-command commandc txt)
    (async/close! commandc)))


(defmethod handle-command "repl"
  [_ args systems commandc]
  (let [[action port-str] args]
    (case action
      "start" (if port-str
                (if-let [port (try (Integer/parseInt port-str) (catch Exception ex))]
                  (repl/open-repl (:repl systems) port)
                  (log-command commandc "Invalid port number:" port-str))
                (repl/open-repl (:repl systems)))
      "stop" (repl/close-repl (:repl systems))
      (log-command commandc (str "Unknown action '" action "', please use 'start' or 'stop'.")))
    (async/close! commandc)))


(defmethod handle-command "threads"
  [_ _ _ commandc]
  (let [threads (keys (Thread/getAllStackTraces))]
    (log-command commandc (apply str "Current threads (" (count threads) "):\n  "
                                 (interpose "\n  " threads)))
    (async/close! commandc)))


(defmethod handle-command "module"
  [_ args systems commandc]
  (let [[action name path] args
        timeout (* 1000 60)]
    (case action
      "list" (let [installed (modules/list-installed (:modules systems))]
               (log-command commandc (with-out-str (print-table installed)))
               (async/close! commandc))

      "activate" (if name
                   (let [descriptor (when path (modules/module-descriptor (as-file path)))]
                     (modules/activate! (:modules systems) name descriptor commandc))
                   (do (log-command commandc "Missing name argument.")
                       (async/close! commandc)))

      "deactivate" (if name
                     (modules/deactivate! (:modules systems) name commandc)
                     (do (log-command commandc "Missing name argument.")
                         (async/close! commandc)))

      "kill" (if name
               (modules/kill! (:modules systems) name commandc)
               (do (log-command commandc "Missing name argument.")
                   (async/close! commandc)))

      "versions" (if name
                   (modules/versions (:modules systems) name commandc)
                   (do (log-command commandc "Missing name argument")
                       (async/close! commandc)))

      (let [msg (if action
                  (str "Unknown action '" action "', see help.")
                  "Missing action argument, see help.")]
        (log-command commandc msg)
        (async/close! commandc)))))
