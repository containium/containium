;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.utils.async
  "Utils for core.async library."
  (:require [clojure.core.async :as async :refer (<!)]))


(defn console-channel
  "Creates a go loop that consumes from a channel and prints the
  contents of to *out*, until the channel closes. Returns the channel
  consumed. Optionally, one can supply the channal to comsume."
  ([prefix]
     (console-channel prefix (async/chan)))
  ([prefix channel]
     (async/go-loop []
       (when-let [msg (<! channel)]
         (println (str "[" prefix "]") msg)
         (recur)))
     channel))
