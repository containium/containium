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
                 ;; Newer version of `nrepl` available, but only useable when leiningen starts
                 ;; using the newest `reply`.
                 [org.clojure/tools.nrepl "0.2.3"]
                 [jline "2.11"]
                 [ring/ring-core "1.3.0"]
                 [http-kit "2.1.18"]
                 [org.apache.httpcomponents/httpclient "4.3.2"]
                 [org.apache.cassandra/cassandra-all "2.0.10"
                  :exclusions [com.thinkaurelius.thrift/thrift-server org.yaml/snakeyaml]]
                 [org.yaml/snakeyaml "1.13"] ; >=1.11 required by r18n, used by some of our apps
                 [org.xerial.snappy/snappy-java "1.1.0-M4"]
                 [org.elasticsearch/elasticsearch "1.2.1"]
                 [com.sonian/elasticsearch-zookeeper "1.2.0"]
                 [org.scala-lang/scala-library "2.9.2"]
                 [org.apache.kafka/kafka_2.9.2 "0.8.1.1"]
                 [com.taoensso/nippy "2.5.2"]
                 [org.clojars.touch/elasticsearch-lang-clojure "0.2.0-SNAPSHOT"]
                 ;; Enable if using containium.systems.ring.netty
                 ;; [boxure/netty-ring-adapter "0.4.7"]
                 [info.sunng/ring-jetty9-adapter "0.6.1"]
                 [cc.qbits/alia "2.1.2"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [simple-time "0.1.1"]
                 [clojurewerkz/elastisch "2.0.0"]
                 [com.maitria/packthread "0.1.1"]
                 [com.draines/postal "1.11.1"]
                 [com.taoensso/timbre "3.2.1"]
                 [myguidingstar/clansi "1.3.0"]
                 [lein-light-nrepl "0.0.18"]
                 ;; The `clojure-complete` is required by lein-light-nrepl, but when omitted,
                 ;; the `lein pom` result only includes it as 'test' scope.
                 [clojure-complete "0.2.3"]
                 [prone "0.4.0"]]
  :exclusions [org.clojure/clojure org.xerial.snappy/snappy-java org.mortbay.jetty/jetty
               javax.jms/jms com.sun.jdmk/jmxtools com.sun.jmx/jmxri]
  :java-source-paths ["src-java"]
  :aot [containium.starter containium.systems.cassandra.config]
  :main containium.starter
  :profiles {:doc {:dependencies [[codox/codox.core "0.6.6" :exclusions [org.clojure/clojure]]]}
             :aot {:aot [containium.core]}
             :uberjar {:omit-sources true
                       :exclusions [;; org.apache.cassandra.config.DatabaseDescriptor.loadConfig()
                                    ;; needs: org.apache.thrift/libthrift
                                    org.apache.cassandra/cassandra-thrift]
                       :uberjar-exclusions [#"^[^/]+?(ya?ml|spec.clj)$"]}}
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=512m"
             "-Djava.net.preferIPv4Stack=true"
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
                  :configuration ([:sourceDirectories [:sourceDirectory "src"]])
                  :executions ([:execution
                                [:id "aot-compile"]
                                [:phase "compile"]
                                [:configuration
                                 [:temporaryOutputDirectory "false"]
                                 [:copyDeclaredNamespaceOnly "true"]
                                 [:compileDeclaredNamespaceOnly "true"]
                                 [:namespaces
                                  ;; Include the namespaces here that need to be AOT compiled for
                                  ;; inclusion in the JAR here. For example:
                                  ;; [:namespace "prime.types.cassandra-repository"]
                                  [:namespace "containium.systems.cassandra.config"]]]
                                [:goals [:goal "compile"]]]
                               [:execution
                                [:id "non-aot-compile"]
                                [:phase "compile"]
                                [:configuration
                                 [:temporaryOutputDirectory "true"]
                                 [:copyDeclaredNamespaceOnly "false"]
                                 [:compileDeclaredNamespaceOnly "false"]
                                 [:namespaces
                                  ;; Include the namespaces here that you want to skip compiling
                                  ;; altogether. Start the namespaces with a bang. For example:
                                  ;; [:namespace "!some.namespace.to.ignore"]
                                  [:namespace "!containium.systems.ring.netty"]]]
                                [:goals [:goal "compile"]]]
                               [:execution
                                [:id "test-clojure"]
                                [:phase "test"]
                                [:goals [:goal "test"]]])}]

                [org.apache.maven.plugins/maven-compiler-plugin "3.1"
                 {:configuration ([:source "1.7"] [:target "1.7"])}]

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
  :aliases {"launch" ["with-profile" "+aot" "run"]}

  :java-agents [[com.github.jbellis/jamm "0.2.6"]])
