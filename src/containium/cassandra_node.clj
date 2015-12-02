;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.cassandra-node
  (:require [containium.systems :refer (with-systems)]
            [containium.systems.cassandra.embedded :as cassandra]
            [containium.systems.config :as config]
            [containium.systems.logging :as logging :refer (refer-logging)]
            [containium.exceptions :as ex]
            [containium.reactor :as reactor]
            [clojure.java.io :refer (resource as-file)]
            [clojure.tools.nrepl.server :as nrepl])
  (:gen-class))
(refer-logging)

(defn -main
  "Launches a Cassandra Node process.

  When run with no arguments: interactive console is started.
  Any other argument will activate daemon mode."
  [& args]
  (ex/register-default-handler)
  (try (with-systems systems [:config (config/file-config (as-file (resource "spec.clj")))
                              :logging logging/logger
                              :cassandra cassandra/embedded]
         (reactor/run-daemon systems))
       (catch Exception ex
         (.printStackTrace ex))
       (finally
         (println "Shutting down...")
         (shutdown-agents)
         (reactor/shutdown-timer 15 true))))
