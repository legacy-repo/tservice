(ns tservice.api.task
  "DEPRECATED: `tservice.api.task` will be deprecated from v0.7.0, please use `tservice.api.plugin`."
  (:require [tservice.db.handler :as db-handler]
            [tservice.util :as util]
            [clojure.data.json :as json]
            [tservice.events :as events]
            [clojure.tools.logging :as log]
            [clojure.string :as clj-str]
            [clojure.core.async :as async]
            [camel-snake-kebab.core :as csk]
            [tservice.plugins.plugin-proxy :refer [get-plugin-context]]
            [spec-tools.json-schema :as json-schema]
            [tservice.lib.files :refer [get-relative-filepath get-workdir]]
            [tservice.api.schema.task :refer [get-response-schema]]))

;; -------------------------------- Re-export --------------------------------
(defn publish-event!
  [topic event-item]
  (events/publish-event! (str topic "-convert") event-item))

(defn get-owner-from-headers
  "The first user is treated as the owner."
  [headers]
  (let [auth-users (get headers "x-auth-users")
        owner (when auth-users (first (clj-str/split auth-users #",")))]
    owner))

;; ----------------------------- HTTP Response -------------------------------
;;; Convert the user's response into a standard response(data2report/data2files/data2charts/data2data)
(defmulti make-response (fn [response] (or (:response-type response) (:response_type response))))

(defmethod make-response :data2report
  make-data2report-response
  [response]
  {:log (get-relative-filepath (:log response) :filemode false)
   :report (get-relative-filepath (:report response) :filemode false)
   :response_type "data2report"
   :task_id (or (:task_id response) (:task-id response))})

(defmethod make-response :data2data
  make-data2data-response
  [response]
  {:log (get-relative-filepath (:log response) :filemode false)
   :data (:data response)
   :response_type "data2data"
   :task_id (or (:task_id response) (:task-id response))})

(defmethod make-response :data2files
  make-data2files-response
  [response]
  {:log (get-relative-filepath (:log response) :filemode false)
   :files (map #(get-relative-filepath % :filemode false) (:files response))
   :response_type "data2files"
   :task_id (or (:task_id response) (:task-id response))})

(defmethod make-response :data2charts
  make-data2charts-response
  [response]
  {:log (get-relative-filepath (:log response) :filemode false)
   :charts (map #(get-relative-filepath % :filemode false) (:charts response))
   :results (map #(get-relative-filepath % :filemode false) (:results response))
   :response_type "data2charts"
   :task_id (or (:task_id response) (:task-id response))})

;; ----------------------------- HTTP Methods/General -------------------------------
(defn- make-request-context
  {:added "0.6.0"}
  [plugin-name plugin-type owner]
  (let [uuid (util/uuid)]
    {:owner owner
     :uuid uuid
     :workdir (if owner
                (get-workdir :username owner :uuid uuid)
                (get-workdir :uuid uuid))
     :plugin-context (merge (get-plugin-context plugin-name)
                            {:plugin-type (name plugin-type)})}))

(defmulti make-method
  {:added "0.6.0"}
  (fn [context] (:method-type context)))

(defmethod make-method :get
  make-get-method
  [{:keys [plugin-name plugin-type endpoint summary query-schema path-schema response-schema handler]}]
  (hash-map (keyword endpoint)
            {:get {:summary (or summary (format "A json schema for %s" plugin-name))
                   :parameters {:query query-schema
                                :path path-schema}
                   :responses {200 {:body (or response-schema map?)}}
                   :handler (fn [{{:keys [query path]} :parameters
                                  {:as headers} :headers}]
                              (let [owner (get-owner-from-headers headers)]
                                {:status 200
                                 :body (handler
                                        (merge query path
                                               (make-request-context plugin-name plugin-type owner)))}))}}))

(defmethod make-method :post
  make-post-method
  [{:keys [plugin-name plugin-type endpoint summary enable-schema? body-schema response-type response-schema handler]
    :or {enable-schema? true}}]
  (merge
   (if (and (= endpoint plugin-name) enable-schema?)
     (hash-map (keyword (str endpoint "-schema"))
               {:get {:summary (format "A json schema for %s" plugin-name)
                      :parameters {}
                      :responses {200 {:body map?}}
                      :handler (fn [_]
                                 {:status 200
                                  :body (json-schema/transform body-schema)})}})
     {})
   (hash-map (keyword endpoint)
             (merge
              ;; Provide the metadata of a plugin, defaultly.
              (if (= endpoint plugin-name)
                {:get {:summary (format "The metadata of the %s plugin" plugin-name)
                       :parameters {}
                       :responses {200 {:body map?}}
                       :handler (fn [_]
                                  (let [plugin-context (get-plugin-context plugin-name)]
                                    {:status 200
                                     :body (:info (:plugin-info plugin-context))}))}}
                {})
              {:post {:summary (or summary (format "Create a(n) task for %s plugin %s." plugin-type plugin-name))
                      :parameters {:body body-schema}
                      :responses {201 {:body (or response-schema (get-response-schema response-type) map?)}}
                      :handler (fn [{{:keys [body]} :parameters
                                     {:as headers} :headers}]
                                 (let [owner (get-owner-from-headers headers)]
                                   {:status 201
                                    :body (make-response
                                           (merge {:response-type (keyword response-type)}
                                                  (handler (merge body
                                                                  (make-request-context plugin-name plugin-type owner)))))}))}}))))

(defmethod make-method :put
  make-put-method
  [{:keys [plugin-name plugin-type endpoint summary body-schema path-schema response-schema handler]}]
  (hash-map (keyword endpoint)
            {:put {:summary (or summary (format "Update a(n) task for %s plugin %s." plugin-type plugin-name))
                   :parameters {:body body-schema
                                :path path-schema}
                   :responses {200 {:body (or response-schema map?)}}
                   :handler (fn [{{:keys [body path]} :parameters
                                  {:as headers} :headers}]
                              (let [owner (get-owner-from-headers headers)]
                                {:status 200
                                 :body (handler (merge body path
                                                       (make-request-context plugin-name plugin-type owner)))}))}}))

(defmethod make-method :delete
  make-delete-method
  [{:keys [plugin-name plugin-type endpoint summary path-schema response-schema handler]}]
  (hash-map (keyword endpoint)
            {:delete {:summary (or summary (format "Delete a(n) task for %s plugin %s." plugin-type plugin-name))
                      :parameters {:path path-schema}
                      :responses {200 {:body (or response-schema (get-response-schema plugin-type) map?)}}
                      :handler (fn [{{:keys [path]} :parameters
                                     {:as headers} :headers}]
                                 (let [owner (get-owner-from-headers headers)]
                                   {:status 200
                                    :body (handler (merge path
                                                          (make-request-context plugin-name plugin-type owner)))}))}}))
(defn- ->methods
  "Convert the forms to the http methods.
   
   Form is a hash map which contains several elements for building http method. And different http method
   need different elements. such as get method need to have these keys: method-type, endpoint, summary,
   query-schema, path-schema, response-schema and handler."
  {:added "0.6.0"}
  [plugin-name plugin-type & forms]
  (map (fn [form] (make-method (merge {:plugin-name plugin-name
                                       :endpoint (or (:endpoint form) plugin-name)
                                       :plugin-type plugin-type} form))) forms))

(defn- merge-map-array
  "Merge a list of hash-map. 
   It will merge the value which have the same key into a vector, but the value must be a collection.
   
   Such as: [{:get {:a 1}} {:get {:b 2}}] -> {:get {:a 1 :b 2}}"
  {:added "0.6.0"}
  [array]
  (apply merge-with into array))

(defn- get-endpoint-prefix
  "Generate endpoint prefix from plugin-type.
   
   Such as: :DataPlugin --> /data"
  {:added "0.6.0"}
  [plugin-type]
  (str "/" (first (clj-str/split
                   (csk/->kebab-case (name plugin-type)) #"-"))))

(defn- get-tag
  "Generate swargger tag from plugin-type.
   
   Such as: :DataPlugin --> Data"
  {:added "0.6.0"}
  [plugin-type]
  (clj-str/capitalize
   (first (clj-str/split
           (csk/->kebab-case (name plugin-type)) #"-"))))

(defn make-routes
  "Make several forms into routes for plugin.
   
   Examples:
   (make-routes \"corrplot\" :ChartPlugin
                {:method-type :get
                 :endpoint \"report\"
                 :summary \"\"
                 :query-schema {}
                 :path-schema {}
                 :response-schema {}
                 :handler (fn [context] context)}
                {:method-type :post
                 :endpoint \"report\"
                 :enable-schema? true
                 :summary \"\"
                 :body-schema {}
                 :response-type :data2files
                 :response-schema {}
                 :handler (fn [context] context)}
                {:method-type :put
                 :endpoint \"report\"
                 :summary \"\"
                 :body-schema {}
                 :path-schema {}
                 :response-schema {}
                 :handler (fn [context] context)}
                {:method-type :get
                 :endpoint \"report\"
                 :summary \"\"
                 :path-schema {}
                 :response-schema {}
                 :handler (fn [context] context)})"
  {:added "0.6.0"}
  [plugin-name plugin-type & forms]
  (let [methods (apply ->methods plugin-name plugin-type forms)
        endpoints (merge-map-array methods)]
    {:routes (map (fn [[k v]] [(str (get-endpoint-prefix plugin-type)
                                    "/"
                                    (name k))
                               (merge {:tags [(get-tag plugin-type)]}
                                      v)]) endpoints)}))

;; ----------------------------- HTTP Methods/Special -------------------------------
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
                     (let [owner (get-owner-from-headers headers)]
                       {:status 201
                        :body (make-response
                               (merge {:response-type (keyword response-type)}
                                      (handler (merge body
                                                      (make-request-context name plugin-type owner)))))}))}})

;; Support :ReportPlugin, :ToolPlugin, :ChartPlugin, :DataPlugin, :StatPlugin
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

(defmethod make-plugin-metadata :ChartPlugin
  make-chart-plugin-route
  [{:keys [^String name params-schema handler plugin-type response-type
           ^String summary response-schema]
    :or {summary nil
         response-schema (get-response-schema response-type)}}]
  {:route [(str "/chart/" name)
           (merge {:tags ["Chart"]}
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
  (let [updated-task (-> (assoc task :response (json/write-str (make-response response)))
                         (assoc :payload (json/write-str payload))
                         (dissoc :response-type))]
    (apply db-handler/create-task! (apply concat updated-task))))
