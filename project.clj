;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(defproject containium "0.1.0-SNAPSHOT"
  :description "A horizontally-isolating application server for Clojure"
  :url "http://github.com/containium/containium"
  :license {:name "Mozilla Public License 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[boxure/clojure "1.5.1"]
                 [boxure "0.1.0-SNAPSHOT"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [jline "2.11"]
                 [ring/ring-core "1.2.0"]
                 [http-kit "2.1.10"]
                 [org.apache.httpcomponents/httpclient "4.2.3"]
                 [org.apache.cassandra/cassandra-all "1.2.10"]
                 [io.netty/netty "3.9.0.Final"]
                 [org.xerial.snappy/snappy-java      "1.1.0-M4"]
                 [org.elasticsearch/elasticsearch "0.90.5"]
                 [org.scala-lang/scala-library "2.9.2"]
                 [kafka/core-kafka_2.9.2 "0.7.2"]
                 [com.taoensso/nippy "2.2.0"]
                 [org.clojure/core.cache "0.6.3"]
                 [cc.qbits/alia "1.9.2"]]
  :exclusions [org.clojure/clojure org.xerial.snappy/snappy-java]
  :java-source-paths ["src-java"]
  :main containium.core
  :aot [containium.core]
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=512m"
             ;; "-XX:+TraceClassLoading"
             ;; "-XX:+TraceClassUnloading"
             ;; "-XX:+HeapDumpOnOutOfMemoryError"
             ]
  :repl-options {:port 13337}
  :global-vars {*warn-on-reflection* true}
  :plugins [[codox "0.6.6"]]
  :codox {:output-dir "codox"
          :src-dir-uri "https://github.com/containium/blob/master/containium/"
          :src-linenum-anchor-prefix "L"
          :include [containium.systems
                    containium.systems.cassandra
                    containium.systems.config
                    containium.deployer
                    containium.systems.elasticsearch
                    containium.systems.kafka
                    containium.modules
                    containium.systems.repl
                    containium.systems.ring]}
  :pom-plugins [[com.theoryinpractise/clojure-maven-plugin "1.3.15"
                 {:extensions "true"
                  :configuration ([:sourceDirectories [:sourceDirectory "src"]]
                                  [:temporaryOutputDirectory "false"])
                  :executions [:execution
                               [:id "compile-clojure"]
                               [:phase "compile"]
                               [:goals [:goal "compile"]]]}]])
