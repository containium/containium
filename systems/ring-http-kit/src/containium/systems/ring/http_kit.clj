;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring.http-kit
  "The HTTP-Kit implementation of the Ring system."
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.ring :refer (Ring box->ring-app make-app)]
            [org.httpkit.server :as httpkit]))


;;; Standard HTTP-Ki implementation.

(defrecord HttpKit [stop-fn app apps]
  Ring
  (upstart-box [_ name box log-fn]
    (log-fn "Adding module" name "to HTTP-kit handler.")
    (->> (box->ring-app name box log-fn)
         (swap! apps assoc name)
         (make-app log-fn)
         (reset! app)))
  (remove-box [_ name log-fn]
    (log-fn "Removing module" name "from HTTP-kit handler.")
    (->> (swap! apps dissoc name)
         (make-app log-fn)
         (reset! app)))

  Stoppable
  (stop [_]
    (println "Stopping HTTP-kit server...")
    (stop-fn)
    (println "Stopped HTTP-kit server.")))


(def http-kit
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :http-kit)
            _ (println "Starting HTTP-kit server, using config" config)
            app (atom (make-app println {}))
            app-fn (fn [request] (@app request))
            stop-fn (httpkit/run-server app-fn config)]
        (println "HTTP-Kit server started.")
        (HttpKit. stop-fn app (atom {}))))))


;;; HTTP-Kit implementation for testing.

(defrecord TestHttpKit [stop-fn]
  Ring
  (upstart-box [_ _ _ _]
    (Exception. "Cannot be used on a test HTTP-Kit implementation."))
  (remove-box [_ _ _]
    (Exception. "Cannot be used on a test HTTP-Kit implementation."))

  Stoppable
  (stop [_]
    (println "Stopping test HTTP-Kit server...")
    (stop-fn)
    (println "Stopped test HTTP-Kit server.")))


(defn test-http-kit
  "Create a simple HTTP-kit server, serving the specified ring handler
  function. This function returns a Startable, requiring a Config
  system to be available when started."
  [handler]
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :http-kit)
            _ (println "Starting test HTTP-Kit, using config" config "...")
            stop-fn (httpkit/run-server handler config)]
        (println "Started test HTTP-Kit.")
        (TestHttpKit. stop-fn)))))
