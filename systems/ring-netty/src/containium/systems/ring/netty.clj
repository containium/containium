;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring.netty
  "The Netty implementation of the Ring system."
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.ring :refer (Ring box->ring-app make-app)]
            [containium.systems.ring-analytics :refer (Analytics)]
            [containium.systems.logging :as logging
             :refer (SystemLogger refer-logging refer-command-logging)]
            [netty.ring.adapter :as netty]))
(refer-logging)
(refer-command-logging)


(defrecord Netty [stop-fn app apps ring-analytics logger]
  Ring
  (upstart-box [_ name box command-logger]
    (info-all command-logger "Adding module" name "to Netty handler.")
    (->> (box->ring-app name box command-logger)
         (swap! apps assoc name)
         (make-app command-logger ring-analytics)
         (reset! app)))
  (remove-box [_ name command-logger]
    (info-all command-logger "Removing module" name "from Netty handler.")
    (->> (swap! apps dissoc name)
         (make-app command-logger ring-analytics)
         (reset! app)))

  Stoppable
  (stop [_]
    (info logger "Stopping Netty server...")
    (stop-fn)
    (info logger "Stopped Netty server.")))


(def netty
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :netty)
            logger (require-system SystemLogger systems)
            _ (info logger "Starting Netty server, using config" config)
            ring-analytics (require-system Analytics systems)
            app (atom (make-app println ring-analytics {}))
            app-fn (fn [request] (@app request))
            stop-fn (netty/start-server app-fn config)]
        (info logger "Netty server started.")
        (Netty. stop-fn app (atom {}) ring-analytics logger)))))
