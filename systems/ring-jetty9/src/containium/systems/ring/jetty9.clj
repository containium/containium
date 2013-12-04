;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring.jetty9
  "The Jetty 9 implementation of the Ring system."
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.ring :refer (Ring box->ring-app make-app)]
            [ring.adapter.jetty9 :as jetty9]))


(defrecord Jetty9 [server app apps]
  Ring
  (upstart-box [_ name box]
    (println "Adding module" name "to Jetty9 handler.")
    (->> (box->ring-app name box)
         (swap! apps assoc name)
         (make-app)
         (reset! app)))
  (remove-box [_ name]
    (println "Removing module" name "from Jetty9 handler.")
    (->> (swap! apps dissoc name)
         (make-app)
         (reset! app)))

  Stoppable
  (stop [_]
    (println "Stopping Jetty9 server...")
    (.stop server)
    (println "Stopped Jetty9 server.")))


(def jetty9
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :jetty9)
            _ (println "Starting Jetty9 server, using config" config)
            app (atom (make-app {}))
            app-fn (fn [request] (@app request))
            server (jetty9/run-jetty app-fn config)]
        (println "Jetty9 server started.")
        (Jetty9. server app (atom {}))))))
