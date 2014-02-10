(ns onyx.peer.transform
  (:require [clojure.core.async :refer [chan go alts!! close! >!] :as async]
            [onyx.peer.storage-api :as api]
            [onyx.extensions :as extensions]))

(defn read-batch [{:keys [batch-size timeout] :as task} queue consumers]
  (let [consumer-chs (take (count consumers) (repeatedly #(chan 1)))]
    (doseq [[c consumer-ch] (map vector consumers consumer-chs)]
      (go (loop []
            (when-let [m (.receive c)]
              (extensions/ack-message queue m)
              (>! consumer-ch m)
              (recur)))))
    (let [chs (conj consumer-chs (async/timeout timeout))
          rets (doall (repeatedly batch-size #(first (alts!! chs))))]
      (doseq [ch chs] (close! ch))
      (filter identity rets))))

(defn decompress-tx [queue message]
  (let [segment (extensions/read-message queue message)]
    (read-string segment)))

(defn apply-fn [task segment]
  (let [user-ns (symbol (name (namespace (:onyx/fn task))))
        user-fn (symbol (name (:onyx/fn task)))]
    ((ns-resolve user-ns user-fn) segment)))

(defn compress-tx [segment]
  (pr-str segment))

(defn write-batch [queue session producer msgs]
  (doseq [msg msgs]
    (extensions/produce-message queue producer msg)))

(defmethod api/munge-read-batch :default
  [{:keys [task queue session ingress-queues batch-size timeout] :as event}]
  (let [consumers (map (partial extensions/create-consumer queue session) ingress-queues)
        batch (read-batch task queue consumers batch-size timeout)]
    (assoc event :batch batch :consumers consumers)))

(defmethod api/munge-decompress-tx :default
  [{:keys [queue batch] :as event}]
  (let [decompressed-msgs (map (partial decompress-tx queue) batch)]
    (assoc event :decompressed decompressed-msgs)))

(defmethod api/munge-apply-fn :default
  [{:keys [decompressed task catalog] :as event}]
  (let [task (first (filter (fn [entry] (= (:onyx/name entry) task)) catalog))
        results (map (partial apply-fn task) decompressed)]
    (assoc event :results results)))

(defmethod api/munge-compress-tx :default
  [{:keys [results] :as event}]
  (let [compressed-msgs (map compress-tx results)]
    (assoc event :compressed compressed-msgs)))

(defmethod api/munge-write-batch :default
  [{:keys [queue queue-name session compressed] :as event}]
  (let [producer (extensions/create-producer queue session queue-name)
        batch (write-batch queue session producer compressed)]
    (assoc event :producer producer)))
