(ns onyx.coordinator.distributed
  (:require [clojure.core.async :refer [>!! <!! chan]]
            [com.stuartsierra.component :as component]          
            [taoensso.timbre :as timbre]
            [ring.adapter.jetty :as jetty]
            [onyx.system :refer [onyx-coordinator]]))

(defmulti dispatch-request
  (fn [coordinator request] (:uri request)))

(defmethod dispatch-request "/submit-job"
  [coordinator request]
  (let [data (read-string (slurp (:body request)))]
    (>!! (:planning-ch-head (:coordinator coordinator)) data)))

(defmethod dispatch-request "/register-peer"
  [coordinator request]
  (let [data (read-string (slurp (:body request)))]
    (>!! (:born-peer-ch-head (:coordinator coordinator)) data)))

(defn handler [coordinator]
  (fn [request]
    (dispatch-request coordinator request)
    {:status 200
     :headers {"content-type" "text/text"}
     :body "ok"}))

(defrecord CoordinatorServer [opts]
  component/Lifecycle
  (start [component]
    (taoensso.timbre/info "Starting Coordinator Netty server")

    (let [coordinator (component/start (onyx-coordinator opts))]
      (assoc component
        :coordinator coordinator
        :server (jetty/run-jetty (handler coordinator) {:port (:onyx-port opts)
                                                        :join? false}))))
  
  (stop [component]
    (taoensso.timbre/info "Stopping Coordinator Netty server")

    (component/stop (:coordinator component))
    (.stop (:server component))

    component))

(defn coordinator-server [opts]
  (map->CoordinatorServer {:opts opts}))

(defn -main [& args]
  (let [id (str (java.util.UUID/randomUUID))
        opts {:datomic-uri (str "datomic:mem://" id)
              :hornetq-host "localhost"
              :hornetq-port 5445
              :zk-addr "127.0.0.1:2181"
              :onyx-id id
              :revoke-delay 2000
              :onyx-port 9950}
        server (component/start (CoordinatorServer. opts))]
    (try
      @(future (<!! (chan)))
      (finally
       (component/stop server)))))
