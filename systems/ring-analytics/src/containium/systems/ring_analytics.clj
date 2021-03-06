;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring-analytics
  "Analytics is a system that can store ring requests."
  (:require [containium.systems :as systems :refer (require-system Startable Stoppable)]
            [containium.systems.elasticsearch :as es-system :refer (Elastic)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.logging :as logging :refer (SystemLogger refer-logging)]
            [containium.exceptions :as ex]
            [simple-time.core :as time]
            [clojurewerkz.elastisch.native :as es]
            [clojurewerkz.elastisch.native.conversion :as cnv]
            [clojurewerkz.elastisch.native.index :as esindex]
            [cheshire.core :as json]
            [clojure.string :refer (lower-case)]
            [clojure.stacktrace :refer (print-cause-trace)]
            [overtone.at-at :as at])
  (:import [org.elasticsearch.client Client]
           [org.elasticsearch.action.support IndicesOptions]
           [org.elasticsearch.action.admin.cluster.state ClusterStateResponse]
           [org.elasticsearch.cluster.metadata IndexMetaData IndexMetaData$State]))
(refer-logging)


;;; Utils

(defn- dissoc!-body [map _]
  (dissoc! map :body))

(defn- assoc!-string-body [{:keys [body] :as map}]
  (if (or (nil? body) (string? body))
    map
   ;else
    (assoc! map :body (str "Stringified: " body))))


;;; The public system API.

