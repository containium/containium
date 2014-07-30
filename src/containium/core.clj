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
            [containium.deployer.socket :as socket]
            [containium.systems.config :as config]
            [containium.modules :as modules]
            [containium.systems.repl :as repl]
            [containium.systems.ring-analytics :as ring-analytics]
            [containium.systems.mail :as mail]
            [containium.systems.logging :as logging :refer (refer-logging)]
            [containium.exceptions :as ex]
            [containium.commands :as commands]
            [clojure.java.io :refer (resource as-file)]
            [clojure.tools.nrepl.server :as nrepl])
  (:import [jline.console ConsoleReader]
           [java.util Timer TimerTask])
  (:gen-class))
(refer-logging)


;;; Globals for REPL access. A necessary evil.

(defonce systems nil)

(defmacro eval-in
  "Evaluate the given `forms` (in an implicit do) in the module identified by `name`."
  [name & forms]

  (let [box @(get @(get-in systems [:modules :agents]) name)]
    (assert box (str "Module not found: " name))
    `(boxure.core/eval (:box @(get @(get-in systems [:modules :agents]) ~name))
                        '(try (do ~@forms)
                              (catch Throwable e# (do (.printStackTrace e#) e#))))))

(defmacro command
  "Call console commands from the REPL. For example:
  (command module versions foo)."
  [cmd & args]
  (let [cmd (str cmd)
        args (mapv str args)]
    `(print (with-out-str
              (handle-command ~cmd ~args systems
                              (logging/stdout-command-logger (:logging systems) ~cmd))))))



;;; Command loop.

(defn command-loop
  "This functions starts the command loop. It uses the handle-command
  multi-method for handling the individual commands (except shutdown).
  See the documentation on the handle-command for more info on this.
  When this function returns (of which its value is of no value, pun
  intended), the `shutdown` command has been issued."
  [{:keys [logging] :as systems}]
  (let [jline (ConsoleReader. System/in @#'containium.systems.logging/stdout)]
    (loop []
      (let [[command & args] (commands/parse-quoted (.readLine jline "containium> "))]
        (case command
          nil (recur)
          "shutdown" nil
          (do (try
                (commands/handle-command command args systems
                                         (logging/stdout-command-logger (:logging systems) command))
                (catch Throwable t
                  (ex/exit-when-fatal t)
                  (error logging "Error handling command" command ":")
                  (error logging t)))
              (recur)))))))


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
  (try (with-systems systems [:config (config/file-config (as-file (resource "spec.clj")))
                              :logging logging/logger
                              :mail mail/postal
                              :cassandra cassandra/embedded12
                              :elastic elastic/embedded
                              :kafka kafka/embedded
                              :session-store cass-session/default
                              :ring-analytics ring-analytics/elasticsearch
                              :http-kit http-kit/http-kit
                              :jetty9 jetty9/jetty9
                              :ring ring/distributed
                              :modules modules/default-manager
                              :fs deployer/directory
                              :repl repl/nrepl
                              ;; Socket needs to be the last system,
                              ;;  otherwise it doesn’t have the :repl system available.
                              :socket socket/socket]
         ((if daemon? run-daemon #_else run) systems))
       (catch Exception ex
         (.printStackTrace ex))
       (finally
         (println "Shutting down...")
         (shutdown-agents)
         (shutdown-timer 15 daemon?))))
