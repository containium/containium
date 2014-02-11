;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring
  "The interface definition of the Ring system. The Ring system offers
  an API to a ring server."
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [org.httpkit.server :refer (run-server)]
            [boxure.core :as boxure])
  (:import [containium.systems Startable Stoppable]))


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


;;; HTTP-Kit implementation.

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
  (->> apps (sort-by #(get % :sort-value)) reverse))


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
  [log-fn apps]
  (let [handler (if-let [apps (seq (vals apps))]
                  (let [sorted (vec (sort-apps apps log-fn))
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
  [name {:keys [project] :as box} log-fn]
  (let [ring-conf (clean-ring-conf (-> project :containium :ring))
        _ (assert (:handler ring-conf)
                  (log-fn ":ring app config requires a :handler, but ring-conf only contains: "
                          ring-conf))
        handler-fn @(boxure/eval box `(do (require '~(symbol (namespace (:handler ring-conf))))
                                          ~(:handler ring-conf)))
        ;; Sorting value is the number of slashes in the context path and the time it is
        ;; deployed (actually, the time when this function is called). The more slashes and the
        ;; earlier it's deployed, the higher the rank. Higher ranks are tried first for serving
        ;; a request.
        num-slashes (count (filter (partial = \/) (:context-path ring-conf)))
        sort-value (long (+ (* num-slashes 1e15) (- 1e15 (System/currentTimeMillis))))]
    (RingApp. name box handler-fn ring-conf sort-value)))


(defrecord HttpKit [stop-fn app apps]
  Ring
  (upstart-box [_ name box log-fn]
    (->> (box->ring-app name box log-fn)
         (swap! apps assoc name)
         (make-app log-fn)
         (reset! app)))
  (remove-box [_ name log-fn]
    (->> (swap! apps dissoc name)
         (make-app log-fn)
         (reset! app)))

  Stoppable
  (stop [_] (stop-fn)))


(def http-kit
  (reify Startable
    (start [_ systems]
      (let [config (require-system Config systems)
            app (atom (make-app println {}))
            app-fn (fn [request] (@app request))
            stop-fn (run-server app-fn (get-config config :http-kit))]
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
            stop-fn (run-server handler config)]
        (println "Started test HTTP-Kit.")
        (TestHttpKit. stop-fn)))))
