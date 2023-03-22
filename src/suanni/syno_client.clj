;; # Syno Client
;;
;; Connect to Synology Surveillance Station API and allow snapshots from
;; cameras.

(ns suanni.syno-client
  (:require [fudo-clojure.http.client :as client]
            [fudo-clojure.http.request :as req]
            [fudo-clojure.result :as result]
            [clojure.string :as str])
  (:import  java.net.InetAddress))

(defn pthru [o] (clojure.pprint/pprint o) o)

;; ## Protocols

;; ### BaseSynoClient
;;
;; In order to fully initialize the SynoClient, we need to be able to query the
;; Synology host to get path/version information and to authenticate. This base
;; client will have enough functionality to do that. Calling `initialize!` on
;; the base client will actually perform the queries necessary to initialize
;; full functionality.

(defprotocol IBaseSynoClient
  (get!        [_ req])
  (initialize! [_ username passwd]))

;; ### SynoClient
;;
;; The SynoClient is the client that is actually able to do things like list
;; cameras, detect motion, and take snapshots.

(defprotocol ISynoClient
  (disconnect!             [_])
  (camera-snapshot!        [_ camera-id])
  (get-cameras!            [_])
  (get-camera-by-location! [_ loc]))

(defprotocol ICamera
  (id             [_])
  (location       [_])
  (vendor         [_])
  (model          [_])
  (host           [_])
  (port           [_])
  (take-snapshot! [_]))

(defrecord Camera [conn data]
  ICamera
  (id             [_]    (:id data))
  (location       [_]    (-> data :newName keyword))
  (vendor         [_]    (-> data :vendor))
  (model          [_]    (-> data :model))
  (host           [_]    (-> data :ip))
  (port           [_]    (-> data :port))
  (take-snapshot! [self] (camera-snapshot! conn (id self))))

;; ## Helper Functions

(defn- get-hostname []
  (-> (InetAddress/getLocalHost)
      (.getHostName)))

;; ## Initialization queries
;;
;; Queries which are necessary to fully initialize the client. Used in the
;; `initialize!` method of BaseSynoClient.

;; ### get-api-info
;;
;; Grab information (maxVersion & path) about a specified API.

(defn- get-api-info! [conn api]
  (-> conn
      (get! (-> (req/base-request)
                (req/with-path "/webapi/query.cgi")
                (req/withQueryParams
                 {
                  :api     :SYNO.API.Info
                  :method  :Query
                  :version 1
                  :query   api
                  })))
      api))

;; ### get-auth-tokens!
;;
;; Authenticate with the provided account and passwd, and get the :sid and :did
;; to use for subsequent requests.

(defn- get-auth-tokens! [conn account passwd]
  (let [{:keys [maxVersion path]} (get-api-info! conn :SYNO.API.Auth)]
    (get! conn
          (-> (req/base-request)
              (req/with-path (format "/webapi/%s" path))
              (req/with-query-params
                {
                 :version      maxVersion
                 :session      :SurveillanceStation
                 :api          :SYNO.API.Auth
                 :method       :login
                 :account      account
                 :passwd       passwd
                 :format       :sid
                 :enable_device_token true
                 :device_name  (get-hostname)
                 })))))

(defn- perform-request! [http-client req]
  (result/bind (client/execute-request! http-client req)
               (fn [resp]
                 (if (:error resp)
                   (throw (ex-info "error performing request"
                                   {:request  req
                                    :error    (:error resp)
                                    :response resp}))
                   (cond (:data resp) (:data resp)
                         (:body resp) (:body resp))))))

;; ## Requests

(defn- make-list-cameras-request [{:keys [maxVersion path]}]
  (-> (req/base-request)
      (req/with-path (format "/webapi/%s" path))
      (req/withQueryParams
       {
        :version maxVersion
        :session :SurveillanceStation
        :api     :SYNO.SurveillanceStation.Camera
        :method  :List
        })))

(defn- make-snapshot-request [{:keys [maxVersion path]} camera-id]
  (-> (req/base-request)
      (req/with-path (format "/webapi/%s" path))
      (req/with-response-format :binary)
      (req/with-option :as :byte-array)
      (req/withQueryParams
       {
        :version maxVersion
        :session :SurveillanceStation
        :api     :SYNO.SurveillanceStation.Camera
        :method  :GetSnapshot
        :id      camera-id
        })))

(defn- make-logout-request [{:keys [maxVersion path]}]
  (-> (req/base-request)
      (req/with-path (format "/webapi/%s" path))
      (req/withQueryParams
       {
        :version maxVersion
        :session :SurveillanceStation
        :api     :SYNO.API.Auth
        :method  :logout
        })))

;; ## Actual client

(defn- find-first [pred lst]
  (loop [els lst]
    (if (pred (first els))
      (first els)
      (recur (rest els)))))

(defrecord SynoConnection [conn auth-info api-info verbose]
  IBaseSynoClient
  (get! [_ req]
    (get! conn
          (-> req
              (req/with-query-params
                {:device_id (:device_id auth-info)})
              (req/withQueryParams
               {:_sid (:sid auth-info)}))))
  (initialize! [_ _ _]
    (throw (ex-info "client already initialized!" {})))

  ISynoClient
  (disconnect! [self]
    (get! self (make-logout-request (:SYNO.API.Auth api-info))))
  (camera-snapshot! [self camera-id]
    (when verbose (println (str "fetching snapshot from camera " camera-id)))
    (-> self
        (get! (make-snapshot-request (:SYNO.SurveillanceStation.Camera api-info) camera-id))))
  (get-cameras! [self]
    (when verbose (println "fetching camera list"))
    (into []
          (map (partial ->Camera self))
          (-> (get! self (make-list-cameras-request (:SYNO.SurveillanceStation.Camera api-info)))
              :cameras)))
  (get-camera-by-location! [self loc]
    (find-first #(= loc (location %)) (get-cameras! self))))

(defn- initialize-connection! [conn auth-info verbose]
  (let [api-info (into {} (map (fn [api] [api (get-api-info! conn api)]))
                       [:SYNO.SurveillanceStation.Camera
                        :SYNO.SurveillanceStation.Camera.Event])]
    (->SynoConnection conn auth-info api-info verbose)))

(defn create-connection [& {:keys [host port verbose]
                            :or   {verbose true}}]
  (let [http-client (client/json-client)]
    (reify
      IBaseSynoClient
      (get! [_ req]
        (perform-request! http-client
                          (-> req
                              (req/with-host host)
                              (req/with-port port)
                              (req/with-option :insecure? true)
                              (req/as-get))))

      (initialize! [self account passwd]
        (let [auth-data (get-auth-tokens! self account passwd)]
          (initialize-connection! self auth-data verbose))))))

(defn with-conn [host port user passwd-file f]
  (let [passwd (-> passwd-file (slurp) (str/trim))
        conn   (-> (create-connection {:host host :port port})
                   (initialize! user passwd))]
    (try
      (f conn)
      (finally (disconnect! conn)))))
