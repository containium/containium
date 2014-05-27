;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring-analytics
  "Analytics is a system that can store ring requests."
  (:require [containium.systems :as systems :refer (Startable Stoppable)]
            [containium.systems.elasticsearch :as es-system :refer (Elastic)]
            [containium.exceptions :as ex]
            [ring.middleware.params]
            [ring.middleware.cookies]
            [simple-time.core :as time]
            [clj-elasticsearch.client :as elastic]
            [cheshire.core :as json]
            [packthread.core :refer (+>)]
            [clojure.string :refer (lower-case)]))


;;; The public system API.

(defprotocol Analytics
  (wrap-ring-analytics [this app-name handler]
    "Returns a handler that stores the given request, using the given
    app name for the log name. The body is not read by this
    middleware."))


;;; ElasticSearch implementation.

(defn- daily-index
  [app]
  (str "log-" (lower-case app) "-" (time/format (time/today) :basic-date)))


(defn- store-request
  [node app-name request]
  (elastic/index-doc node {:index (daily-index app-name)
                           :source (json/generate-smile request)
                           :type "request"
                           :async? false
                           :content-type :smile}))


(defn- wrap-ring-analytics*
  [{:keys [node] :as record} app-name handler]
  (-> (fn [request]
        (let [started (System/currentTimeMillis)
              response (try (handler request)
                            (catch Throwable t (ex/exit-when-fatal t) t))
              processed (+> request
                            (dissoc :body :async-channel)
                            (assoc :started (time/format (time/datetime started)
                                                         :date-hour-minute-second-ms)
                                   :took (- (System/currentTimeMillis) started))
                            (if (instance? Throwable response)
                              (assoc :failed (.getMessage ^Throwable response))
                              (assoc :status (:status response))))]
          (future (try (store-request node app-name processed)
                       (catch Throwable t
                         (ex/exit-when-fatal t)
                         (println "Failed to store request for ring-analytics:")
                         (println "Request (processed)" processed)
                         (.printStackTrace t))))
          (if (instance? Throwable response) (throw response) response)))
      ring.middleware.cookies/wrap-cookies
      ring.middleware.params/wrap-params))


(defn- put-template
  [node]
  (let [template {:template "log-*"
                  :mappings {:request {:properties {"started"
                                                    {:type :date
                                                     :format :date_hour_minute_second_millis}}
                                       :_source {:excludes ["params.password"
                                                            "form-params.password"]}}}}]
    (elastic/put-template node {:name "log"
                                :source (json/generate-smile template)
                                :content-type :smile
                                :create? false})))


(defrecord ElasticAnalytics [node]
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
      (let [elastic (systems/require-system Elastic systems)]
        (let [node (es-system/node elastic)]
          (put-template node)
          (println "Started Analytics based on ElasticSearch.")
          (ElasticAnalytics. node))))))
