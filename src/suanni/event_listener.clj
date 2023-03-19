(ns suanni.event-listener
  (:require [reitit.ring :as ring]
            [clojure.core.async :as async :refer [>!!]]
            [clojure.data.json :as json]
            [ring.adapter.jetty :refer [run-jetty]]
            [suanni.stoppable :refer [IStoppable]]))

;; Maybe someday we'll actually know the camera...
(defn- motion-event
  ([]       (motion-event nil))
  ([camera]
   {:type     :motion-detected
    :location camera}))

(defn- handle-motion-event [verbose event-chan]
  (fn [req]
    (let [{:keys [camera]} (-> req :body (slurp) (json/read-str {:key-fn keyword}))]
      (when verbose (println (str "motion reported on camera " camera)))
      (>!! event-chan (motion-event (keyword camera))))
    { :status 200 :body "OK" }))

(defn create-app [verbose event-chan]
  (ring/ring-handler
   (ring/router ["/event/motion" {:post {:handler (handle-motion-event verbose event-chan)}}])
   (ring/create-default-handler)))

(defn- run-server! [{:keys [host port verbose event-chan]
                     :or   {verbose false}}]
  (when verbose (println (str "listening for events on " host ":" port)))
  (run-jetty (create-app verbose event-chan) {:host host :port port :join? false}))

(defrecord EventListenerServer [server event-chan]
  IStoppable
  (stop! [_]
    (.stop server)
    (async/close! event-chan)))

(defn start! [& {:keys [event-chan] :as args}]
  (->EventListenerServer (run-server! args) event-chan))
