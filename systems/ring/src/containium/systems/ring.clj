;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring
  "The interface definition of the Ring system. The Ring system offers
  an API to a ring server."
  (:require [containium.systems]
            [containium.systems.config :refer (get-config)]
            [org.httpkit.server :refer (run-server)]
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
        handler-fn @(boxure/eval box `(do (require '~(symbol (namespace (:handler ring-conf))))
                                          ~(:handler ring-conf)))]
    (RingApp. name box handler-fn ring-conf)))


(defrecord HttpKit [stop-fn app apps]
  Ring
  (upstart-box [_ name box]
    (->> (box->ring-app name box)
         (swap! apps assoc name)
         (make-app)
         (reset! app)))
  (remove-box [_ name]
    (->> (swap! apps dissoc name)
         (make-app)
         (reset! app)))

  Stoppable
  (stop [_] (stop-fn)))


(def http-kit
  (reify Startable
    (start [_ systems]
      (let [app (atom (make-app {}))
            app-fn (fn [request] (@app request))
            stop-fn (run-server app-fn (get-config (:config systems) :http-kit))]
        (HttpKit. stop-fn app (atom {}))))))