(defprotocol Analytics
  (wrap-ring-analytics
    [this app-name handler]
    [this app-name request-filter response-filter handler]
    "Returns a handler that stores the given request, using the given
    app name for the log name. The body is not read by this
    middleware.

    Before storing the request+response, request-filter and response-filter
    are called with 2 arguments: the corresponding transient map and the
    opposite (persistent) map, as such:
    - `(request-filter  (transient request)  response)`
    - `(response-filter (transient response) request)`

    Both filters functions must return a transient map.

    This allows removal of sensitive or uninteresting data before storing.
    When no filters are provided, a default filter that removes :body is used."))


;;; No-analytics (nil system) implementation

(extend-type nil
  Analytics
  (wrap-ring-analytics
    ([this app-name handler] handler)
    ([this app-name request-filter response-filter handler] handler)))


;;; ElasticSearch implementation.

(defn- daily-index
  [app started-utc-ms]
  (str "log-" (lower-case app) "-" (time/format (time/datetime started-utc-ms) :basic-date)))


(defn- store-request
  [client app-name started-utc-ms request]
  (es/index client (cnv/->index-request (daily-index app-name started-utc-ms)
                                        "request" request
                                        {:op-type "create", :content-type :smile})))


(defn- wrap-ring-analytics*
  [{:keys [client logger] :as record} app-name request-filter response-filter handler]
  (fn [request]
    (let [started (System/currentTimeMillis)
          response (try (handler request)
                        (catch Throwable t (ex/exit-when-fatal t) t))
          took (- (System/currentTimeMillis) started)
          processed (-> (transient request)
                        (request-filter response)
                        (dissoc! :async-channel)
                        (assoc! :started (time/format (time/datetime started)
                                                      :date-hour-minute-second-ms)
                                :response (if (instance? Throwable response)
                                            {:message (.getMessage ^Throwable response)
                                             :stacktrace (with-out-str
                                                           (print-cause-trace response))
                                             :class (str (class response))
                                             :status 500
                                             :took took}
                                           ;else, regular request:
                                            (-> (if (map? response)
                                                  response
                                                 ;else, wrap in map:
                                                  {:body response
                                                   :status (if (nil? response) 404 #_else 200)})
                                                (transient)
                                                (assoc! :took took)
                                                (response-filter request)
                                                (assoc!-string-body)
                                                (persistent!))))
                        (persistent!))]
      (try (store-request client app-name started processed)
           (catch Throwable t
             (ex/exit-when-fatal t)
             (error logger "Failed to store request for ring-analytics:")
             (error logger "Request (processed)" processed)
             (error logger t)))
      (if (instance? Throwable response) (throw response) response))))


(defn- put-template
  [client]
  (esindex/put-template client "log", :template "log-*"
    :settings {"index.refresh_interval" "5s"
               ;"index.codec" "best_compression" ;; Requires ES 2.0: https://github.com/elastic/elasticsearch/pull/8863
    }
    :mappings {:request {:properties {"started" {:type "date"
                                                 :format "date_hour_minute_second_millis"}}
                                     :_source {:excludes ["*.*password*"]}}}
    :content-type :smile, :create? false))

(defn- process-index-before-closing! [client logger delete-day optimize-day close-day
                                      ^IndexMetaData index-meta]
  (let [index (.index index-meta)
        created (-> (.creationDate index-meta) time/datetime)]
    (cond
      (time/< created delete-day)
      (do
        (info logger "Deleting index:" index)
        (esindex/delete client index)
        nil)

      (and (time/< created    close-day)
           (= IndexMetaData$State/OPEN (.getState index-meta)))
      index

      (and (time/< created optimize-day)
           (= IndexMetaData$State/OPEN (.getState index-meta)))
      (do
        ;;---TODO Find out if already optimized
        (info logger "Optimizing index:" index)
        (esindex/optimize client index :max-num-segments 1)
        nil)

      :else ;; do nothing and don't close
      nil)))

(defn- indices-operations
  "Optimizes and closes log-* indices. One can specify the days after
  which the operations should take place."
  [{:keys [^Client client logger] :as elastic-ring-analytics}
   {:keys [delete-after optimize-after close-after] :or {delete-after 3650, optimize-after 2, close-after 7}}]
  (info logger "Deleting:" delete-after "days or older; Optimizing:" optimize-after "days or older; and Closing:" close-after "days or older log indices...")
  (try
    (let [metas (let [request (.. client admin cluster prepareState
                                  (setMetaData true)
                                  (setIndicesOptions (IndicesOptions/fromOptions #_ignoreUnavailable true, #_allowNoIndices true, #_expandToOpenIndices true, #_expandToClosedIndices true))
                                  (setIndices (into-array String ["log-*"]))
                                  request)
                      ^ClusterStateResponse response (.. client admin cluster (state request) get)]
                  (.. response getState metaData indices))
          today (time/today)
          optimize-day (time/add-days today (- optimize-after))
          delete-day (time/add-days today (- delete-after))
          close-day (time/add-days today (- close-after))
          to-close (keep (partial process-index-before-closing!
                                  client logger delete-day optimize-day close-day)
                         (iterator-seq (.valuesIt metas)))]
      (when (seq to-close)
        (info logger "Closing indices:" (vec to-close))
        (esindex/flush client to-close)
        (esindex/close client (into-array String to-close))))
    (info logger "Done deleting, optimizing and closing old ring-analytics logs.")
    (catch Exception e
      (error logger "Error while deleting, optimizing and closing old ring-analytics logs:")
      (error logger e))))


(defrecord ElasticAnalytics [client logger atat]
  Analytics
  (wrap-ring-analytics [this app-name handler]
    (wrap-ring-analytics* this app-name, dissoc!-body   dissoc!-body    handler))
  (wrap-ring-analytics [this app-name request-filter response-filter handler]
    (wrap-ring-analytics* this app-name, request-filter response-filter handler))

  Stoppable
  (stop [this]
    (info logger "Stopping Analytics based on ElasticSearch.")
    (at/stop-and-reset-pool! atat :strategy :kill)
    (info logger "Stopped Analytics based on ElasticSearch.")))


(def elasticsearch
  (reify Startable
    (start [this systems]
      (let [config (get-config (require-system Config systems) :ring-analytics)
            logger (require-system SystemLogger systems)
            elastic (require-system Elastic systems)]
        (info logger "Starting Analytics based on ElasticSearch..." config)
        (let [client (es-system/client elastic)
              atat (at/mk-pool)
              elastic-ring-analytics (ElasticAnalytics. client logger atat)]
          (put-template client)
          (at/every (* 1000 60 60 24) ; 24 hours
                    (partial indices-operations elastic-ring-analytics config) atat
                    :initial-delay (* 1000 60 10)) ; 10 minutes
          (info logger "Started Analytics based on ElasticSearch.")
          elastic-ring-analytics)))))
