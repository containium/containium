;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.core
  (:require [containium.systems :refer (with-systems)]
            [containium.systems.cassandra.embedded12 :as cassandra]
            [containium.systems.elasticsearch :as elastic]
            [containium.systems.kafka :as kafka]
            [containium.systems.ring :as ring]
            [containium.systems.ring.http-kit :as http-kit]
            [containium.systems.ring.jetty9 :as jetty9]
            [containium.systems.ring-session-cassandra :as cass-session]
            [containium.deployer :as deployer]
            [containium.systems.config :as config]
            [containium.modules :as modules]
            [containium.systems.repl :as repl]
            [containium.exceptions :as ex]
            [clojure.java.io :refer (resource as-file)]
            [clojure.string :refer (split trim)]
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.pprint :refer (pprint print-table)])
  (:import [jline.console ConsoleReader]
           [java.util Timer TimerTask])
  (:gen-class))


;;; Globals for REPL access. A necessary evil.

(defonce systems nil)

(defmacro eval-in
  "Evaluate the given `forms` (in an implicit do) in the module identified by `name`."
  [name & forms]

  (let [box @(get @(get-in systems [:modules :agents]) name)]
    (assert box (str "Module not found: " name))
    `@(boxure.core/eval (:box @(get @(get-in systems [:modules :agents]) ~name))
                        '(try (do ~@forms)
                              (catch Throwable e# (do (.printStackTrace e#) e#))))))


;;; Command loop.

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
                "\n module <list|deploy|undeploy|redeploy|kill|versions> [name [path]]"
                "\n   Prints a list of installed modules, deploys a module by name and path, or"
                "\n   undeploys/redeploys a module by name. Paths can point to a directory or"
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
      "list" (print-table (modules/list-installed (:modules systems)))

      "deploy" (if (and name path)
                 (future (println (:message (deref (modules/deploy! (:modules systems) name
                                                                    (as-file path))
                                                   timeout
                                                   {:message (str "Deployment of " name
                                                                  " timed out.")}))))
                 (println "Missing name and/or path argument."))

      "undeploy" (if name
                   (future (println (:message (deref (modules/undeploy! (:modules systems) name)
                                                     timeout
                                                     {:message (str "Undeployment of " name
                                                                    " timed out.")}))))
                   (println "Missing name argument."))

      "redeploy" (if name
                   (future (println (:message (deref (modules/redeploy! (:modules systems) name)
                                                     timeout
                                                     {:message (str "Redeployment of " name
                                                                    " timed out.")}))))
                   (println "Missing name argument."))

      "kill" (if name
               (future (println (:message (deref (modules/kill! (:modules systems) name)
                                                 timeout
                                                 {:message (str "Killing of " name
                                                                " timed out.")}))))
               (println "Missing name argument."))

      "versions" (if name
                   (modules/versions (:modules systems) name)
                   (println "Missing name argument"))

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
      (let [[command & args] (map (fn [[normal quoted]] (or quoted normal))
                                  (re-seq #"'([^']+)'|[^\s]+"
                                          (trim (.readLine jline "containium> "))))]
        (case command
          nil (recur)
          "shutdown" nil
          (do (try
                (handle-command command args systems)
                (catch Throwable t
                  (ex/exit-when-fatal t)
                  (println "Error handling command" command ":")
                  (.printStackTrace t)))
              (recur)))))))


(defmacro command [cmd & args]
  "Call console commands from the REPL. For example:
  (command module versions foo)."
  (let [cmd (str cmd)
        args (mapv str args)]
    `(print (with-out-str (handle-command ~cmd ~args systems)))))


;;; Thread debug on shutdown.

(defn shutdown-timer
  "Start a timer that shows debug information, iff the JVM has not
  shutdown yet and `wait` seconds have passed. If the `kill?` argument
  is set to true, containium will be force-terminated as well."
  [wait kill?]
  (let [timer (Timer. "shutdown-timer" true)
        task (proxy [TimerTask] []
               (run []
                 (let [threads (keys (Thread/getAllStackTraces))]
                   (println (apply str "Threads still running (" (count threads) "):\n  "
                                   (interpose "\n  " threads))))
                 (when kill? (System/exit 1))))]
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
  (deployer/bootstrap-modules (:fs sys))
  (command-loop sys))

(defn run-daemon
  "Same as 'run but without the command-loop"
  [sys]
  (.addShutdownHook (java.lang.Runtime/getRuntime) (Thread. ^Runnable shutdown))
  (println "Waiting for the kill.")
  (alter-var-root #'systems (constantly sys))
  (deployer/bootstrap-modules (:fs sys))
  (.await daemon-latch))


(defn -main
  "Launches containium process.

  When run with no arguments: interactive console is started.
  Any other argument will activate daemon mode."
  [& [daemon? args]]
  (ex/register-default-handler)
  (with-systems systems [:config (config/file-config (as-file (resource "spec.clj")))
                         :cassandra cassandra/embedded12
                         :elastic elastic/embedded
                         :kafka kafka/embedded
                         :http-kit http-kit/http-kit
                         :jetty9 jetty9/jetty9
                         :ring ring/distributed
                         :session-store cass-session/embedded
                         :modules modules/default-manager
                         :fs deployer/directory
                         :repl repl/nrepl]
    ((if daemon? run-daemon #_else run) systems))
  (println "Shutting down...")
  (shutdown-agents)
  (shutdown-timer 15 daemon?))
