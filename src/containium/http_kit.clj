;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.http-kit
  (:require [org.httpkit.server :refer (run-server)]))


;;; Ring app management.

(def apps (atom {}))


(defn- make-app
  [handlers]
  (if (seq handlers)
    (fn [request] (some (fn [handler] (handler request)) handlers))
    (constantly {:status 200 :body "no apps loaded"})))


(def ^:private app (make-app nil))


(defn assoc-app
  [key handler]
  (let [apps (swap! apps assoc key handler)]
    (alter-var-root app (constantly (make-app (vals apps))))))


(defn dissoc-app
  [key]
  (let [apps (swap! apps dissoc key)]
    (alter-var-root app (constantly (make-app (vals apps))))))


;;; Ring server management.

(defn start
  [config]
  (println "Starting HTTP Kit using config:" (:http-kit config))
  (let [server (run-server #'app (:http-kit config))]
    (println "HTTP Kit started.")
    server))


(defn stop
  [stop-fn]
  (println "Stopping HTTP Kit...")
  (stop-fn)
  (println "HTTP Kit stopped."))
