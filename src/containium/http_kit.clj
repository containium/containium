;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.http-kit
  "The namespace for starting and stopping HTTP Kit, and managing
  boxes that contain ring apps."
  (:require [org.httpkit.server :refer (run-server)]
            [boxure.core :as boxure]))


;;; Ring app registry.

(defrecord RingApp [box handler-fn ring-conf])

(defonce apps (atom {}))

(defonce ^:private app nil)


;;; Overall ring handler creation.

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
                    (apply str (interpose ", " (map (comp :name :box) non-deterministic)))
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


(defn- make-app
  "Recreates the toplevel ring handler function, which routes the
  registered RingApps."
  []
  (let [handler (if-let [apps (seq (vals @apps))]
                  (let [sorted (sort-apps apps)
                        fn-form `(fn [~'request]
                                   (let [~'uri (:uri ~'request)
                                         ~'server-name (:server-name ~'request)]
                                     (or ~@(for [app sorted]
                                             `(when (matcher ~(:ring-conf app) ~'uri ~'server-name)
                                                (boxure/call-in-box
                                                 ~(:box app)
                                                 ~(:handler-fn app)
                                                 ~(if-let [cp (-> app :ring-conf :context-path)]
                                                    `(update-in ~'request [:uri]
                                                                #(subs % ~(count cp)))
                                                    ~'request)))))))]
                    (eval fn-form))
                  (constantly {:status 503 :body "no apps loaded"}))]
    (alter-var-root #'app (constantly handler))))


;;; Ring app registration.

(defn- clean-ring-conf
  "Updates the ring-conf, in order to ensure some properties of the values."
  [ring-conf]
  (let [result ring-conf
        result (update-in result [:context-path]
                          #(when % (if (= (last %) \/) (apply str (butlast %)) %)))
        result (update-in result [:context-path]
                          #(when % (if (= (first %) \/) % (str "/" %))))]
    result))


(defn upstart-box
  "Add a box holding a ring application. The box's project definition
  needs to have a :ring configuration inside the :containium
  configuration. A required key is :handler-sym, which, when evaluated
  inside the box, should be the ring handler function. Optional keys
  are :context-path and :host-regex. The first acts as a filter for
  the first element(s) in the request URI, for example \"/api/v1\".
  The second is a regular expression, which is matched against the
  server name of the request, for example \".*containium.com\"."
  [{:keys [name project] :as box}]
  (let [ring-conf (clean-ring-conf (-> project :containium :ring))
        handler-fn (boxure/eval box (:handler-sym ring-conf))]
    (swap! apps assoc name (RingApp. box handler-fn ring-conf))
    (make-app)))


(defn remove-box
  "Removes a box's ring handler from the ring server."
  [{name :name}]
  (swap! apps dissoc name)
  (make-app))


;;; Ring server management.

(defn start
  "Start HTTP Kit, based on the specified spec config."
  [config]
  (println "Starting HTTP Kit using config:" (:http-kit config))
  (make-app)
  (let [stop-fn (run-server #'app (:http-kit config))]
    (println "HTTP Kit started.")
    stop-fn))


(defn stop
  "Stop HTTP Kit, using the stop function as returned by `start`."
  [stop-fn]
  (println "Stopping HTTP Kit...")
  (stop-fn)
  (println "HTTP Kit stopped."))
