;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.elasticsearch
  (:require [containium.systems :refer (->AppSystem)])
  (:import [org.elasticsearch.node Node NodeBuilder]))


(defn start
  [config systems]
  (println "Starting embedded ElasticSearch node...")
  (let [node (.node (NodeBuilder/nodeBuilder))]
    (println "Embedded ElasticSearsch node started.")
    node))


(defn stop
  [node]
  (println "Stopping embedded ElasticSearch node...")
  (.close node)
  (println "Embedded ElasticSearch node stopped."))


(def system (->AppSystem start stop nil))
