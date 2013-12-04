;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.standalone
  (:require [containium.systems :refer (with-systems)]
            [containium.systems.config :refer (map-config)]
            [containium.systems.repl :as repl]
            [containium.systems.elasticsearch :as elastic]
            [containium.systems.cassandra.embedded12 :as cassandra]
            [containium.systems.ring.http-kit :refer (test-http-kit)]
            [ring.middleware.session.memory :refer (memory-store)]
            [clojure.java.io :as io]))


(defn run [spec containium-map]
  (prn "Ring handler: " (-> containium-map :ring :handler))
  (with-systems systems [:config (map-config spec)
                         :repl repl/nrepl
                         :session-store (memory-store)
                         :ring (test-http-kit (-> containium-map :ring :handler))
                         :elastic elastic/embedded
                         :cassandra cassandra/embedded12]
    (let [start-fn (:start containium-map)
          stop-fn (:stop containium-map)]
      (start-fn systems
                {:file (io/as-file ".")
                 :profiles []
                 :active-profiles []
                 :dev? true
                 :containium containium-map})
      (read-line)
      (stop-fn systems)))
  (shutdown-agents))
