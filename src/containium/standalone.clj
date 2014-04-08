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


(defn run [spec {:keys [start stop ring profiles active-profiles dev?]
                 :or {:profiles [dev provided user system base]
                      :active-profiles [dev provided user system base]
                      :dev? true}
                 :as containium-map}]
  (with-systems systems [:config (map-config spec)
                         :repl repl/nrepl
                         :session-store (memory-store)
                         :ring (test-http-kit (-> ring :handler))
                         :elastic elastic/embedded
                         :cassandra cassandra/embedded12]
    (start systems
           {:file (io/as-file ".")
            :profiles profiles
            :active-profiles active-profiles
            :dev? (:dev? containium-map true)
            :containium containium-map})
    (read-line)
    (stop systems))
  (shutdown-agents))
