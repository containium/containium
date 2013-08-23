;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.elasticsearch
  (:import [org.elasticsearch.node Node NodeBuilder]))


(defn start
  [config]
  (println "Starting embedded ElasticSearch node...")
  (let [node (.node (NodeBuilder/nodeBuilder))]
    (println "Embedded ElasticSearsch node started.")
    node))


(defn stop
  [node]
  (println "Stopping embedded ElasticSearch node...")
  (.close node)
  (println "Embedded ElasticSearch node stopped."))
