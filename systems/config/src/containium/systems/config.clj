;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.config
  "The system that handles the configuration."
  (:require [clojure.edn :as edn])
  (:import [java.io File]))


;;; The system protocols.

(defprotocol Config
  (get-config [this key]))


(defprotocol WritableConfig
  (set-config [this key value])
  (unset-config [this key]))


;;; Implementation using a file.

(defrecord FileConfig [^File file cache]
  Config
  (get-config [_ key]
    (let [last-modified (.lastModified file)]
      (when-not (= last-modified (::last-modified @cache))
        (reset! cache (assoc (-> file slurp edn/read-string) ::last-modified last-modified))))
    (get @cache key)))


(defn file-config
  [file]
  (FileConfig. file (atom nil)))


;;; Implementation using a hashmap.

(defrecord MapConfig [map]
  Config
  (get-config [_ key]
    (get @map key))
  WritableConfig
  (set-config [_ key value]
    (swap! map assoc key value))
  (unset-config [_ key]
    (swap! map dissoc key)))


(defn map-config
  [map]
  (MapConfig. (atom map)))
