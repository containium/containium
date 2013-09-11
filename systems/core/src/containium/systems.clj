;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems
  "Logic for starting and stopping systems.")


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


(defmacro with-systems*
  [symbol system-components body]
  (if-let [[name system] (first system-components)]
    `(try
       (let [system# ~system
             system# (if (satisfies? Startable system#) (start system# ~symbol) system#)
             ~symbol (assoc ~symbol ~name system#)]
         (with-systems* ~symbol ~(rest system-components) ~body)
         (when (satisfies? Stoppable system#)
           (try
             (stop system#)
             (catch Throwable ex#
               (println "Exception while stopping system component" ~name "-" ex#)
               (.printStackTrace ex#)))))
       (catch Throwable ex#
         (println "Exception while starting system component" ~name "-" ex#)
         (.printStackTrace ex#)))
    `(try
      ~@body
      (catch Throwable ex#
        (println "Exception while running `with-systems` body. Stopping systems.")
        (.printStackTrace ex#)))))


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
  `(let [~symbol {}]
     (with-systems* ~symbol ~(partition 2 system-components) ~body)))
