;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.core
  (:require [containium.systems :refer (with-systems)]
            [containium.systems.cassandra.embedded12 :as cassandra]
            [containium.systems.cassandra.alia1 :as alia1]
            [containium.systems.elasticsearch :as elastic]
            [containium.systems.kafka :as kafka]
            [containium.systems.ring :as ring]
            [containium.systems.ring-session-cassandra :as cass-session]
            [containium.deployer :as deployer]
            [containium.deployer.socket :as socket]
            [containium.systems.config :as config]
            [containium.modules :as modules]
            [containium.systems.repl :as repl]
            [clojure.java.io :refer (resource as-file)]
            [clojure.string :refer (split trim)]
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.pprint :refer (pprint print-table)]
            [clojure.core.async :as async :refer (<!)])
  (:import [jline.console ConsoleReader]
           [java.util Timer TimerTask])
  (:gen-class))


;;; Globals for REPL access. A necessary evil.

(defonce systems nil)

(defmacro eval-in
  "Evaluate the given `forms` (in an implicit do) in the module identified by `name`."
  [name & forms]
  `@(boxure.core/eval (:box @(get @(get-in systems [:modules :agents]) ~name)) '(do ~@forms)))


;;; Command loop.

(defn- console-channel
  [name]
  (let [channel (async/chan)]
    (async/go-loop []
      (when-let [msg (<! channel)]
        (println (str "[" name "]") msg)
        (recur)))
    channel))


(defmulti handle-command
  "This multi-method dispatches on the command argument. It also
  receives the command arguments and the systems map. The result of this command is ignored."
  (fn [command args systems] command))


(defmethod handle-command :default
  [command _ _]
  (println "Unknown command:" command)
  (println "Type 'help' for info on the available commands."))


(defmethod handle-command "help"
  [_ _ _]
  (println (str "Available commands are:"
                "\n"
                "\n module <list|describe|activate|deactivate|kill> [name [path]]"
                "\n   Prints a list of installed modules, describes what is known of a module by"
                "\n   name, activates (deploy/redeploy/swap) a module by name and path, or "
                "\n   deactivates (undeploy) a module by name. Paths can point to a directory or"
                "\n   to a JAR file. Killing a module is also possible, which forces the"
                "\n   module to a halt, whatever its state."
                "\n"
                "\n repl <start|stop> [port]"
                "\n   Starts an nREPL at the specified port, or stops the current one, inside"
                "\n   the containium."
                "\n"
                "\n shutdown"
                "\n   Stops all boxes and systems gracefully."
                "\n"
                "\n threads"
                "\n   Prints a list of all threads.")))


(defmethod handle-command "repl"
  [_ args systems]
  (let [[action port-str] args]
    (case action
      "start" (if port-str
                (if-let [port (try (Integer/parseInt port-str) (catch Exception ex))]
                  (repl/open-repl (:repl systems) port)
                  (println "Invalid port number:" port-str))
                (repl/open-repl (:repl systems)))
      "stop" (repl/close-repl (:repl systems))
      (println (str "Unknown action '" action "', please use 'start' or 'stop'.")))))


(defmethod handle-command "threads"
  [_ _ _]
  (let [threads (keys (Thread/getAllStackTraces))]
    (println (apply str "Current threads (" (count threads) "):\n  "
                    (interpose "\n  " threads)))))


(defmethod handle-command "module"
  [_ args systems]
  (let [[action name path] args
        timeout (* 1000 60)]
    (case action
      "list" (print-table (map #(select-keys % [:name :status])
                               (modules/list-modules (:modules systems))))

      "describe" (if name
                   (if-let [data (first (filter #(= (:name %) name)
                                                (modules/list-modules (:modules systems))))]
                     (print-table (reduce (fn [s [k v]] (conj s {:key k :value v})) nil data))
                     (println "Module" name "unknown."))
                   (println "Missing name argument."))

      "activate" (if name
                   (modules/activate! (:modules systems) name
                                      (when path (modules/module-descriptor (as-file path)))
                                      (console-channel name))
                   (println "Missing name argument."))
      "deactivate" (if name
                     (modules/deactivate! (:modules systems) name (console-channel name))
                     (println "Missing name argument."))
      "kill" (if name
               (modules/kill! (:modules systems) name (console-channel name))
               (println "Missing name argument."))
      (if action
        (println (str "Unknown action '" action "', see help."))
        (println "Missing action argument, see help.")))))



(defn command-loop
  "This functions starts the command loop. It uses the handle-command
  multi-method for handling the individual commands (except shutdown).
  See the documentation on the handle-command for more info on this.
  When this function returns (of which its value is of no value, pun
  intended), the `shutdown` command has been issued."
  [systems]
  (let [jline (ConsoleReader.)]
    (loop []
      (let [[command & args] (map (fn [[normal quoted]] (or quoted normal)) (re-seq #"'([^']+)'|[^\s]+" (trim (.readLine jline "containium> "))))]
        (case command
          nil (recur)
          "shutdown" nil
          (do (try
                (handle-command command args systems)
                (catch Throwable t
                  (println "Error handling command" command ":")
                  (.printStackTrace t)))
              (recur)))))))


;;; Thread debug on shutdown.

(defn shutdown-timer
  "Start a timer that shows debug information, iff the JVM has not
  shutdown yet and `wait` seconds have passed."
  [wait]
  (let [timer (Timer. "shutdown-timer" true)
        task (proxy [TimerTask] []
               (run []
                 (let [threads (keys (Thread/getAllStackTraces))]
                   (println (apply str "Threads still running (" (count threads) "):\n  "
                                   (interpose "\n  " threads))))
                 (println "Killing containium.")
                 (System/exit 1)))]
    (.schedule timer task (int (* wait 1000)))))

;;; Daemon control

(def ^java.util.concurrent.CountDownLatch daemon-latch (java.util.concurrent.CountDownLatch. 1))
(defn shutdown []
  (println "Received kill.")
  (.countDown daemon-latch)
  (Thread/sleep 1337))

;;; The coordinating functions.

(defn run
  "This function is used for the with-systems function. It is called
  when all root systems are up and running. Currently it starts the
  boxes, enters the command loop, and stops the boxes when the command
  loop exited."
  [sys]
  (alter-var-root #'systems (constantly sys))
  ;; (deployer/bootstrap-modules (:fs sys))
  (command-loop sys))

(defn run-daemon
  "Same as 'run but without the command-loop"
  [sys]
  (.addShutdownHook (java.lang.Runtime/getRuntime) (Thread. ^Runnable shutdown))
  (println "Waiting for the kill.")
  (alter-var-root #'systems (constantly sys))
  ;; (deployer/bootstrap-modules (:fs sys))
  (.await daemon-latch))


(defn -main
  "Launches containium process.

  When run with no arguments: interactive console is started.
  Any other argument will activate daemon mode."
  [& [daemon? args]]
  (with-systems systems [:config (config/file-config (as-file (resource "spec.clj")))
                         :cassandra cassandra/embedded12
                         :alia (alia1/alia1 :alia)
                         :elastic elastic/embedded
                         :kafka kafka/embedded
                         :ring ring/http-kit
                         :session-store cass-session/embedded
                         :modules modules/default-manager
                         :fs deployer/directory
                         :socket socket/socket
                         :repl repl/nrepl]
    ((if daemon? run-daemon #_else run) systems))
  (println "Shutting down...")
  (shutdown-agents)
  (shutdown-timer 10))
