;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.elasticsearch
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.logging :as logging :refer (SystemLogger refer-logging)]
            [clojurewerkz.elastisch.native :as es])
  (:import [containium.systems Startable Stoppable]
           [org.elasticsearch.client Client]
           [org.elasticsearch.node Node NodeBuilder]
           [org.elasticsearch.client ClusterAdminClient]
           [org.elasticsearch.action ActionFuture]
           [org.elasticsearch.action.admin.cluster.health ClusterHealthRequest
            ClusterHealthResponse]))
(refer-logging)


;;; The public API for Elastic systems.

(defprotocol Elastic
  (^org.elasticsearch.client.Client client [this]
    "Returns the Client object usable for querying.")
  (^org.elasticsearch.node.Node node [this]
    "Returns the Node object when running embedded."))


(defn- wait-until-started
  "Wait until elastic node/cluster is ready."
  [^Client client timeout-secs]
  (let [^ClusterHealthRequest chr (.. (ClusterHealthRequest. (make-array String 0))
                                      (timeout (str timeout-secs "s"))
                                      waitForYellowStatus)
        ^ClusterAdminClient admin (.. client admin cluster)
        ^ActionFuture fut (.health admin chr)
        ^ClusterHealthResponse resp (.actionGet fut)]
    (not (.isTimedOut resp))))


(defrecord ElasticNode [^Node node logger]
  Elastic
  (node [_] node)
  (client [_] (.client node))

  Stoppable
  (stop [_]
    (info logger "Stopping embedded ElasticSearch node...")
    (.close node)
    ;; (reset! stop-monitor true)
    (info logger "Embedded ElasticSearch node stopped.")))

(defrecord ElasticClient [^Client client logger]
  Elastic
  (node [_] nil)
  (client [_] client)

  Stoppable
  (stop [_]
    (info logger "Stopping ElasticSearch client...")
    (.close client)
    ;; (reset! stop-monitor true)
    (info logger "ElasticSearch client stopped.")))


(defn- es-config [systems dissoc-key]
  (-> (require-system Config systems)
      (get-config :elasticsearch)
      (dissoc :client)
      (update-in [:wait-for-yellow-secs] #(or % 300))))

(def embedded
  (reify Startable
    (start [_ systems]
      (let [{:keys [wait-for-yellow-secs] :as config} (es-config systems :transport-client)
            logger (require-system SystemLogger systems)
            _ (info logger "Starting embedded ElasticSearch node, using config:" config "...")
            node (.node (NodeBuilder/nodeBuilder))]
        (info logger "Waiting for Embedded ElasticSearch node to have initialised.")
        (when-not (wait-until-started (.client node) wait-for-yellow-secs)
          (throw (Exception. (str "Cluster not ready within " wait-for-yellow-secs " seconds."))))
        (info logger "Embedded ElasticSearch node started.")
        (ElasticNode. node logger)))))

(def connection
  (reify Startable
    (start [_ systems]
      (let [{:keys [wait-for-yellow-secs] :as config} (es-config systems :embedded)
            logger (require-system SystemLogger systems)
            _ (info logger "Starting ElasticSearch transport client, using config:" config "...")
            {:keys [hosts settings]} (:transport-client config)
            client (apply es/connect (cond (and hosts settings) [hosts settings]
                                           settings             [["localhost" 9300] settings]
                                           hosts                [hosts {"cluster.name" "elasticsearch"}]
                                           :else                nil))]
        (info logger "Waiting for ElasticSearch client to have initialised.")
        (when-not (wait-until-started client wait-for-yellow-secs)
          (throw (Exception. (str "Cluster not ready within " wait-for-yellow-secs " seconds."))))
        (info logger "ElasticSearch client started.")
        (ElasticClient. client logger)))))
