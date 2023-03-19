(ns suanni.cli
  (:require [suanni.syno-client :as syno]
            [suanni.stoppable :refer [stop!]]
            [milquetoast.client :as mqtt]
            [objectifier-client.core :as obj]
            [suanni.client :as client]
            [clojure.set :as set]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.core.async :as async :refer [<!! >!!]])
  (:gen-class))

(def cli-opts
  [["-v" "--verbose" "Provide verbose output."]
   ["-H" "--hostname HOSTNAME" "Hostname on which to listen for incoming events."]
   ["-p" "--port PORT" "Port on which to listen for incoming events."
    :parse-fn #(Integer/parseInt %)]

   [nil "--synology-host HOSTNAME" "Hostname of Synology server."]
   [nil "--synology-port PORT" "Port on which to connect to the Synology server."
    :parse-fn #(Integer/parseInt %)]
   [nil "--synology-user USER" "User as which to connect to Synology server."]
   [nil "--synology-password-file PASSWD_FILE" "File containing password for Synology user."]

   [nil "--objectifier-host HOSTNAME" "Hostname of Objectifier server."]
   [nil "--objectifier-port PORT" "Port on which to connect to the Objectifier server."
    :parse-fn #(Integer/parseInt %)
    :default  80]

   [nil "--mqtt-host HOSTNAME" "Hostname of MQTT server."]
   [nil "--mqtt-port PORT" "Port on which to connect to the MQTT server."
    :parse-fn #(Integer/parseInt %)]
   [nil "--mqtt-user USER" "User as which to connect to MQTT server."]
   [nil "--mqtt-password-file PASSWD_FILE" "File containing password for MQTT user."]
   [nil "--mqtt-topic TOPIC" "MQTT topic to which events should be published."]])

(defn- msg-quit [status msg]
  (println msg)
  (System/exit status))

(defn- usage
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: suanni-server [opts]"
                                  ""
                                  "Options:"
                                  summary])
                         (str/join \newline))))

(defn- parse-opts [args required cli-opts]
  (let [{:keys [options]} :as result (cli/parse-opts args cli-opts)
        missing (set/difference required (-> options (keys) (set)))
        missing-errors (map #(format "missing required parameter: %s" %)
                            missing)]
    (update result :errors concat missing-errors)))

(defn -main [& args]
  (let [required-args #{:hostname :port
                        :synology-host :synology-port :synology-user :synology-password-file
                        :objectifier-host
                        :mqtt-host :mqtt-port :mqtt-user :mqtt-password-file :mqtt-topic}
        {:keys [options _ errors summary]} (parse-opts args required-args cli-opts)]
    (when (seq errors) (msg-quit 1 (usage summary errors)))
    (let [{:keys [hostname port
                  synology-host synology-port synology-user synology-password-file
                  objectifier-host objectifier-port
                  mqtt-host mqtt-port mqtt-user mqtt-password-file mqtt-topic
                  verbose]} options
          catch-shutdown (async/chan)
          syno-client (-> (syno/create-connection :host    synology-host
                                                  :port    synology-port
                                                  :verbose verbose)
                          (syno/initialize! synology-user (-> synology-password-file
                                                              (slurp)
                                                              (str/trim))))
          obj-client (obj/define-connection
                       :host    objectifier-host
                       :port    objectifier-port
                       :verbose verbose)
          mqtt-client (mqtt/connect-json! :host     mqtt-host
                                          :port     mqtt-port
                                          :username mqtt-user
                                          :password (-> mqtt-password-file
                                                        (slurp)
                                                        (str/trim))
                                          :verbose  verbose)
          suanni-client (client/start! :listen-host hostname
                                       :listen-port port
                                       :syno-client syno-client
                                       :obj-client  obj-client
                                       :mqtt-client mqtt-client
                                       :mqtt-topic  mqtt-topic
                                       :verbose     verbose)]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (>!! catch-shutdown true))))
      (<!! catch-shutdown)
      (stop! suanni-client)
      (System/exit 0))))
