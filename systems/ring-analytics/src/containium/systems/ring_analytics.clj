;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring-analytics
  "Analytics is a system that can store ring requests."
  (:require [containium.systems :as systems :refer (Startable Stoppable)]
            [containium.systems.elasticsearch :as es-system :refer (Elastic)]
            [ring.middleware.session.store :refer (SessionStore)]
            [ring.middleware.params]
            [ring.middleware.session]
            [ring.middleware.cookies]
            [prime.session]
            [simple-time.core :as time]
            [clj-elasticsearch.client :as elastic]
            [cheshire.core :as json]))


;;; The public system API.

(defprotocol Analytics
  (wrap-ring-analytics [this app-name handler]
    "Returns a handler that stores the given request, using the given
    app name for the log name. The body is not read by this
    middleware."))


;;; ElasticSearch implementation.

(defn- daily-index
  [app]
  (str "log-" app "-" (time/format (time/today) :basic-date)))


(defn- store-request
  [node app-name request]
  (elastic/index-doc node {:index (daily-index app-name)
                           :source (json/generate-smile request)
                           :type "request"
                           :async? true
                           :content-type :smile})
  request)


(defn- wrap-ring-analytics*
  [{:keys [node session-opts] :as record} app-name handler]
  (let [wrapped (-> handler
                    (ring.middleware.session/wrap-session session-opts)
                    prime.session/wrap-sid-query-param
                    ring.middleware.cookies/wrap-cookies
                    ring.middleware.params/wrap-params)]
    (fn [request]
      (let [started (System/currentTimeMillis)
            response (wrapped request)
            processed (-> request
                          (dissoc :body :async-channel)
                          (assoc :started started
                                 :took (- (System/currentTimeMillis) started)
                                 :status (:status response)))]
        (store-request node app-name processed)
        response))))


(defrecord ElasticAnalytics [node session-opts]
  Analytics
  (wrap-ring-analytics [this app-name handler]
    (wrap-ring-analytics* this app-name handler))
  Stoppable
  (stop [this]
    (println "Stopped Analytics based on ElasticSearch.")))


(def elasticsearch
  (reify Startable
    (start [this systems]
      (println "Starting Analytics based on ElasticSearch...")
      (let [elastic (systems/require-system Elastic systems)
            session-store (systems/require-system SessionStore systems)
            session-opts {:session-store session-store
                          :cookie-name "sid"}]
        (println "Started Analytics based on ElasticSearch.")
        (ElasticAnalytics. (es-system/node elastic) session-opts)))))
