;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(defproject containium "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Mozilla Public License 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[boxure/clojure "1.5.1"]
                 [boxure "0.1.0-SNAPSHOT"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [jline "2.11"]
                 [ring/ring-core "1.2.0"]
                 [http-kit "2.1.10"]
                 [org.apache.httpcomponents/httpclient "4.2.3"]
                 [org.apache.cassandra/cassandra-all "2.0.0" :exclusions [com.thinkaurelius.thrift/thrift-server
                                                                          org.yaml/snakeyaml]]
                 [org.elasticsearch/elasticsearch "0.90.5"]
                 [org.scala-lang/scala-library "2.9.2"]
                 [kafka/core-kafka_2.9.2 "0.7.2"]
                 [com.taoensso/nippy "2.1.0"]
                 [org.clojure/core.cache "0.6.3"]]
;➭ ll target/containium-0.1.0-SNAPSHOT-standalone.jar
;  With yaml:    -rw-r--r--  1 blue  staff  51399386 22 sep 02:28 target/containium-0.1.0-SNAPSHOT-standalone.jar
;                                           49,018274307 MB
;  Without yaml: -rw-r--r--  1 blue  staff  51128514 22 sep 02:26 target/containium-0.1.0-SNAPSHOT-standalone.jar
;                                           48,759950638 MB
  :exclusions [org.clojure/clojure]
  :java-source-paths ["src-java"]
  :aot 'containium.systems.cassandra.config
  :main containium.core
  :profiles {:release {:aot :all}
             :uberjar {:omit-sources true
                       :exclusions [;org.apache.cassandra.config.DatabaseDescriptor.loadConfig() needs: org.apache.thrift/libthrift
                                    org.apache.cassandra/cassandra-thrift]
                       :uberjar-exclusions [#"^[^/]+?(ya?ml|spec.clj)$"]}}
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=512m"
             ;; "-XX:+TraceClassLoading"
             ;; "-XX:+TraceClassUnloading"
             ;; "-XX:+HeapDumpOnOutOfMemoryError"
             ]
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

  :pom-addition [:build [:plugins
                            [:plugin
                              [:groupId "com.theoryinpractise"]
                              [:artifactId "clojure-maven-plugin"]
                              [:version "1.3.15"]
                              [:extensions "true"]
                              [:configuration
                                [:sourceDirectories
                                  [:sourceDirectory "src"]]
                                [:temporaryOutputDirectory "true"]]
                              [:executions
                                [:execution
                                  [:id "compile-clojure"]
                                  [:phase "compile"]
                                  [:goals
                                    [:goal "compile"]]]]]]])

;;; Sync this file with pom.xml.
