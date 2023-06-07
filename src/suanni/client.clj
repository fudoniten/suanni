(ns suanni.client
  (:require [suanni.syno-client :as syno]
            [suanni.event-listener :as listen]
            [suanni.stoppable :refer [IStoppable stop!]]
            [milquetoast.client :as mqtt]
            [objectifier-client.core :as obj]
            [clojure.core.async :as async :refer [<! >! go-loop <!! timeout]]
            [clojure.string :as str])
  (:import  java.time.Instant
            java.lang.Exception))

;; Let's see:
;;
;; - Take in a syno-client and an objectifier client.
;;
;; - Start an event listener, and wait for notifications to come in.
;;
;; - Take snapshots from all cameras via the syno client, and pass them to the
;;   objectifier.
;;
;; - If anything is detected, send a notification to the callback.


(defn- exception? [obj]
  (instance? Exception obj))

(defprotocol ISuanNiServer
  (object-channel [_]))

(defrecord SuanNiServer [event-chan image-chan obj-chan listener]
  IStoppable
  (stop! [_]
    (stop! listener))
  ISuanNiServer
  (object-channel [_] obj-chan))

(defn- retry-attempt [verbose f]
  (let [wrap-attempt (fn []
                       (try [true (f)]
                            (catch RuntimeException e
                              (do (when verbose
                                    (println (format "exception: %s"
                                                     (.toString e))))
                                  [false e]))))
        max-wait     (* 5 60 1000)] ;; wait at most 5 minutes
    (loop [[success? result] (wrap-attempt)
           wait-ms 1000
           n       0]
      (if success?
        result
        (do (when verbose
              (println (format "attempt failed, sleeping %s ms" wait-ms)))
            (if (< n 10)
              (do
                (<!! (timeout wait-ms))
                (recur (wrap-attempt) (min (* wait-ms 1.25) max-wait) (+ n 1)))
              (throw result)))))))

(defn start!
  [& {:keys [listen-host
             listen-port
             syno-client
             obj-client
             mqtt-client
             mqtt-topic
             verbose]}]
  (let [event-chan (async/chan 5)
        image-chan (async/chan 10)
        obj-chan   (async/chan 10)
        mqtt-chan  (mqtt/open-channel! mqtt-client
                                       mqtt-topic
                                       :buffer-size 10)
        listener   (listen/start! :host       listen-host
                                  :port       listen-port
                                  :event-chan event-chan
                                  :verbose    verbose)]
    (go-loop [event (<! event-chan)]
      (if (nil? event)
        (when verbose
          (println "stopping event listener")
          (async/close! image-chan))
        (when (-> event :type (= :motion-detected))
          (let [cam (syno/get-camera-by-location! syno-client (:location event))]
            (try
              (>! image-chan
                  {
                   :location  (syno/location cam)
                   :camera-id (syno/id cam)
                   :snapshot  (syno/take-snapshot! cam)
                   :time      (Instant/now)
                   :camera    cam
                   })
              (catch Exception e
                (println (.toString e)))))
          (recur (<! event-chan)))))
    (go-loop [image-data (<! image-chan)]
      (if (nil? image-data)
        (when verbose
          (println "stopping image listener")
          (async/close! obj-chan))
        (let [{:keys [location camera-id snapshot time]} image-data
              summary (retry-attempt verbose
                                     #(obj/get-summary! obj-client snapshot))]
          (if (exception? summary)
            (println (.toString summary))
            (do
              (when verbose
                (println (str "detected "
                              (count (:objects summary))
                              " objects: "
                              (->> summary
                                   :objects
                                   (keys)
                                   (map name)
                                   (str/join " "))))
                (println (str "highlights: " (:output summary))))
              (when (> (count (:objects summary)) 0)
                (>! obj-chan
                    {
                     :location      location
                     :camera-id     camera-id
                     :detect-time   time
                     :snapshot      snapshot
                     :objects       (:objects summary)
                     :detection-url (:output summary)
                     }))))
          (recur (<! image-chan)))))
    (go-loop [detection-event (<! obj-chan)]
      (if (nil? detection-event)
        (when verbose
          (println "stopping object listener")
          (async/close! mqtt-chan))
        (do
          (try
            (>! mqtt-chan
                {:type      :detection-event
                 :time      (Instant/now)
                 :detection
                 (select-keys detection-event
                              [:location
                               :camera-id
                               :detect-time
                               :objects
                               :detection-url])})
            (catch Exception e
              (println (.toString e))))
          (recur (<! obj-chan)))))
    (->SuanNiServer event-chan image-chan obj-chan listener)))
