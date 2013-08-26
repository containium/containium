;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.httpkit
  (:require [org.httpkit.server :refer (run-server)]))


(def app (constantly nil))


(defn start
  [config]
  (println "Starting HTTP Kit using config:" (:httpkit config))
  (let [server (run-server app (:httpkit config))]
    (println "HTTP Kit started.")
    server))


(defn stop
  [stop-fn]
  (println "Stopping HTTP Kit...")
  (stop-fn)
  (println "HTTP Kit stopped."))
