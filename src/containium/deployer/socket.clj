;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.deployer.socket
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :as config :refer (Config)]
            [containium.deployer :refer :all]
            [containium.modules :as modules :refer (Manager)]
            [clojure.string :refer (trim)]
            [clojure.java.io :refer (as-file)]
            [clojure.core.async :as async :refer (<! >!)])
  (:import [org.jboss.netty.channel SimpleChannelHandler ChannelHandlerContext MessageEvent Channel
            ChannelFutureListener ChannelFactory]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory]
           [org.jboss.netty.bootstrap ServerBootstrap]
           [org.jboss.netty.handler.codec.string StringDecoder StringEncoder]
           [java.util.concurrent Executors]
           [java.net InetSocketAddress]
           [containium.modules Response]))


;;; Async loop for writing to Netty channel.

(defn write
  [^Channel nettyc msg]
  (if (instance? containium.modules.Response msg)
    (do (when-let [status (:status msg)] (.write nettyc (str "STATUS " (name status) \newline)))
        (.write nettyc (str (if (:success? msg) "SUCCESS" "FAILED") \newline)))
    (.write nettyc (str msg \newline))))


(defn handler-loop
  ([^Channel nettyc]
     (handler-loop nettyc (async/chan)))
  ([^Channel nettyc commc]
     (async/go-loop [fut nil]
       (if-let [msg (<! commc)]
         (recur (try
                  (write nettyc msg)
                  (catch Exception ex
                    (async/close! commc)
                    (println "Failed to write" msg "to netty channel:" (.getMessage ex)))))
         (if fut
           (.addListener fut ChannelFutureListener/CLOSE)
           (.close nettyc))))
     commc))


;;; The message handler for Netty server.

(defn close-with-error
  [commc ^String msg status]
  (async/go
   (>! commc msg)
   (>! commc (Response. false status))
   (async/close! commc)))


(defn handler
  [manager]
  (proxy [SimpleChannelHandler] []
    (messageReceived [^ChannelHandlerContext ctx ^MessageEvent evt]
      (let [^String msg (.getMessage evt)
            [command & args] (map (fn [[normal quoted]] (or quoted normal))
                                  (re-seq #"'([^']+)'|[^\s]+" (trim msg)))
            commc (handler-loop ^Channel (.getChannel evt))]
        (when command ;; ignore empty lines
          (case (.toLowerCase ^String command)
            "activate"
            (let [[name path] args]
              (if name
                (modules/activate! manager name
                                   (when path (modules/module-descriptor (as-file path)))
                                   commc)
                (close-with-error commc "Missing name argument." nil)))

            "deactivate"
            (let [[name] args]
              (if name
                (modules/deactivate! manager name commc)
                (close-with-error commc "Missing name argument." nil)))

            "kill"
            (let [[name] args]
              (if name
                (modules/kill! manager name commc)
                (close-with-error commc "Missing name argument." nil)))

            (close-with-error commc (str "Unkown command: " command) nil)))))))


;;; Creation of Netty server and socket system.

(defn netty-server
  [port handler]
  (let [channel-factory (NioServerSocketChannelFactory. (Executors/newCachedThreadPool)
                                                        (Executors/newCachedThreadPool))
        bootstrap (ServerBootstrap. channel-factory)
        pipeline (.getPipeline bootstrap)]
    (.addLast pipeline "decoder" (new StringDecoder))
    (.addLast pipeline "encoder" (new StringEncoder))
    (.addLast pipeline "handler" handler)
    (.setOption bootstrap "child.tcpNoDelay", true)
    (.setOption bootstrap "child.keepAlive", true)
    (.bind bootstrap (InetSocketAddress. port))
    channel-factory))


(defrecord SocketDeployer [^ChannelFactory factory]
  Deployer
  (bootstrap-modules [this])

  Stoppable
  (stop [_]
    ;; ---TODO: Tidy clean up
    (.shutdown factory)
    ;; ---TODO: Wait for shutdown to complete?
    ))


(def socket
  (reify Startable
    (start [_ systems]
      (let [config (config/get-config (require-system Config systems) :socket)
            manager (require-system Manager systems)
            handler (handler manager)]
        (SocketDeployer. (netty-server (:port config) handler))))))
