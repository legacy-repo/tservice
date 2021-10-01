(ns tservice.api.task
  (:require [tservice.db.handler :as db-handler]
            [tservice.util :as util]
            [clojure.data.json :as json]
            [tservice.events :as events]
            [tservice.config :refer [which-database]]
            [clojure.tools.logging :as log]
            [clojure.string :as clj-str]
            [clojure.core.async :as async]
            [tservice.plugins.plugin-proxy :refer [get-plugin-env]]
            [spec-tools.json-schema :as json-schema]
            [tservice.lib.files :refer [get-relative-filepath]]
            [tservice.api.schema.task :refer [get-response-schema]]))

;; -------------------------------- Re-export --------------------------------
(defn publish-event!
  [topic event-item]
  (events/publish-event! (str topic "-convert") event-item))

;; ----------------------------- HTTP Metadata -------------------------------
(def ^:private response-identities
  {:data2files #{:log :files :total :response_type}
   :data2report #{:log :report :response_type}
   :data2data #{:log :data}})

(defn get-reponse-keys
  [response-type]
  (vec ((keyword response-type) response-identities)))

;;; Convert the user's response into a standard response(data2report/data2files)
(defmulti make-response (fn [response] (:response-type response)))

(defmethod make-response :data2report
  make-data2report-response
  [response]
  {:log (get-relative-filepath (:log response) :filemode false)
   :report (get-relative-filepath (:report response) :filemode false)
   :response_type :data2report})

(defmethod make-response :data2data
  make-data2data-response
  [response]
  {:log (get-relative-filepath (:log response) :filemode false)
   :data (:data response)
   :response_type :data2data})

(defmethod make-response :data2files
  make-data2report-response
  [response]
  {:log (get-relative-filepath (:log response) :filemode false)
   :files (map #(get-relative-filepath % :filemode false) (:files response))
   :response_type :data2files})

(defn- gen-response
  [{:keys [name summary plugin-type params-schema response-schema response-type handler]}]
  {:get   {:summary (format "A json schema for %s" name)
           :parameters {}
           :responses {200 {:body map?}}
           :handler (fn [_]
                      {:status 200
                       :body (json-schema/transform params-schema)})}
   :post {:summary (or summary (format "Create a(n) task for %s plugin %s." plugin-type name))
          :parameters {:body params-schema}
          :responses {201 {:body response-schema}}
          :handler (fn [{{:keys [body]} :parameters
                         {:as headers} :headers}]
                     (let [auth-users (get headers "x-auth-users")
                           owner (first (clj-str/split auth-users #","))]
                       {:status 201
                        :body (make-response (merge {:response-type (keyword response-type)}
                                                    (handler (merge body {:owner owner
                                                                          :plugin-env (get-plugin-env name)}))))}))}})

;; Support :ReportPlugin, :ToolPlugin, :DataPlugin, :StatPlugin
(defmulti make-plugin-metadata (fn [plugin-metadata] (:plugin-type plugin-metadata)))

(defmethod make-plugin-metadata :ReportPlugin
  make-report-plugin-route
  [{:keys [^String name params-schema handler plugin-type response-type
           ^String summary response-schema]
    :or {summary nil
         response-schema (get-response-schema response-type)}}]
  {:route [(str "/report/" name)
           (merge {:tags ["Report"]}
                  (gen-response {:name name
                                 :summary summary
                                 :handler handler
                                 :plugin-type plugin-type
                                 :response-schema response-schema
                                 :params-schema params-schema}))]})

(defmethod make-plugin-metadata :ToolPlugin
  make-tool-plugin-route
  [{:keys [^String name params-schema handler plugin-type response-type
           ^String summary response-schema]
    :or {summary nil
         response-schema (get-response-schema response-type)}}]
  {:route [(str "/tool/" name)
           (merge {:tags ["Tool"]}
                  (gen-response {:name name
                                 :summary summary
                                 :handler handler
                                 :plugin-type plugin-type
                                 :response-schema response-schema
                                 :params-schema params-schema}))]})

;;; ------------------------------------------------ Event Metadata -------------------------------------------------
(defonce ^:private report-plugin-events
  (atom {}))

(defn get-report-plugin-topics
  "The `Set` of event topics which are subscribed to for use in tracking."
  []
  (keys @report-plugin-events))

(defn- register-report-plugin-event!
  "Register an event into the report-plugin-events."
  [event-name event-handler]
  (reset! report-plugin-events
          (merge @report-plugin-events
                 (hash-map (keyword (str event-name "-convert")) event-handler))))

(def ^:private report-plugin-channel
  "Channel for receiving event report-plugin we want to subscribe to for report-plugin events."
  (async/chan))

(defn- make-event-process
  "Make a event process which handle processing for a single event notification received on the report-plugin-channel"
  []
  (fn [report-plugin-event]
    (log/debug "Make Event Process: " report-plugin-event @report-plugin-events)
    ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
    (try
      (when-let [{topic :topic object :item} report-plugin-event]
        ;; TODO: only if the definition changed??
        (if-let [event-handler (topic @report-plugin-events)]
          (event-handler object)
          (log/warn (format "No such event %s. (Events: %s)" @report-plugin-events report-plugin-event))))
      (catch Throwable e
        (log/warn (format "Failed to process %s event. %s" (:topic report-plugin-event) e))))))

(defn make-events-init
  "Generate event initializer."
  [event-name event-handler]
  ;; Must register event before generating event initializer.
  (register-report-plugin-event! event-name event-handler)
  (fn []
    (events/start-event-listener! (get-report-plugin-topics) report-plugin-channel (make-event-process))))


;; ----------------------------- Task Database -------------------------------
(defn update-process!
  "Update the task process with running status and percentage.
   
   Arguments:
     - task-id: An UUID string.
     - percentage: -1-100, -1 means Failed, 0-100 means the running percentage.

   Examples:
   (update-process! \"ff79d50e-6206-45fb-8bc3-6d2a0ec070ff\" \"Started\" 20) 
  "
  [^String task-id ^Integer percentage]
  (let [record (cond
                 (= percentage 100) {:status "Finished"
                                     :percentage 100
                                     :finished_time (util/time->int (util/now))}
                 (= percentage -1) {:status "Failed"
                                    :finished_time (util/time->int (util/now))}
                 :else {:percentage percentage})]
    (db-handler/update-task! task-id record)))

(defn create-task!
  [{:keys [id name description payload plugin-name
           plugin-type plugin-version response owner]
    :as task}]
  ;; TODO: response need to contain response-type field.
  (println "create-task!: " task)
  (let [updated-task (-> (assoc task :response (json/write-str (make-response response)))
                         (assoc :payload (json/write-str payload))
                         (dissoc :response-type))]
    (apply db-handler/create-task! (apply concat updated-task))))
