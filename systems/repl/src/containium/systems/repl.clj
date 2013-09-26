;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.repl
  "The system for starting and stopping REPLs."
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [clojure.tools.nrepl.server :as nrepl]))


;;; The public system API.

(defprotocol REPL
  (open-repl [this ] [this port]
    "Starts the REPL. If no port number is supplied, a unbound port is
  chosen at random.")

  (close-repl [this]
    "Stops the currently running REPL."))


;;; The nREPL implementation.

(defrecord NREPL [server]
  REPL
  (open-repl [_]
    (if @server
      (println "An nREPL server is already running, on port" (:port @server))
      (do (reset! server (nrepl/start-server))
          (println "nREPL server started on port" (:port @server)))))

  (open-repl [_ port]
    (if @server
      (println "An nREPL server is already running, on port" (:port @server))
      (do (reset! server (nrepl/start-server :port port))
          (println "nREPL server started on port" port))))

  (close-repl [_]
    (if @server
      (do (nrepl/stop-server @server)
          (reset! server nil)
          (println "nREPL server stopped."))
      (println "Could not close nREPL, no nREPL server currently running.")))

  Startable
  (start [this systems]
    (let [{:keys [port]} (get-config (require-system Config systems) :repl)]
      (assert (integer? port))
      (open-repl this port)
      this))

  Stoppable
  (stop [this]
    (when @server (close-repl this))))


(def nrepl (NREPL. (atom nil)))
