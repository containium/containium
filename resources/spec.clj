;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

;;;; Production settings.

{:http-kit {:port 8080}
 :cassandra {:config-file "cassandra.yaml"}
 :alia {:contact-points ["localhost"]
        :port 9042}
 :session-store {:ttl 60}
 :kafka {:server {:port 9090
                  :brokerid 1
                  :log.dir "target/kafka-log"
                  :zk.connect "localhost:2181"}
         :producer {:serializer.class "nl.storm.MessagePackVOSerializer"
                    :zk.connect "localhost:2181"}}
 :elastic {}
 :modules {:resolve-dependencies true
           :isolates ["containium.*"
                      "org\\.httpkit.*"
                      "taoensso\\.nippy.*"
                      "ring.*"]}
 :fs {:deployments "deployments"}
 :socket {:port 9999
          :wait2finish-secs 30
          :wait2close-secs 5}}
