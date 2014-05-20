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
  (store-request [this app request]
    "Stores the given request, using the given app name for the log
    name. Returns the original request."))


;;; ElasticSearch implementation.

(defn- daily-index
  [app]
  (str "log-" app "-" (time/format (time/today) :basic-date)))


(defn- store-fn
  [node request]
  (elastic/index-doc node {:index (daily-index (::app request))
                           :source (json/generate-smile request)
                           :type "request"
                           :async? true
                           :content-type :smile})
  request)


(defrecord ElasticAnalytics [wrapped-fn]
  Analytics
  (store-request [this app request]
    (wrapped-fn (-> request
                    (dissoc :body :async-channel)
                    (assoc :timestamp (System/currentTimeMillis)
                           ::app app)))
    request)
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
                          :cookie-name "sid"}
            wrapped-fn (-> (partial store-fn (es-system/node elastic))
                           (ring.middleware.session/wrap-session session-opts)
                           prime.session/wrap-sid-query-param
                           ring.middleware.cookies/wrap-cookies
                           ring.middleware.params/wrap-params)]
        (println "Started Analytics based on ElasticSearch.")
        (ElasticAnalytics. wrapped-fn)))))
