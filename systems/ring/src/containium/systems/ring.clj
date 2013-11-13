;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring
  "The interface definition of the Ring system. The Ring system offers
  an API to a ring server."
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [org.httpkit.server :as httpkit]
            [netty.ring.adapter :as netty]
            [boxure.core :as boxure])
  (:import [containium.systems Startable Stoppable]))


;;; Public interface

(defprotocol Ring
  (upstart-box [this name box]
    "Add a box holding a ring application. The box's project definition
  needs to have a :ring configuration inside the :containium
  configuration. A required key is :handler, which, when evaluated
  inside the box, should be the ring handler function. Optional keys
  are :context-path and :host-regex. The first acts as a filter for
  the first element(s) in the request URI, for example \"/api/v1\".
  The second is a regular expression, which is matched against the
  server name of the request, for example \".*containium.com\".")

  (remove-box [this box]
    "Removes a box's ring handler from the ring server."))


;;; HTTP-Kit implementation.

(defrecord RingApp [name box handler-fn ring-conf])


(defn- sort-apps
  "Sort the RingApps for routing. Currently it sorts on the number of
  path elements in the context path, descending."
  [apps]
  (let [non-deterministic (remove #(or (-> % :ring-conf :context-path)
                                       (-> % :ring-conf :host-regex))
                                  apps)]
    (when (< 1 (count non-deterministic))
      (println (str "Warning: multiple web apps registered not "
                    "having a context-path nor a host-regex ("
                    (apply str (interpose ", " (map :name non-deterministic)))
                    ")."))))
  (->> apps
       (sort-by #(count (filter (partial = \/)
                                (-> % :ring-conf :context-path))))
       reverse))


(defmacro matcher
  "Evaluates to a match form, used by the make-app function."
  [{:keys [host-regex context-path]} uri-sym server-name-sym]
  (let [host-test `(re-matches ~host-regex ~server-name-sym)
        context-test `(and (.startsWith ~uri-sym ~context-path)
                           (= (get ~uri-sym ~(count context-path)) \/))]
    `(and ~@(remove nil? (list (when host-regex host-test)
                               (when context-path context-test))))))


(defn- wrap-try-catch
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (println "Error handling request:" request)
        (.printStackTrace t)
        {:status 500 :body "Internal error."}))))


(defn- make-app
  "Recreates the toplevel ring handler function, which routes the
  registered RingApps."
  [apps]
  (let [handler (if-let [apps (seq (vals apps))]
                  (let [sorted (vec (sort-apps apps))
                        fn-form `(fn [~'sorted ~'request]
                                   (let [~'uri (:uri ~'request)
                                         ~'server-name (:server-name ~'request)]
                                     (or ~@(for [index (range (count sorted))
                                                 :let [app (get sorted index)]]
                                             `(when (matcher ~(:ring-conf app) ~'uri ~'server-name)
                                                (let [~'app (get ~'sorted ~index)]
                                                  (boxure/call-in-box
                                                   (:box ~'app)
                                                   (:handler-fn ~'app)
                                                   ~(if-let [cp (-> app :ring-conf :context-path)]
                                                      `(update-in ~'request [:uri]
                                                                  #(subs % ~(count cp)))
                                                      'request))))))))]
                    (partial (eval fn-form) sorted))
                  (constantly {:status 503 :body "no apps loaded"}))]
    (wrap-try-catch handler)))


(defn- clean-ring-conf
  "Updates the ring-conf, in order to ensure some properties of the values."
  [ring-conf]
  (let [result ring-conf
        result (update-in result [:context-path]
                          #(when % (if (= (last %) \/) (apply str (butlast %)) %)))
        result (update-in result [:context-path]
                          #(when % (if (= (first %) \/) % (str "/" %))))]
    result))


(defn- box->ring-app
  [name {:keys [project] :as box}]
  (let [ring-conf (clean-ring-conf (-> project :containium :ring))
        _ (assert (and ring-conf (:handler ring-conf)) (print-str ":ring system config requires a :handler, but ring-conf only contains: " ring-conf))
        handler-fn @(boxure/eval box `(do (require '~(symbol (namespace (:handler ring-conf))))
                                          ~(:handler ring-conf)))]
    (RingApp. name box handler-fn ring-conf)))


(defrecord HttpKit [stop-fn app apps]
  Ring
  (upstart-box [_ name box]
    (println "Adding module" name "to HTTP-kit handler.")
    (->> (box->ring-app name box)
         (swap! apps assoc name)
         (make-app)
         (reset! app)))
  (remove-box [_ name]
    (println "Removing module" name "from HTTP-kit handler.")
    (->> (swap! apps dissoc name)
         (make-app)
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
            app (atom (make-app {}))
            app-fn (fn [request] (@app request))
            stop-fn (httpkit/run-server app-fn config)]
        (println "HTTP-Kit server started.")
        (HttpKit. stop-fn app (atom {}))))))


;;; HTTP-Kit implementation for testing.

(defrecord TestHttpKit [stop-fn]
  Ring
  (upstart-box [_ _ _]
    (Exception. "Cannot be used on a test HTTP-Kit implementation."))
  (remove-box [_ _]
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


;;; Netty implementation.

(defrecord Netty [stop-fn app apps]
  Ring
  (upstart-box [_ name box]
    (println "Adding module" name "to Netty handler.")
    (->> (box->ring-app name box)
         (swap! apps assoc name)
         (make-app)
         (reset! app)))
  (remove-box [_ name]
    (println "Removing module" name "from Netty handler.")
    (->> (swap! apps dissoc name)
         (make-app)
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
            app (atom (make-app {}))
            app-fn (fn [request] (@app request))
            stop-fn (netty/start-server app-fn config)]
        (println "Netty server started.")
        (Netty. stop-fn app (atom {}))))))


;;; Distribution of apps along multiple Ring server implementations.

(defrecord DistributedRing [rings]
  Ring
  (upstart-box [_ name box]
    (doseq [ring rings]
      (upstart-box ring name box)))
  (remove-box [_ name]
    (doseq [ring rings]
      (remove-box ring name))))


(def distributed
  (reify Startable
    (start [_ systems]
      (if-let [ring-systems (seq (filter (comp (partial satisfies? Ring) val) systems))]
        (do (println "Initialising Ring distributer among servers:" (keys ring-systems))
            (DistributedRing. (vals ring-systems)))
        (throw (Exception. "No Ring systems have been started."))))))
