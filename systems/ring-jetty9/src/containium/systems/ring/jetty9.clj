;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring.jetty9
  "The Jetty 9 implementation of the Ring system."
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.ring :refer (Ring box->ring-app make-app)]
            [ring.adapter.jetty9 :as jetty9]))


;;; Normal implementation.

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


;;; Test implementation.

(defrecord TestJetty9 [server]
  Ring
  (upstart-box [_ _ _]
    (Exception. "Cannot be used on a test Jetty 9 implementation."))
  (remove-box [_ _]
    (Exception. "Cannot be used on a test Jetty 9 implementation."))

  Stoppable
  (stop [_]
    (println "Stopping test Jetty 9 server...")
    (.stop server)
    (println "Stopped test Jetty 9 server.")))


(defn test-jetty9
  "Create a simple Jetty 9 server, serving the specified ring handler
  function. This function returns a Startable, requiring a Config
  system to be available when started."
  [handler]
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :jetty9)
            _ (println "Starting test Jetty 9 server, using config" config)
            server (jetty9/run-jetty handler config)]
        (println "Test Jetty 9 server started.")
        (TestJetty9. server)))))
