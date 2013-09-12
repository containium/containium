;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

;;;; Development settings.

{:http-kit {:port 8080}
 :cassandra {:config-file "cassandra.yaml"}
 :session-store {:ttl 1}
 :modules {:resolve-dependencies true
           :isolates ["containium.*"
                      "org\\.httpkit.*"
                      "taoensso\\.nippy.*"
                      "ring.*"]
           :start-on-boot ["dev-resources/test-module/target/test-module-0.1.jar"]}
 :config {:kafka {:port "9090"
                  :broker-id "1"
                  :log-dir "target/kafka-log"
                  :zk-connect "localhost:2181"}
          :fs {:deployments "dev-resources/deployments"}}}
