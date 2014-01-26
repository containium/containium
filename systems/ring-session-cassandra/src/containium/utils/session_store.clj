;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.utils.session-store
  (:require [ring.middleware.session.store :refer (SessionStore) :as session]
            [taoensso.nippy :as nippy]))


(defrecord SerializingSessionStore [session-store serialize deserialize]
  SessionStore
  (read-session [_ key]
    (when-let [data (::serialized (session/read-session session-store key))]
      (deserialize data)))
  (write-session [_ key data]
    (session/write-session session-store key {::serialized (serialize data)}))
  (delete-session [this key]
    (session/delete-session session-store key)))


(defn mk-session-store
  ([session-store]
     (mk-session-store session-store nil))
  ([session-store {:keys [serializer deserializer]
                                 :or {serializer nippy/freeze deserializer nippy/thaw}
                                 :as options}]
     (SerializingSessionStore. session-store (or serializer identity) (or deserializer identity))))
