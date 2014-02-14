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
            ChannelFutureListener ChannelFactory ChannelFuture ChannelStateEvent]
           [org.jboss.netty.channel.group ChannelGroup DefaultChannelGroup]
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
                    (println "Failed to write" msg "to netty channel:"
                             (.getMessage ^Exception ex)))))
         (if fut
           (.addListener ^ChannelFuture fut ChannelFutureListener/CLOSE)
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
  [manager ^ChannelGroup channels]
  (proxy [SimpleChannelHandler] []
    (channelOpen [^ChannelHandlerContext ctx ^ChannelStateEvent evt]
      (.add channels (.getChannel evt)))

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
                (do (println "Received activate action from socket deployer for module" name)
                    (modules/activate! manager name
                                       (when path (modules/module-descriptor (as-file path)))
                                       commc))
                (close-with-error commc "Missing name argument." nil)))

            "deactivate"
            (let [[name] args]
              (if name
                (do (println "Received deactivate action from socket deployer for module" name)
                    (modules/deactivate! manager name commc))
                (close-with-error commc "Missing name argument." nil)))

            "kill"
            (let [[name] args]
              (if name
                (do (println "Received kill action from socket deployer for module" name)
                    (modules/kill! manager name commc))
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
    {:server-channel (.bind bootstrap (InetSocketAddress. port))
     :server-factory channel-factory}))


(defrecord SocketDeployer [^ChannelFactory server-factory ^Channel server-channel
                           ^ChannelGroup channels config]
  Deployer
  (bootstrap-modules [this])

  Stoppable
  (stop [_]
    (println "Stopping socket-based module deployer...")
    (println "Closing server socket...")
    (.close server-channel)
    (loop [i (:wait2finish-secs config)]
      (when (and (< 0 i) (not (.isEmpty channels)))
        (when (= 0 (mod i 5)) (println "Waiting for socket deployer actions to finish."))
        (Thread/sleep 1000)
        (recur (dec i))))
    (when-not (.isEmpty channels)
      (println "Still socket deployer actions running after" (:wait2finish-secs config)
               "seconds. Closing the connections now."))
    (.. channels close (await (* 1000 (:wait2close-secs config))))
    (when-not (.isEmpty channels)
      (println "Still open socket deployer connections after" (:wait2close-secs config)
               "seconds. Killing them now."))
    (.releaseExternalResources server-factory)
    (.shutdown server-factory)
    (println "Socked-based module deployer stopped.")))


(def socket
  (reify Startable
    (start [_ systems]
      (let [config (config/get-config (require-system Config systems) :socket)
            _ (println "Starting socket-based module deployer, using config:" config)
            manager (require-system Manager systems)
            channels (DefaultChannelGroup. "containium management")
            handler (handler manager channels)
            {:keys [server-factory server-channel]} (netty-server (:port config) handler)]
        (println "Socket-based module deployer started.")
        (SocketDeployer. server-factory server-channel channels config)))))
