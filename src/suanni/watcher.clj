(ns suanni.watcher
  (:require [milquetoast.client :as mqtt]
            [clojure.core.async :as async :refer [go-loop <! >!]])
  (:import java.time.Instant))

(defn start-watching! [& {:keys [mqtt-client event-chan sensor-location-map verbose]
                          :or   {verbose false}}]
  (letfn [(exposes-occupancy? [d]
            (some (fn [exp] (-> exp :property (= "occupancy")))
                  (-> d :definition :exposes)))
          (assoc-location [sensor]
            (assoc sensor :location (get sensor-location-map
                                         (:ieee_address sensor))))]
    (let [motion-sensors (->> (mqtt/get! mqtt-client "zigbee2mqtt/bridge/devices")
                              :payload
                              (filter exposes-occupancy?)
                              (map assoc-location))]
      (doseq [sensor motion-sensors]
        (let [sensor-topic (format "zigbee2mqtt/%s" (:ieee_address sensor))]
          (when verbose (println (format "listening to channel: %s" sensor-topic)))
          (let [sensor-chan (mqtt/subscribe! mqtt-client sensor-topic)]
            (go-loop [evt (<! sensor-chan)]
              (when evt
                (>! event-chan
                    {
                     :type      :motion-detected-sensor
                     :sensor    sensor
                     :location  (:location sensor)
                     :sensor-id (:ieee_address sensor)
                     :time      (Instant/now)
                     })
                (recur (<! sensor-chan)))))))))
  true)
