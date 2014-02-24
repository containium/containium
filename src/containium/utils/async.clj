;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.utils.async
  "Utils for core.async library."
  (:require [clojure.core.async :as async :refer (<!)])
  (:import [java.io OutputStream FilterOutputStream]))


;;---TODO Replace these with a better performing Java/gen-class implementation.

(defn eavesdrop-outputstream
  "Eavesdrop an OutputStream. The given or created channel receives
   the following tuples: [bytes offset length], [bytes nil nil] or
   [int nil nil]. Returns a tuple with the proxied OutputStream and
   the channel."
  ([^OutputStream os]
     (eavesdrop-outputstream os (async/chan)))
  ([^OutputStream os chan]
     [(proxy [FilterOutputStream] [os]
         (write [b off len]
           (async/put! chan [b off len])
           (if (and off len)
             (.write os b off len)
             (.write os b))))
      chan]))


(defn forwarding-outputstream
  "Create a forwarding OutputStream. The given or created channel
   receives the following tuples: [bytes offset length], [bytes nil
   nil] or [int nil nil]. Returns a tuple with the OutputStream and
   the channel."
  ([]
     (forwarding-outputstream (async/chan)))
  ([chan]
     [(proxy [OutputStream] []
        (write [b off len]
          (async/put! chan [b off len])))
      chan]))
