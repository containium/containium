;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring
  "The interface definition of the Ring system. The Ring system offers
  an API to a ring server."
  (:require [containium.exceptions :as ex]
            [containium.systems :refer (Startable)]
            [containium.systems.ring-analytics :refer (wrap-ring-analytics)]
            [boxure.core :as boxure]
            [packthread.core :refer (+>)]))


;;; Public interface

(defprotocol Ring
  (upstart-box [this name box log-fn]
    "Add a box holding a ring application. The box's project definition
  needs to have a :ring configuration inside the :containium
  configuration. A required key is :handler, which, when evaluated
  inside the box, should be the ring handler function. Optional keys
  are :context-path and :host-regex. The first acts as a filter for
  the first element(s) in the request URI, for example \"/api/v1\".
  The second is a regular expression, which is matched against the
  server name of the request, for example \".*containium.com\".")

  (remove-box [this box log-fn]
    "Removes a box's ring handler from the ring server."))


;;; Generic logic used by Ring system implementations.

(defrecord RingApp [name box handler-fn ring-conf sort-value])


(defn- sort-apps
  "Sort the RingApps for routing. It uses the :sort-value of the apps,
  in descending order. The :sort-value is assigned in `box->ring-app`
  function."
  [apps log-fn]
  (let [non-deterministic (remove #(or (-> % :ring-conf :context-path)
                                       (-> % :ring-conf :host-regex))
                                  apps)]
    (when (< 1 (count non-deterministic))
      (log-fn (str "Warning: multiple web apps registered not "
                    "having a context-path nor a host-regex ("
                    (apply str (interpose ", " (map :name non-deterministic)))
                    ")."))))
  (let [sorted (->> apps (sort-by #(get % :sort-value)) reverse)]
    (log-fn (apply str "Sorted apps: " (interpose ", " (map :name apps))))
    sorted))


(defn- wrap-try-catch
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (ex/exit-when-fatal t)
        (println "Error handling request:" request)
        (.printStackTrace t)
        {:status 500 :body "Internal error."}))))


(defn- wrap-trim-context
  [handler context-path]
  (fn [request]
    (handler (update-in request [:uri] #(subs % (count context-path))))))


(defn- wrap-call-in-box
  [handler box]
  (fn [request]
    (boxure/call-in-box box (handler request))))


(defn- wrap-matcher
  [handler {:keys [host-regex context-path] :as ring-conf}]
  (let [host-test (when-let [pattern (when host-regex (re-pattern host-regex))]
                    (fn [request] (re-matches pattern (-> request :headers (get "host")))))
        context-test (when context-path
                       (fn [request]
                         (and (.startsWith ^String (:uri request) context-path)
                              (= (get (:uri request) (count context-path)) \/))))
        all-tests (let [tests (remove nil? [host-test context-test])]
                    (fn [request] (every? #(% request) tests)))]
    (fn [request]
      (when (all-tests request)
        (handler request)))))


(defn- wrap-log-request
  [handler app-name log-fn]
  (fn [request]
    (log-fn (str "[" app-name "]") request)
    (handler request)))


(defn make-app
  "Recreates the toplevel ring handler function, which routes the
  registered RingApps."
  [log-fn ring-analytics apps]
  (let [handler (if-let [apps (seq (vals apps))]
                  (let [sorted (vec (sort-apps apps log-fn))
                        handlers (mapv (fn [{:keys [ring-conf handler-fn box] :as app}]
                                         (+> handler-fn
                                             (wrap-call-in-box box)
                                             (->> (wrap-ring-analytics ring-analytics (:name box)))
                                             (when-let [cp (:context-path ring-conf)]
                                               (wrap-trim-context cp))
                                             (when (:print-requests ring-conf)
                                               (wrap-log-request (:name box) println))
                                             (wrap-matcher ring-conf)))
                                       sorted)]
                    (fn [request]
                      (some (fn [handler] (handler request)) handlers)))
                  (constantly {:status 503 :body "no apps loaded"}))]
    (wrap-try-catch handler)))


(defn clean-ring-conf
  "Updates the ring-conf, in order to ensure some properties of the values.
   - Makes sure the context-path does not end with a / character."
  [ring-conf]
  (let [result ring-conf
        result (update-in result [:context-path]
                          (fn [path] (let [path (if (= (first path) \/) path #_else (str "/" path))
                                           path (if (= (last path) \/) (apply str (butlast path))
                                                    #_else path)] path)))]
    result))


(defn box->ring-app
  [name {:keys [project] :as box} log-fn]
  (let [ring-conf (clean-ring-conf (-> project :containium :ring))
        _ (assert (:handler ring-conf)
                  (log-fn ":ring app config requires a :handler, but ring-conf only contains: "
                          ring-conf))
        handler-fn (boxure/eval box `(do (require '~(symbol (namespace (:handler ring-conf))))
                                          ~(:handler ring-conf)))
        ;; Sorting value is the number of slashes in the context path and the time it is
        ;; deployed (actually, the time when this function is called). The more slashes and the
        ;; earlier it's deployed, the higher the rank. Higher ranks are tried first for serving
        ;; a request.
        num-slashes (count (filter (partial = \/) (:context-path ring-conf)))
        sort-value (long (+ (* num-slashes 1e15) (- 1e15 (System/currentTimeMillis))))]
    (RingApp. name box handler-fn ring-conf sort-value)))


;;; Distribution implementation.

(defrecord DistributedRing [rings]
  Ring
  (upstart-box [_ name box log-fn]
    (doseq [ring rings]
      (upstart-box ring name box log-fn)))
  (remove-box [_ name log-fn]
    (doseq [ring rings]
      (remove-box ring name log-fn))))


(def distributed
  (reify Startable
    (start [_ systems]
      (if-let [ring-systems (seq (filter (comp (partial satisfies? Ring) val) systems))]
        (do (println "Initialising Ring distributer among servers:" (keys ring-systems))
            (DistributedRing. (vals ring-systems)))
        (throw (Exception. "No Ring systems have been started."))))))
