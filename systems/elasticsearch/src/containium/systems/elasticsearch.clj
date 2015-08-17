;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.elasticsearch
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.logging :as logging :refer (SystemLogger refer-logging)])
  (:import [containium.systems Startable Stoppable]
           [org.elasticsearch.node Node NodeBuilder]
           [org.elasticsearch.client ClusterAdminClient]
           [org.elasticsearch.action ActionFuture]
           [org.elasticsearch.action.admin.cluster.health ClusterHealthRequest
            ClusterHealthResponse]))
(refer-logging)


;;; The public API for Elastic systems.

(defprotocol Elastic
  (^org.elasticsearch.node.Node node [this]
    "Returns the Node object used connecting.")) ;;---TODO: Is this a good API?


;;; The embedded implementation.

;; (def stop-monitor (atom false))

;; (defn- monitor
;;   []
;;   (println "[!!] ES monitor started.")
;;   (loop [stop? @stop-monitor
;;          last ""]
;;     (if-not stop?
;;       (let [new (slurp "http://localhost:9200/_cluster/health")]
;;         (when (not= new last)
;;           (println "[!!] health:" new))
;;         (Thread/sleep 25)
;;         (recur @stop-monitor new))
;;       (println "[!!] ES monitor stopped."))))


(defn- wait-until-started
  "Wait until elastic node/cluster is ready."
  [^Node node timeout-secs]
  (let [^ClusterHealthRequest chr (.. (ClusterHealthRequest. (make-array String 0))
                                      (timeout (str timeout-secs "s"))
                                      waitForYellowStatus)
        ^ClusterAdminClient admin (.. node client admin cluster)
        ^ActionFuture fut (.health admin chr)
        ^ClusterHealthResponse resp (.actionGet fut)]
    (not (.isTimedOut resp))))


(defrecord EmbeddedElastic [^Node node logger]
  Elastic
  (node [_] node)

  Stoppable
  (stop [_]
    (info logger "Stopping embedded ElasticSearch node...")
    (.close node)
    ;; (reset! stop-monitor true)
    (info logger "Embedded ElasticSearch node stopped.")))


(def embedded
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :elastic)
            logger (require-system SystemLogger systems)
            _ (info logger "Starting embedded ElasticSearch node, using config" config "...")
            node (.node (NodeBuilder/nodeBuilder))]
        ;; (-> (Thread. monitor) .start)
        (info logger "Waiting for Embedded ElasticSearch node to have initialised.")
        (when-not (wait-until-started node (or (:wait-for-yellow-secs config) 300))
          (throw (Exception. "Could not initialise ES within 300 seconds.")))
        (info logger "Embedded ElasticSearch node started.")
        (EmbeddedElastic. node logger)))))
