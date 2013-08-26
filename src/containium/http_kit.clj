;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.http-kit
  (:require [org.httpkit.server :refer (run-server)]
            [boxure.core :as boxure]))


;;; Ring app registry.

(defrecord App [box handler-fn ring-conf])

(def apps (atom {}))

(def ^:private app nil)


;;; Overall ring handler creation.

(defn- sort-apps
  [apps]
  (let [non-deterministic (remove #(or (-> % :ring-conf :context-path)
                                       (-> % :ring-conf :host-regex))
                                   apps)]
    (when (< 1 (count non-deterministic))
      (println (str "Warning: multiple web apps registered not "
                    "having a context-path nor a host-regex ("
                    (apply str (interpose ", " (map (comp :name :box) non-deterministic)))
                    ")."))))
  (->> apps
       (sort-by #(count (filter (partial = \/)
                                (-> % :ring-conf :context-path))))
       reverse))


(defn- request-matches
  [{:keys [host-regex context-path] :as ring-conf} request]
  (and (or (not host-regex)
           (re-matches host-regex))
       (or (not context-path)
           (.startsWith (:uri request) context-path))))


(defn- make-app
  []
  (let [handler (if-let [apps (seq (vals @apps))]
                  (let [sorted (sort-apps apps)
                        fn-form `(fn [~'request]
                                   (condp request-matches ~'request
                                     ~@(for [app sorted
                                             form [(:ring-conf app)
                                                   (list 'boxure/call-in-box
                                                         (:box app)
                                                         (:handler-fn app)
                                                         'request)]]
                                         form)))]
                    (eval fn-form))
                  (constantly {:status 503 :body "no apps loaded"}))]
    (alter-var-root #'app (constantly handler))))


;;; Ring app registration.

(defn upstart-box
  [{:keys [name project] :as box}]
  (let [ring-conf (-> project :boxure :ring)
        handler-fn (boxure/eval box (:handler-sym ring-conf))]
    (swap! apps assoc name (App. box handler-fn ring-conf))
    (make-app)))


(defn remove-box
  [{name :name}]
  (swap! apps dissoc name)
  (make-app))


;;; Ring server management.

(defn start
  [config]
  (println "Starting HTTP Kit using config:" (:http-kit config))
  (make-app)
  (let [stop-fn (run-server #'app (:http-kit config))]
    (println "HTTP Kit started.")
    stop-fn))


(defn stop
  [stop-fn]
  (println "Stopping HTTP Kit...")
  (stop-fn)
  (println "HTTP Kit stopped."))
