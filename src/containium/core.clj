;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.core
  (:require [containium.systems :refer (with-systems)]
            [containium.systems.cassandra.embedded :as cassandra]
            [containium.systems.elasticsearch :as elastic]
            [containium.systems.kafka :as kafka]
            [containium.systems.ring :as ring]
            [containium.systems.ring.http-kit :as http-kit]
            [containium.systems.ring.jetty9 :as jetty9]
            [containium.systems.ring-session-cassandra :as cass-session]
            [containium.deployer :as deployer]
            [containium.deployer.socket :as socket]
            [containium.systems.config :as config]
            [containium.modules :as modules]
            [containium.systems.repl :as repl]
            [containium.systems.ring-analytics :as ring-analytics]
            [containium.systems.mail :as mail]
            [containium.systems.logging :as logging :refer (refer-logging)]
            [containium.exceptions :as ex]
            [containium.reactor :as reactor]
            [clojure.java.io :refer (resource as-file)]
            [clojure.tools.nrepl.server :as nrepl])
  (:gen-class))
(refer-logging)


;; Convenience alias
(def ^:macro eval-in #'containium.reactor/eval-in)


(defn -main
  "Launches ‘all systems enabled’ Containium Reactor process.

  When run with no arguments: interactive console is started.
  Any other argument will activate daemon mode."
  [& [daemon? args]]
  (ex/register-default-handler)
  (try (with-systems systems [:config (config/file-config (as-file (resource "spec.clj")))
                              :logging logging/logger
                              :mail mail/postal
                              :cassandra cassandra/embedded
                              :elastic elastic/embedded
                              :kafka kafka/embedded
                              :session-store cass-session/default
                              :ring-analytics ring-analytics/elasticsearch
                              :http-kit http-kit/http-kit
                              :jetty9 jetty9/jetty9
                              :ring ring/distributed
                              :modules modules/default-manager
                              :fs deployer/directory
                              :repl repl/nrepl
                              ;; Socket needs to be the last system,
                              ;;  otherwise it doesn’t have the :repl system available.
                              :socket socket/socket]
         ((if daemon? reactor/run-daemon #_else reactor/run) systems))
       (catch Exception ex
         (.printStackTrace ex))
       (finally
         (println "Shutting down...")
         (shutdown-agents)
         (reactor/shutdown-timer 15 daemon?))))
