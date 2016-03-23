;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.reactor
  (:require [containium.deployer :as deployer :refer (Deployer)]
            [containium.systems.logging :as logging :refer (refer-logging)]
            [containium.exceptions :as ex]
            [containium.commands :as commands])
  (:import [jline.console ConsoleReader]
           [java.util Timer TimerTask]
           [java.util.concurrent CountDownLatch]))
(refer-logging)


;;; Globals for REPL access. A necessary evil.

(defonce ^:redef systems nil)



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

(def ^CountDownLatch daemon-latch (CountDownLatch. 1))
(defn shutdown []
  (println "Received kill.")
  (.countDown daemon-latch)
  (Thread/sleep 1337))

;;; The coordinating functions.

(defn bootstrap-modules
  "Calls `bootstrap-modules` on every Deployer system."
  [systems]
  (let [deployers (filter #(satisfies? Deployer %) (vals systems))
        latch (CountDownLatch. (count deployers))]
    (doseq [deployer deployers]
      (deployer/bootstrap-modules deployer latch))))

(defn run
  "This function is used for the with-systems function. It is called
  when all root systems are up and running. Currently it starts the
  boxes, enters the command loop, and stops the boxes when the command
  loop exited."
  [sys]
  (alter-var-root #'systems (constantly sys))
  (bootstrap-modules sys)
  (command-loop sys))

(defn run-daemon
  "Same as 'run but without the command-loop"
  [sys]
  (.addShutdownHook (java.lang.Runtime/getRuntime) (Thread. ^Runnable shutdown))
  (println "Waiting for the kill.")
  (alter-var-root #'systems (constantly sys))
  (bootstrap-modules sys)
  (.await daemon-latch))

