;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring.netty
  "The Netty implementation of the Ring system."
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.ring :refer (Ring box->ring-app make-app)]
            [netty.ring.adapter :as netty]))


(defrecord Netty [stop-fn app apps]
  Ring
  (upstart-box [_ name box log-fn]
    (log-fn "Adding module" name "to Netty handler.")
    (->> (box->ring-app name box log-fn)
         (swap! apps assoc name)
         (make-app log-fn)
         (reset! app)))
  (remove-box [_ name log-fn]
    (println "Removing module" name "from Netty handler.")
    (->> (swap! apps dissoc name)
         (make-app log-fn)
         (reset! app)))

  Stoppable
  (stop [_]
    (println "Stopping Netty server...")
    (stop-fn)
    (println "Stopped Netty server.")))


(def netty
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :netty)
            _ (println "Starting Netty server, using config" config)
            app (atom (make-app println {}))
            app-fn (fn [request] (@app request))
            stop-fn (netty/start-server app-fn config)]
        (println "Netty server started.")
        (Netty. stop-fn app (atom {}))))))
