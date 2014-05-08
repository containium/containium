;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring.http-kit
  "The HTTP-Kit implementation of the Ring system."
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.ring :refer (Ring box->ring-app make-app)]
            [classlojure.core :refer (with-classloader)]
            [org.httpkit.server :as httpkit]))


;;; Patch http-kit AsyncChannel, in order to use the correct classloader.

(defn wrap-callback-context-fn
  [callback]
  (println "Wrapping http-kit callback!")
  (fn [message]
    (with-classloader (.. callback getClass getClassLoader)
      (callback message))))

(extend-type org.httpkit.server.AsyncChannel
  httpkit/Channel
  (open? [ch] (not (.isClosed ch)))
  (close [ch] (.serverClose ch 1000))
  (websocket? [ch] (.isWebSocket ch))
  (send!
    ([ch data] (.send ch data (not (httpkit/websocket? ch))))
    ([ch data close-after-send?] (.send ch data (boolean close-after-send?))))
  (on-receive [ch callback] (.setReceiveHandler ch (wrap-callback-context-fn callback)))
  (on-close [ch callback] (.setCloseHandler ch (wrap-callback-context-fn callback))))


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
