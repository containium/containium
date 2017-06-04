;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(defproject containium "0.1.0-SNAPSHOT"
  :description "A horizontally-isolating application server for Clojure"
  :url "http://github.com/containium/containium"
  :license {:name "Mozilla Public License 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[boxure/clojure "1.8.0"]
                 [boxure "0.1.0-SNAPSHOT"]
                 [jline "2.12.1"]
                 [ring/ring-core "1.3.2"]
                 [org.clojure/tools.reader "0.10.0"]
                 [clj-time "0.9.0"]
                 [joda-time "2.8.1"]
                 [http-kit "2.2.0"]
                 [org.apache.httpcomponents/httpclient "4.5"]
                 [org.apache.cassandra/cassandra-all "2.2.9"
                  :exclusions [com.google.guava/guava com.thinkaurelius.thrift/thrift-server org.yaml/snakeyaml io.netty/netty io.dropwizard.metrics/metrics-core]]
                 ;[com.google.guava/guava "15.0"] ;; Cassandra still requires Guava 15
                 [org.yaml/snakeyaml "1.15"] ; >=1.11 required by r18n, used by some of our apps
                 [org.xerial.snappy/snappy-java "1.1.1.6"]
                 [org.elasticsearch/elasticsearch "1.7.6"]
                 [org.codehaus.groovy/groovy-all "2.4.0"] ;; To enable groovy scripts in ElasticSearch
                 [clojurewerkz/elastisch "2.1.0" :exclusions [clj-http]]
                 [org.scala-lang/scala-library "2.9.2"]
                 [org.apache.kafka/kafka_2.9.2 "0.8.2.2" :exclusions [org.slf4j/slf4j-log4j12 log4j]]
                 [com.taoensso/nippy "2.11.1"]
                 [com.taoensso/encore "2.46.1"]
                 [com.taoensso/timbre "4.3.1"]
                 [org.clojars.touch/elasticsearch-lang-clojure "0.2.0-SNAPSHOT"]
                 ;; Enable if using containium.systems.ring.netty
                 ;; [boxure/netty-ring-adapter "0.4.7"]
                 [info.sunng/ring-jetty9-adapter "0.8.5"]
                 [io.netty/netty "3.10.6.Final"]
                 [cc.qbits/alia "3.1.11"]
                 [org.apache.commons/commons-lang3 "3.4"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [simple-time "0.2.0"]
                 [com.maitria/packthread "0.1.6"]
                 [com.draines/postal "1.11.3"]
                 [myguidingstar/clansi "1.3.0"]
                 [lein-light-nrepl "0.1.0" :exclusions [org.clojure/tools.nrepl]]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [overtone/at-at "1.2.0"]]
  :exclusions [org.clojure/clojure org.xerial.snappy/snappy-java org.mortbay.jetty/jetty
               javax.jms/jms com.sun.jdmk/jmxtools com.sun.jmx/jmxri]
  :java-source-paths ["src-java"]
  :aot [containium.starter containium.with-external-cassandra containium.cassandra-node containium.systems.cassandra.config]
  :auto-clean false
  :main containium.starter
  :profiles {:doc {:dependencies [[codox/codox.core "0.6.6" :exclusions [org.clojure/clojure]]]}
             :aot {:aot [containium.core]
                   :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             ;; These :test dependencies are to make POM generation work...
             :test {:dependencies [
                      [org.clojure/tools.nrepl "0.2.10" :scope "compile"]
                      [clojure-complete "0.2.3" :scope "compile"]
                      ;[org.clojure/clojurescript "0.0-3308" :scope "compile"]
                    ]}
             :uberjar {:omit-sources true
                       :exclusions [;; org.apache.cassandra.config.DatabaseDescriptor.loadConfig()
                                    ;; needs: org.apache.thrift/libthrift
                                    org.apache.cassandra/cassandra-thrift]
                       :uberjar-exclusions [#"^[^/]+?(ya?ml|spec.clj)$"]}}
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=1024m"
             "-Djava.net.preferIPv4Stack=true"
             ;; "-XX:+TraceClassLoading"
             ;; "-XX:+TraceClassUnloading"
             ;; "-XX:+HeapDumpOnOutOfMemoryError"
             "-Xmx2048m" ; max heap size.
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
  :pom-plugins [[com.theoryinpractise/clojure-maven-plugin "1.7.1"
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
  :aliases {"launch" ["do" ["with-profile" "+aot" "compile"] "run"]}

  :java-agents [[com.github.jbellis/jamm "0.2.6"]])
