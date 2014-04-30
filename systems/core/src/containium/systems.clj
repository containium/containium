;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems
  "Logic for starting and stopping systems."
  (:require [containium.exceptions :as ex])
  (:import [clojure.lang Compiler]))


(defprotocol Startable
  "Implement this protocol, if you want to have the system started by
  `with-systems` below. This way, one gains access to the previously
  started systems. It is the returned value of the the `start`
  function that is considered to be the actual system."
  (start [this systems]))


(defprotocol Stoppable
  "Implement this protocol, if you want to have the system stopped by
  the `with-systems` function below. Stoppables are stopped in reverse
  order in which they were started."
  (stop [this]))


(defn- start-systems
  [system-components]
  (loop [to-start system-components
         started nil]
    (if-let [[name system] (first to-start)]
      (let [result (if (satisfies? Startable system)
                      (try
                        (start system (into {} started))
                        (catch Throwable ex
                          (ex/exit-when-fatal ex)
                          ex))
                      system)]
        (if-not (instance? Throwable result)
          (recur (rest to-start) (conj started [name result]))
          (do (println "Exception while starting system component" name "-" result)
              (.printStackTrace ^Throwable result)
              [started result])))
      [started nil])))


(defn- stop-systems
  [started-components]
  (doseq [[name system] started-components]
    (when (satisfies? Stoppable system)
      (try
        (stop system)
        (catch Throwable ex
          (ex/exit-when-fatal ex)
          (println "Exception while stopping system component" name "-" ex)
          (.printStackTrace ex))))))


(defmacro with-systems*
  [symbol system-components body]
  `(let [[started# ex#] (#'start-systems ~system-components)
         ex# (if ex#
               ex#
               (try
                 (let [~symbol (into {} started#)] ~@body nil)
                 (catch Throwable ex#
                   (ex/exit-when-fatal ex#)
                   (println "Exception while running `with-systems` body. Stopping systems.")
                   (.printStackTrace ex#)
                   ex#)))]
     (#'stop-systems started#)
     (when ex# (throw ex#))))


(defmacro with-systems
  "This macro expands into starting the systems for the containium. The
  `symbol` argument is what symbol the eventual systems map is bound
  to, which can be used in the body. The `system-components` argument
  is a vector of alternating name-system pairs. Each pair is an
  identifier (likely a keyword) for the root system and a reference to
  the system implementation and/or Startable. If it is a Startable,
  the return value of the `start` function is considered to be the
  system. It is also that system on which `stop` is called, if it
  satisfies Stoppable."
  [symbol system-components & body]
  (assert (even? (count system-components))
          "System components vector needs to have an even number of forms.")
  `(with-systems* ~symbol (partition 2 ~system-components) ~body))


(defn require-system
  "Given a protocol, returns the single system in the systems map that
  satisfies that protocol. If no such system exists, or multiple
  exist, an exception is thrown."
  [protocol systems]
  (let [impls (filter (comp (partial satisfies? protocol) val) systems)]
    (if (= 1 (count impls))
      (val (first impls))
      (throw (Exception. (str (if (seq impls) "Multiple" "No")
                              " systems found satisfying protocol " (:on protocol)))))))


(defn protocol-forwarder "DEPRECATED" [protocol] identity)


(defn forward-protocol
  "Extends the given object or class with the given protocol. The
  protocol implementation merely forwards the functions. This is used
  for using objects via the protocol, which already satisfy that
  protocol, but in another Clojure runtime. If the given object or
  class already implements or satisfies the protocol, this is a noop."
  [object-or-class protocol]
  (assert object-or-class "object or class cannot be nil")
  (assert (#'clojure.core/protocol? protocol) "protocol argument must point to a protocol")
  (let [class (if (class? object-or-class) object-or-class (class object-or-class))]
    (try
      (extend class protocol
              (into {} (for [sig (:sigs protocol)
                             :let [{:keys [name arglists]} (val sig)]]
                         [(keyword name)
                          (eval `(fn ~@(for [arglist arglists]
                                         `(~arglist
                                            ; Systems currently run in the root Classloader context
                                            ; but this could be changed to isolated systems using
                                            ; a different ns-root binding:
                                            (binding [*ns-root* (.getRawRoot #'*ns-root*)]
                                              (~(symbol (str "." (Compiler/munge (str name))))
                                                         ~@arglist))))))])))
      (catch IllegalArgumentException iae))))


(defn forward-systems
  "This function should be called from within an isolated Clojure
  runtime (boxure), so the system protocols work on the values in the
  system map."
  [systems]
  (let [keyword->protocol {:cassandra 'containium.systems.cassandra/Cassandra
                           :elastic 'containium.systems.elasticsearch/Elastic
                           :kafka 'containium.systems.kafka/Kafka
                           :session-store 'ring.middleware.session.store/SessionStore}]
    (doseq [[keyword system] systems
            :let [protocol (keyword->protocol keyword)]
            :when protocol]
      (require (symbol (namespace protocol)))
      (forward-protocol system (eval protocol)))))
