;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.elasticsearch
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)])
  (:import [containium.systems Startable Stoppable]
           [org.elasticsearch.node Node NodeBuilder]))


;;; The public API for Elastic systems.

(defprotocol Elastic
  (node [this]
    "Returns the Node object used connecting.")) ;;---TODO: Is this a good API?


;;; The embedded implementation.

(defrecord EmbeddedElastic [^ Node node]
  Elastic
  (node [_] node)

  Stoppable
  (stop [_]
    (println "Stopping embedded ElasticSearch node...")
    (.close node)
    (println "Embedded ElasticSearch node stopped.")))


(def embedded
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :elastic)
            _ (println "Starting embedded ElasticSearch node, using config" config "...")
            node (.node (NodeBuilder/nodeBuilder))]
        (println "Embedded ElasticSearsch node started.")
        (EmbeddedElastic. node)))))
