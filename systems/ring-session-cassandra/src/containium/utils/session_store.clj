;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.utils.session-store
  (:require [ring.middleware.session.store :refer (SessionStore) :as session]
            [taoensso.nippy :as nippy]))


;;; Helper methods.

(defn deep-merge
  "Like merge-with, but merges maps recursively."
  [& maps]
  (apply merge-with
         (fn m [& maps]
           (if (every? map? maps)
             (apply merge-with m maps)
             (last maps)))
         maps))


(defn deep-unmerge
  [m kss]
  (reduce (fn [[extracted leftover] ks]
            [(if-let [v (get-in m ks)] (assoc-in extracted ks v) extracted)
             (update-in leftover (butlast ks) dissoc (last ks))])
          [{} m]
          kss))


;;; Serializing session store.

(def default-plain
  [[:noir :session/longlived]
   [:containium/last-db-write]])


(defrecord SerializingSessionStore [session-store serialize deserialize options]
  SessionStore
  (read-session [_ key]
    (when-let [data (session/read-session session-store key)]
      (deep-merge data (when-let [to-deserialize (::serialized data)]
                         (deserialize to-deserialize)))))
  (write-session [_ key data]
    (let [[plain to-serialize] (deep-unmerge data (:plain options))
          serialized (assoc plain ::serialized (serialize to-serialize))]
      (session/write-session session-store key serialized)))
  (delete-session [this key]
    (session/delete-session session-store key)))


(defn mk-session-store
  ([session-store]
     (mk-session-store session-store nil))
  ([session-store {:keys [serializer deserializer]
                   :or {serializer nippy/freeze, deserializer nippy/thaw}
                   :as options}]
     (SerializingSessionStore. session-store (or serializer identity) (or deserializer identity)
                               (merge {:plain default-plain} options))))
