;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

;;;; Development settings.

{:config {:cassandra {:config-file "cassandra.yaml"}
          :kafka {:port "9090"
                  :broker-id "1"
                  :log-dir "target/kafka-log"
                  :zk-connect "localhost:2181"}
          :http-kit {:port 8080}
          :ring {:session-ttl 1}}
 :modules ["dev-resources/test-module/target/test-module-0.1.jar"]
 :resolve-dependencies true}
