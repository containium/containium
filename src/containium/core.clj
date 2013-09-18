;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.core
  (:require [containium.systems :refer (with-systems)]
            [containium.systems.cassandra :as cassandra]
            [containium.systems.elasticsearch :as elastic]
            [containium.systems.kafka :as kafka]
            [containium.systems.ring :as ring]
            [containium.systems.ring-session-cassandra :as cass-session]
            [containium.deployer :as deployer]
            [containium.systems.config :as config]
            [containium.modules :as modules]
            [containium.systems.repl :as repl]
            [clojure.java.io :refer (resource as-file)]
            [clojure.string :refer (split trim)]
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.pprint :refer (pprint)])
  (:import [jline.console ConsoleReader]
           [java.util Timer TimerTask]))


;;; Globals for REPL access. A necessary evil.

(def systems nil)


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
                "\n module <list|deploy|undeploy> [name [path]]"
                "\n   Prints a list of running modules, deploys a module by name and path, or"
                "\n   undeploys a module by name."
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
      "list" (println (modules/list-active (:modules systems))) ; Improve this
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
      (let [[command & args] (split (trim (.readLine jline "containium> ")) #"\s+")]
        (case command
          "" (recur)
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
                                   (interpose "\n  " threads))))))]
    (.schedule timer task (* wait 1000))))


;;; The coordinating functions.

(defn run
  "This function is used for the with-systems function. It is called
  when all root systems are up and running. Currently it starts the
  boxes, enters the command loop, and stops the boxes when the command
  loop exited."
  [sys]
  (alter-var-root #'systems (constantly sys))
  (command-loop sys))


(defn -main
  [& args]
  (with-systems systems [:config (config/file-config (as-file (resource "spec.clj")))
                         :cassandra cassandra/embedded12
                         :elastic elastic/embedded
                         :kafka kafka/embedded
                         :ring ring/http-kit
                         :session-store cass-session/embedded
                         :modules modules/default-manager
                         :fs deployer/directory
                         :repl repl/nrepl]
    (run systems))
  (shutdown-agents)
  (shutdown-timer 10))
