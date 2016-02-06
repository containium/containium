;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(defproject containium.systems/ring-analytics "0.3.2"
  :description "Ring requests and responses stored in ElasticSearch for use with Kibana"
  :url "http://containium.org"
  :scm {:dir "../../"}
  :license {:name "Mozilla Public License 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[containium.systems/elasticsearch "0.1.1"]
                 [simple-time "0.2.0"]
                 [overtone/at-at "1.2.0"]
                ]
  :java-source-paths ["src-java"]
  :global-vars {*warn-on-reflection* true}
  :pom-plugins [[com.theoryinpractise/clojure-maven-plugin "1.7.1"
                 {:extensions "true"
                  :configuration ([:sourceDirectories [:sourceDirectory "src"]])
                  :executions ([:execution
                                [:id "non-aot-compile"]
                                [:phase "compile"]
                                [:configuration
                                 [:temporaryOutputDirectory "true"]
                                 [:copyDeclaredNamespaceOnly "false"]
                                 [:compileDeclaredNamespaceOnly "false"]]
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
)
