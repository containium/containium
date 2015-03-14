;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring-analytics
  "Analytics is a system that can store ring requests."
  (:require [containium.systems :as systems :refer (Startable Stoppable)]
            [containium.systems.elasticsearch :as es-system :refer (Elastic)]
            [containium.systems.logging :as logging :refer (SystemLogger refer-logging)]
            [containium.exceptions :as ex]
            [simple-time.core :as time]
            [clojurewerkz.elastisch.native.document :as elastic]
            [clojurewerkz.elastisch.native.index :as esindex]
            [cheshire.core :as json]
            [clojure.string :refer (lower-case)]
            [clojure.stacktrace :refer (print-cause-trace)]
            [overtone.at-at :as at])
  (:import [org.elasticsearch.client Client]
           [org.elasticsearch.action.admin.cluster.state ClusterStateResponse]
           [org.elasticsearch.cluster.metadata IndexMetaData]))
(refer-logging)


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
  [client app-name request]
  (elastic/create client (daily-index app-name) "request" request :content-type :smile))


(defn- wrap-ring-analytics*
  [{:keys [client logger] :as record} app-name handler]
  (fn [request]
    (let [started (System/currentTimeMillis)
          response (try (handler request)
                        (catch Throwable t (ex/exit-when-fatal t) t))
          took (- (System/currentTimeMillis) started)
          processed (-> request
                        (dissoc :body :async-channel)
                        (assoc :started (time/format (time/datetime started)
                                                     :date-hour-minute-second-ms)
                               :response (if (instance? Throwable response)
                                           {:message (.getMessage ^Throwable response)
                                            :stacktrace (with-out-str
                                                          (print-cause-trace response))
                                            :class (str (class response))
                                            :status 500
                                            :took took}
                                           (-> response
                                               (dissoc :body)
                                               (assoc :took took)))))]
      (future (try (store-request client app-name processed)
                   (catch Throwable t
                     (ex/exit-when-fatal t)
                     (error logger "Failed to store request for ring-analytics:")
                     (error logger "Request (processed)" processed)
                     (error logger t))))
      (if (instance? Throwable response) (throw response) response))))


(defn- put-template
  [client]
  (esindex/put-template client "log", :template "log-*"
    :settings {"index.refresh_interval" "5s"
               ;"index.codec" "best_compression" ;; Requires ES 2.0: https://github.com/elastic/elasticsearch/pull/8863
    }
    :mappings {:request {:properties {"started" {:type "date"
                                                 :format "date_hour_minute_second_millis"}}
                                     :_source {:excludes ["params.password"
                                                          "form-params.password"]}}}
    :content-type :smile, :create? false))


(defn- indices-operations
  "Optimizes and closes log-* indices. One can specify the days after
  which the operations should take place."
  [{:keys [^Client client logger] :as elastic-ring-analytics}
   {:keys [optimize-after close-after] :or {optimize-after 2 close-after 7}}]
  (info logger "Optimizing and closing old ring-analytics logs...")
  (try
    (let [metas (let [request (.. client admin cluster prepareState (setMetaData true)
                                  (setIndices (into-array String ["log-*"]))
                                  request)
                      ^ClusterStateResponse response (.. client admin cluster (state request) get)]
                  (.. response getState metaData indices))
          today (time/today)
          optimize-day (time/add-days today (- optimize-after))
          close-day (time/add-days today (- close-after))
          to-close (keep (fn [^IndexMetaData index-meta]
                           (let [index (.index index-meta)
                                 created (-> (.creationDate index-meta) time/datetime)]
                             (if (time/< created close-day)
                               index
                               (when (time/< created optimize-day)
                                 ;;---TODO Find out if already optimized
                                 (info logger "Optimizing index:" index)
                                 (esindex/optimize client index :max-num-segments 1)
                                 nil))))
                         (iterator-seq (.valuesIt metas)))]
      (when (seq to-close)
        (info logger "Closing indices:" (vec to-close))
        (esindex/flush client to-close)
        (esindex/close client (into-array String to-close))))
    (info logger "Done optimizing and closing old ring-analytics logs.")
    (catch Exception e
      (error logger "Error optimizing and closing old ring-analytics logs.")
      (error logger e))))


(defrecord ElasticAnalytics [client logger atat]
  Analytics
  (wrap-ring-analytics [this app-name handler]
    (wrap-ring-analytics* this app-name handler))
  Stoppable
  (stop [this]
    (info logger "Stopping Analytics based on ElasticSearch.")
    (at/stop-and-reset-pool! atat :strategy :kill)
    (info logger "Stopped Analytics based on ElasticSearch.")))


(def elasticsearch
  (reify Startable
    (start [this systems]
      (let [elastic (systems/require-system Elastic systems)
            logger (systems/require-system SystemLogger systems)]
        (info logger "Starting Analytics based on ElasticSearch...")
        (let [client (.client (es-system/node elastic))
              atat (at/mk-pool)
              elastic-ring-analytics (ElasticAnalytics. client logger atat)]
          (put-template client)
          (at/every (* 1000 60 60 24) ; 24 hours
                    (partial indices-operations elastic-ring-analytics nil) atat
                    :initial-delay (* 1000 60 10)) ; 10 minutes
          (info logger "Started Analytics based on ElasticSearch.")
          elastic-ring-analytics)))))
