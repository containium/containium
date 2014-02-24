;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.logging
  "Namespace for logging messages."
  (:require [clojure.core.async :as async]
            [clojure.java.io :refer (writer)]
            [containium.utils.async :as async-util])
  (:import [java.io PrintStream]))


;;; Posting messages logic.

(def ^{:private true :doc "Channel to post console messages on."} consolec (async/chan))

(def ^{:doc "Mult to tap onto for receiving console messages."} consolem (async/mult consolec))
;;---TODO Make sure no problems arise when a tapped channels fails to untap and read.

(defn log-command
  "Log a messages that is only of interest for the command issuer."
  [commandc msg & rest]
  (async/put! commandc (str msg " " (apply str (interpose " " rest)) \newline)))


(defn log-console
  "Log a messages that is of interest for every connected console.
  Printlns have the same effect."
  [msg & rest]
  (async/put! consolec (str msg " " (apply str (interpose " " rest)) \newline)))


(defn log-all
  "Log a messages that is of interest for both every console as well
  as the command issuer."
  [commandc msg & rest]
  (apply log-command commandc msg rest)
  (apply log-console msg rest))


;;; Redirect STDOUT messages to console channel.

(defonce stdout (System/out))

(defn stdout-logger
  "Use this logger to print the messages on the created or given
  channel to STDOUT. Returns the channel."
  ([]
     (stdout-logger (async/chan)))
  ([chan]
     (async/go-loop []
       (when-let [msg (<! chan)]
         (.print stdout msg)
         (recur)))
     chan))


(when (= stdout (System/out))
  (let [stringified (async/map> (fn [[b off len]] (String. b off len)) consolec)
        ;;---TODO above assumes b=bytes, off=number and len=number. Correct?
        [out _] (async-util/forwarding-outputstream stringified)]
    (System/setOut (PrintStream. out))
    (alter-var-root #'clojure.core/*out* (constantly (writer out)))))


;;; Have console messages printed to STDOUT.

(async/tap consolem (stdout-logger))
