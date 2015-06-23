;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring
  "The interface definition of the Ring system. The Ring system offers
  an API to a ring server."
  (:require [containium.exceptions :as ex]
            [containium.systems :refer (Startable)]
            [containium.systems.ring-analytics :refer (wrap-ring-analytics)]
            [containium.systems.logging :as logging :refer (refer-command-logging)]
            [boxure.core :as boxure]
            [packthread.core :refer (+>)]
            [clojure.stacktrace :as stack]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.cookies :refer (wrap-cookies)])
  (:import [java.util Date]))
(refer-command-logging)


;;; Public interface

(defprotocol Ring
  (upstart-box [this name box command-logger]
    "Add a box holding a ring application. The box's project definition
  needs to have a :ring configuration inside the :containium
  configuration. A required key is :handler, which, when evaluated
  inside the box, should be the ring handler function. Optional keys
  are :context-path and :host-regex. The first acts as a filter for
  the first element(s) in the request URI, for example \"/api/v1\".
  The second is a regular expression, which is matched against the
  server name of the request, for example \".*containium.com\".")

  (remove-box [this box command-logger]
    "Removes a box's ring handler from the ring server."))


;;; Generic logic used by Ring system implementations.

(defrecord RingApp [name box handler-fn ring-conf sort-value])


(def app-seq-no (atom 0))


(defn- sort-apps
  "Sort the RingApps for routing. It uses the :sort-value of the apps,
  in descending order. The :sort-value is assigned in `box->ring-app`
  function."
  [apps command-logger]
  (let [non-deterministic (remove #(or (-> % :ring-conf :context-path)
                                       (-> % :ring-conf :host-regex)
                                       (-> % :ring-conf :priority))
                                  apps)]
    (when (< 1 (count non-deterministic))
      (warn-all command-logger (str "Warning: multiple web apps registered not "
                                    "having a context-path nor a host-regex ("
                                    (apply str (interpose ", " (map :name non-deterministic)))
                                    ")."))))
  (let [sorted (->> apps (sort-by #(get % :sort-value)) reverse)]
    (debug-command command-logger (apply str "Sorted apps: " (interpose ", " (map :name apps))))
    sorted))


(defn- wrap-trim-context
  [handler context-path]
  (fn [request]
    (handler (-> request
                 (update-in [:uri] #(subs % (count context-path)))
                 (assoc :context-path context-path)))))


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
        all-tests (apply every-pred (remove nil? [host-test context-test]))]
    (fn [request]
      (when (all-tests request)
        (handler request)))))


(defn- wrap-log-request
  [handler app-name log-fn]
  (fn [request]
    (log-fn (str "[" app-name "]") request)
    (handler request)))


(defn- wrap-exceptions
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (ex/exit-when-fatal t)
        (let [now (Date.)
              msg (str "Error handling request at " now ":\n"
                       request
                       "\nCause by:\n"
                       (with-out-str (stack/print-cause-trace t)))]
          (.println (System/err) msg)
          {:status 500
           :body (str "<html><body>"
                      "<h3>Internal server error. Please contact support.</h3>"
                      "<span style=\"color: #777\">Server time: " now "</span>"
                      "</body></html>")})))))


(defn make-app
  "Recreates the toplevel ring handler function, which routes the
  registered RingApps."
  [command-logger ring-analytics apps]
  (let [handler (if-let [apps (seq (vals apps))]
                  (let [sorted (vec (sort-apps apps command-logger))
                        handlers (seq (map (fn [{:keys [ring-conf handler-fn box] :as app}]
                                             (+> handler-fn
                                                 (wrap-call-in-box box)
                                                 (->> (wrap-ring-analytics ring-analytics (:name box)))
                                                 (when-let [cp (:context-path ring-conf)]
                                                   (wrap-trim-context cp))
                                                 (when (:print-requests ring-conf)
                                                   (wrap-log-request (:name box) println))
                                                 (wrap-matcher ring-conf)))
                                           sorted))
                        all-handlers (apply some-fn handlers)
                        miss-handler (wrap-ring-analytics ring-analytics "404" (constantly {:status 404}))]
                    (fn [request]
                      (or (all-handlers request)
                          (miss-handler request))))
                  (constantly {:status 503 :body "no apps loaded"}))]
    (-> handler
        wrap-cookies
        wrap-params
        wrap-exceptions)))


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
  [name {:keys [project] :as box} command-logger]
  (let [ring-conf (clean-ring-conf (-> project :containium :ring))
        _ (assert (:handler ring-conf)
                  (str ":ring app config requires a :handler, but ring-conf is: " ring-conf))
        handler-fn (boxure/eval box `(do (require '~(symbol (namespace (:handler ring-conf))))
                                          ~(:handler ring-conf)))
        ;; Sorting value determines the rank. Higher ranks are tried first for serving a request.
        ;; This sorting value is based on three values, in the order importancy:
        ;;  - the :priority config within the :ring configuration of the app, which is a value
        ;;    between -92233720368 and 92233720368;
        ;;  - the number of slashes in the :context-path config within the :ring configuration
        ;;    of the app;
        ;;  - an automatically incremented sequence number of deployed apps, which means
        ;;    later added apps with the same priority and the same number of slashes get a higher
        ;;    rank.
        num-slashes (count (filter (partial = \/) (:context-path ring-conf)))
        sort-value (+ (* (or (:priority ring-conf) 0) 100 1000000)
                      (* num-slashes 1000000)
                      (swap! app-seq-no inc))]
    (RingApp. name box handler-fn ring-conf sort-value)))


;;; Distribution implementation.

(defrecord DistributedRing [rings]
  Ring
  (upstart-box [_ name box command-logger]
    (doseq [ring rings]
      (upstart-box ring name box command-logger)))
  (remove-box [_ name command-logger]
    (doseq [ring rings]
      (remove-box ring name command-logger))))


(def distributed
  (reify Startable
    (start [_ systems]
      (if-let [ring-systems (seq (filter (comp (partial satisfies? Ring) val) systems))]
        (do (println "Initialising Ring distributer among servers:" (keys ring-systems))
            (DistributedRing. (vals ring-systems)))
        (throw (Exception. "No Ring systems have been started."))))))
