;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.deployer.socket
  (:require [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :as config :refer (Config)]
            [containium.deployer :refer :all]
            [containium.systems.logging :as logging :refer (SystemLogger LogWriter refer-logging)]
            [containium.commands :as commands]
            [clojure.java.io :refer (as-file)]
            [clojure.stacktrace :refer (print-cause-trace)])
  (:import [org.jboss.netty.channel SimpleChannelHandler ChannelHandlerContext MessageEvent Channel
            ChannelFutureListener ChannelFactory ChannelFuture ChannelStateEvent ExceptionEvent]
           [org.jboss.netty.channel.group ChannelGroup DefaultChannelGroup]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory]
           [org.jboss.netty.bootstrap ServerBootstrap]
           [org.jboss.netty.handler.codec.string StringDecoder StringEncoder]
           [java.util.concurrent Executors]
           [java.net InetSocketAddress]
           [containium.modules Response]))
(refer-logging)


;;; The message handler for Netty server.

(defn handler
  [systems logger ^ChannelGroup channels]
  (let [show-ex (atom true)]
    (proxy [SimpleChannelHandler] []
      (channelOpen [^ChannelHandlerContext ctx ^ChannelStateEvent evt]
        (.add channels (.getChannel evt)))

      (messageReceived [^ChannelHandlerContext ctx ^MessageEvent evt]
        (let [^String msg (.getMessage evt)
              ^Channel chan (.getChannel evt)
              [command & args] (commands/parse-quoted msg)]
          (when command
            ;; TODO Add argument to done-fn for success?
            ;; TODO Close via future? (.addListener ^ChannelFuture fut ChannelFutureListener/CLOSE)
            (commands/handle-command command args systems
                                     (logging/command-logger logger chan command
                                                             (fn []  (.close chan)))))))
      (exceptionCaught [^ChannelHandlerContext ctx ^ExceptionEvent evt]
        (when @show-ex
          (reset! show-ex false)
          (let [^Throwable cause (.getCause evt)]
            (error logger "Error in IO of Netty Socket deployer -" (.getMessage cause))
            (debug logger (with-out-str (print-cause-trace cause)))))))))


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
                           ^ChannelGroup channels config logger]
  Deployer
  (bootstrap-modules [this])

  Stoppable
  (stop [_]
    (info logger "Stopping socket-based module deployer...")
    (info logger "Closing server socket...")
    (.close server-channel)
    (loop [i (:wait2finish-secs config)]
      (when (and (< 0 i) (not (.isEmpty channels)))
        (when (= 0 (mod i 5)) (info logger "Waiting for socket deployer actions to finish."))
        (Thread/sleep 1000)
        (recur (dec i))))
    (when-not (.isEmpty channels)
      (warn logger "Still socket deployer actions running after" (:wait2finish-secs config)
            "seconds. Closing the connections now."))
    (.. channels close (await (* 1000 (:wait2close-secs config))))
    (when-not (.isEmpty channels)
      (warn logger "Still open socket deployer connections after" (:wait2close-secs config)
            "seconds. Killing them now."))
    (.releaseExternalResources server-factory)
    (.shutdown server-factory)
    (info logger "Socked-based module deployer stopped.")))


(defn- extend-channel
  [logger]
  (extend-type Channel
    LogWriter
    (write-line [this line]
      (.write this (str line \newline)))))


(def socket
  (reify Startable
    (start [_ systems]
      (let [config (config/get-config (require-system Config systems) :socket)
            logger (require-system SystemLogger systems)
            _ (info logger "Starting socket-based module deployer, using config:" config)
            channels (DefaultChannelGroup. "containium management")
            handler (handler systems logger channels)
            {:keys [server-factory server-channel]} (netty-server (:port config) handler)]
        (extend-channel logger)
        (info logger "Socket-based module deployer started.")
        (SocketDeployer. server-factory server-channel channels config logger)))))
