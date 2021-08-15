(ns tservice.api.task
  (:require [tservice.db.handler :as db-handler]
            [tservice.util :as util]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [tservice.events :as events]
            [spec-tools.json-schema :as json-schema]
            [tservice.api.schema.task :refer [get-response-schema get-reponse-keys]]))

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
                                     :finished-time (util/time->int (util/now))}
                 (= percentage -1) {:status "Failed"
                                    :finished-time (util/time->int (util/now))}
                 :else {:percentage percentage})]
    (db-handler/update-task! task-id record)))

(defn create-task!
  [& {:keys [name description payload plugin-name
             plugin-type plugin-version response]
      :as task}]
  (apply db-handler/create-task! (apply concat task)))

;;; ------------------------------------------------ HTTP Metadata -------------------------------------------------
(defn- make-response
  "Convert the user's response into a standard response."
  [response-type response]
  (select-keys response
               (get-reponse-keys response-type)))

(defn- get-manifest-data
  []
  (-> (io/resource "manifest.json")
      slurp
      json/read-str))

;; Support :ReportPlugin, :DataPlugin, :StatPlugin
(defmulti make-plugin-metadata (fn [plugin-metadata] (:plugin-type plugin-metadata)))

(defmethod make-plugin-metadata :ReportPlugin
  make-report-plugin-route
  [{:keys [name params-schema handler plugin-type response-type summary response-schema]
    :or {summary ""
         response-schema (get-response-schema response-type)}}]
  {:route [(str "/report/" name)
           {:tags ["Report"]
            :post {:summary (or summary (format "%s Plugin %s." plugin-type name))
                   :parameters {:body params-schema}
                   :responses {201 {:body response-schema}}
                   :handler (fn [{{:keys [body]} :parameters}]
                              {:status 201
                               :body (make-response response-type (handler body))})}
            :get {:summary (format "A json schema for %s" name)
                  :parameters {}
                  :responses {200 {:body map?}}
                  :handler (fn [_]
                             {:status 200
                              :body (json-schema/transform params-schema)})}}]
   :manifest (get-manifest-data)})

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
        (log/warn (format "Failed to process ballgown2exp event. %s" (:topic report-plugin-event)) e)))))

(defn make-events-init
  "Generate event initializer."
  [event-name event-handler]
  ;; Must register event before generating event initializer.
  (register-report-plugin-event! event-name event-handler)
  (fn []
    (events/start-event-listener! (get-report-plugin-topics) report-plugin-channel (make-event-process))))
