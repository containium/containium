;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.repl
  "The system for starting and stopping REPLs."
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.logging :as logging
             :refer (SystemLogger refer-logging refer-command-logging)]
            [clojure.tools.nrepl.server :as nrepl]))
(refer-logging)
(refer-command-logging)


;;; The public system API.

(defprotocol REPL
  (open-repl [this command-logger] [this command-logger port]
    "Starts the REPL. If no port number is supplied, an unbound port is
  chosen at random.")

  (close-repl [this comamnd-logger]
    "Stops the currently running REPL."))


;;; The nREPL implementation.

(defrecord NREPL [server logger]
  REPL
  (open-repl [_ command-logger]
    (if @server
      (error-command command-logger "An nREPL server is already running, on port" (:port @server))
      (do (reset! server (nrepl/start-server))
          (info-all command-logger "nREPL server started on port" (:port @server)))))

  (open-repl [_ command-logger port]
    (if @server
      (error-command command-logger "An nREPL server is already running, on port" (:port @server))
      (do (reset! server (nrepl/start-server :port port))
          (info-all command-logger "nREPL server started on port" port))))

  (close-repl [_ command-logger]
    (if @server
      (do (nrepl/stop-server @server)
          (reset! server nil)
          (info-all command-logger "nREPL server stopped."))
      (error-command command-logger "Could not close nREPL, no nREPL server currently running.")))

  Startable
  (start [this systems]
    (let [{:keys [port]} (get-config (require-system Config systems) :repl)
          logger (require-system SystemLogger systems)]
      (info logger "Starting nREPL system...")
      (assert (integer? port))
      (open-repl this (logging/stdout-command-logger logger nil) port)
      (assoc this :logger logger)))

  Stoppable
  (stop [this]
    (when @server (close-repl this (logging/stdout-command-logger logger nil)))))


(def nrepl (NREPL. (atom nil) nil))
