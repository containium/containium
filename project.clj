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
                 [ring/ring-core "1.2.0" :exclusions [[javax.servlet/servlet-api]]]
                 [http-kit "2.1.10"]
                 [org.apache.httpcomponents/httpclient "4.2.3"]
                 [org.apache.cassandra/cassandra-all "1.2.12" :exclusions [javax.servlet/servlet-api]]
                 [io.netty/netty "3.7.0.Final"]
                 [org.xerial.snappy/snappy-java      "1.1.0-M4"]
                 [org.elasticsearch/elasticsearch "0.90.5"]
                 [org.scala-lang/scala-library "2.9.2"]
                 [org.apache.kafka/kafka_2.9.2 "0.8.1"]
                 [com.taoensso/nippy "2.5.2"]
                 [org.clojure/core.cache "0.6.3"]
                 [org.clojars.touch/elasticsearch-lang-clojure "0.1.0-SNAPSHOT"]
                 ;; Enable if using containium.systems.ring.netty
                 ;; [boxure/netty-ring-adapter "0.4.7"]
                 [info.sunng/ring-jetty9-adapter "0.2.0"]
                 [cc.qbits/alia "1.9.2"]]
  :profiles {:test {:dependencies [[cc.qbits/alia "1.9.2"]]}
             :doc {:dependencies [[codox/codox.core "0.6.6" :exclusions [org.clojure/clojure]]]}}
  :exclusions [org.clojure/clojure org.xerial.snappy/snappy-java org.mortbay.jetty/jetty]
  :java-source-paths ["src-java"]
  :main containium.core
  :aot [containium.core]
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=512m"
             ;; "-XX:+TraceClassLoading"
             ;; "-XX:+TraceClassUnloading"
             ;; "-XX:+HeapDumpOnOutOfMemoryError"
             "-Xmx500m" ; max heap size.
             "-XX:OnOutOfMemoryError=./killpid.sh %p"
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
                                  [:temporaryOutputDirectory "false"]
                                  [:copyDeclaredNamespaceOnly "false"]
                                  [:compileDeclaredNamespaceOnly "false"]
                                  [:namespaces
                                   ;; Include the namespaces here that you want to skip compiling
                                   ;; altogether. Start the namespaces with a bang. For example:
                                   ;; [:namespace "!some.namespace.to.ignore"]
                                   [:namespace "!containium.systems.ring.netty"]])
                  :executions ([:execution
                                [:id "compile-clojure"]
                                [:phase "compile"]
                                [:goals [:goal "compile"]]]
                               [:execution
                                [:id "test-clojure"]
                                [:phase "test"]
                                [:goals [:goal "test"]]])}]

                [org.codehaus.mojo/buildnumber-maven-plugin "1.2"
                 {:executions [:execution [:phase "validate"] [:goals [:goal "create"]]]
                  :configuration ([:doCheck "false"] ; Set to true to prevent packaging with local changes.
                                  [:doUpdate "false"]
                                  [:shortRevisionLength "8"])}]

                [org.apache.maven.plugins/maven-jar-plugin "2.1"
                 {:configuration [:archive
                                  [:manifest [:addDefaultImplementationEntries "true"]]
                                  [:manifestEntries [:Containium-Version "${buildNumber}"]]]}]]
  :pom-addition [:properties [:project.build.sourceEncoding "UTF-8"]]
)
