;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

;;; Development settings.

{:http-kit {:port 8080
            :max-body 52428800} ; 50 mb
 :netty {:port 8090
         :zero-copy true
         :max-http-chunk-length 1073741824 ; 1 gb
         :max-channel-memory-size 1048576 ; 1 mb
         :max-total-memory-size 1048576} ; 1 mb
 :jetty9 {:port 8100
          :join? false
          :ssl-port 8443
          :keystore "dev-resources/dev_keystore.jks"
          :key-password "whoopwhoop"}
 :cassandra {:config-file "cassandra-test.yaml"
             :triggers-dir "target"
             :listen-address "localhost"}
 :alia {:contact-points ["localhost"]
        :port 9042}
 :session-store {:ttl-mins 60
                 :ttl-days 30}
 :kafka {:server {:port 9090
                  :broker.id 1
                  :message.max.bytes 16777216
                  :replica.fetch.max.bytes 16777216
                  :log.dir "target/kafka-log"
                  :zookeeper.connect "localhost"}}
 :elasticsearch {:wait-for-yellow-secs 300}
 :modules {:resolve-dependencies true
           :isolates [;"containium.*"
                      "containium\\.utils.*"
                      "taoensso\\.nippy.*"
                      "taoensso\\.timbre.*"
                      "taoensso\\.encore.*"
                      "ring\\.(?!middleware).*"
                      "leiningen.*"
                      "robert.hooke"
                      "cemerick.pomegranate.*"
                      "bultitude.core*"
                      "dynapath.*"
                      "simple_time.*"
                      "postal.*"
                      "clj_http.*"
                      "overtone\\.at_at.*"
                      "clojure\\.core\\.cache.*"
                      "clojure\\.core\\.memoize.*"
                      ;; Analytics deps
                      "clj_elasticsearch.*"
                      "cheshire.*"
                      "gavagai.*"
                      ;; Prime middleware deps
                      "prime\\.session.*"
                      "ring\\.util.*"
                      "noir.*"
                      ;; Packthread deps
                      "packthread.*"
                      "clojure\\.core\\.match.*"
                      ;; Alia system deps
                      "qbits.*"
                      "cljs.core.async.*"
                      "clojure.core.async.*"
                      "lamina.*"
                      "taoensso.nippy.*"
                      "potemkin.*"
                      "flatland.*"
                      "useful.*"
                      "clj_tuple.*"
                      "riddley\\.(?!Util).*"
                      ;; The http-kit AsyncChannel is not isolated because of pubsure-reader app.
                      ;; Keep this in mind when seeking leaks. :)
                      ;;"org\\.httpkit\\.(?!server\\.AsyncChannel).*"
                      ;; lighttable-nrepl deps
                      "clj_stacktrace.*"
                      "fs.*"
                      ;; Prone deps
                      "prone.*"
                      "flare.*"
                      "hiccup_find.*"
                      "cljs.*"
                      "schema.*"
                      "quiescent.*"
                      ]}
 :repl {:port 13337}
 :fs {:deployments "deployments"}
 :socket {:port 9999
          :wait2finish-secs 30
          :wait2close-secs 5}
 :postal {:host "localhost"
          :port 1025}}
