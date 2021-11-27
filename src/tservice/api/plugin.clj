(ns tservice.api.plugin
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [tservice.events :as events]
            [tservice.util :as u]
            [daguerreo.core :as dag]
            [clojure.string :as clj-str]
            [clojure.data.json :as json]
            [camel-snake-kebab.core :as csk]
            [tservice.lib.files :refer [get-workdir]]
            [tservice.plugins.plugin-proxy :refer [get-plugin-context]]
            [daguerreo.helpers :as dag-helpers]
            [tservice.db.handler :as db-handler]))

;;; ------------------------------------------------ Event Metadata -------------------------------------------------
(defonce ^:private plugin-events
  (atom {}))

(defn get-plugin-topics
  "The `Set` of event topics which are subscribed to for use in tracking."
  []
  (keys @plugin-events))

(defn- register-plugin-event!
  "Register an event into the plugin-events."
  [event-name event-handler]
  (reset! plugin-events
          (merge @plugin-events
                 (hash-map (keyword (str event-name "-publish")) event-handler))))

(defn publish-event!
  [topic event-item]
  (events/publish-event! (str topic "-publish") event-item))

(def ^:private plugin-channel
  "Channel for receiving event we want to subscribe to for plugin events."
  (async/chan))

(defn- make-event-process
  "Make a event process which handle processing for a single event notification received on the report-plugin-channel"
  []
  (fn [plugin-event]
    (log/debug "Make Event Process: " plugin-event @plugin-events)
    ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
    (try
      (when-let [{topic :topic object :item} plugin-event]
        ;; TODO: only if the definition changed??
        (if-let [event-handler (topic @plugin-events)]
          (event-handler object)
          (log/warn (format "No such event %s. (Events: %s)" @plugin-events plugin-event))))
      (catch Throwable e
        (log/warn (format "Failed to process %s event. %s" (:topic plugin-event) e))))))

(defn make-events-init
  "Generate event initializer."
  [event-name event-handler]
  ;; Must register event before generating event initializer.
  (register-plugin-event! event-name event-handler)
  (fn []
    (events/start-event-listener! (get-plugin-topics) plugin-channel (make-event-process))))

;;; ------------------------------------------------ DAG Runner -------------------------------------------------
(defn update-status
  [id percentage]
  (let [record
        (cond
          (= percentage 100) {:status "Finished"
                              :percentage 100
                              :finished_time (u/time->int (u/now))}
          (= percentage -1) {:status "Failed"
                             :finished_time (u/time->int (u/now))}
          :else {:percentage percentage})]
    (db-handler/update-task! id record)))

(defn- get-val
  [coll pred-fn? key]
  (-> (filter #(pred-fn? %) coll)
      first
      (or {})
      (get key)))

(defn- fetch-percentage
  [event percentage-map]
  (let [state (:daguerreo.task/state event)
        name (:daguerreo.task/name event)
        percentage (get-val percentage-map
                            (fn [item] (= (:name item) name))
                            :percentage)]
    (cond
      (= state :task.state/completed) 100
      (= state :task.state/exception) -1
      (= state :task.state/failed) -1
      (= state :task.state/running) percentage
      (= state :task.state/timed-out) -1
      :else percentage)))

(def ^:private default-event-fn (partial dag-helpers/log-event println))

(defn async-update-status
  [update-fn id percentage-map & {:keys [event-fn]
                                  :or {event-fn default-event-fn}}]
  (let [c (async/chan 100)]
    (async/go-loop []
      (when-let [event (async/<! c)]
        (update-fn id (fetch-percentage event percentage-map))
        (event-fn event)
        (recur)))
    c))

(defn register-tasks
  "Register DAG tasks and bind the async channel what is used by updating status."
  [tasks ctx & {:keys [id timeout max-concurrency max-retries event-fn]
                :or {id (u/uuid)
                     timeout 3000
                     max-concurrency 3
                     max-retries 1
                     event-fn default-event-fn}}]
  (let [percentage-map (->> tasks
                            (map #(select-keys % [:percentage :name])))
        event-chan (async-update-status update-status id percentage-map event-fn)]
    (dag/run tasks {:ctx ctx :timeout timeout
                    :max-concurrency max-concurrency
                    :max-retries max-retries
                    :event-chan event-chan})))

(defn create-task!
  [{:keys [id name description payload plugin-name
           plugin-type plugin-version response owner]
    :as task}]
  ;; TODO: response need to contain response-type field.
  (let [updated-task (-> (assoc task :response (json/write-str response))
                         (assoc :payload (json/write-str payload))
                         (dissoc :response-type))]
    (apply db-handler/create-task! (apply concat updated-task))))

;;; ------------------------------------------------ HTTP Methods -------------------------------------------------
(defn get-owner-from-headers
  "The first user is treated as the owner."
  [headers]
  (let [auth-users (get headers "x-auth-users")
        owner (when auth-users (first (clj-str/split auth-users #",")))]
    owner))

(defn- make-request-context
  {:added "0.6.0"}
  [plugin-name plugin-type owner]
  (let [uuid (u/uuid)]
    {:owner owner
     :uuid uuid
     :workdir (if owner
                (get-workdir :username owner :uuid uuid)
                (get-workdir :uuid uuid))
     :plugin-context (merge (get-plugin-context plugin-name)
                            {:plugin-type (name plugin-type)})}))

(defn- make-handler
  [plugin-name plugin-type handler status-code]
  (fn [{{:keys [query path body]} :parameters
        {:as headers} :headers}]
    (let [owner (get-owner-from-headers headers)]
      {:status status-code
       :body (handler
              (merge {:path path :query query :body body :headers headers}
                     (make-request-context plugin-name plugin-type owner)))})))

(defmulti make-method
  {:added "0.7.0"}
  (fn [context] (:method-type context)))

(defmethod make-method :get
  make-get-method
  [{:keys [plugin-name plugin-type endpoint summary query-schema path-schema response-schema handler]}]
  (hash-map (keyword endpoint)
            {:get {:summary (or summary (format "A json schema for %s" plugin-name))
                   :parameters {:query query-schema
                                :path path-schema}
                   :responses {200 {:body (or response-schema map?)}}
                   :handler (make-handler plugin-name plugin-type handler 200)}}))

(defmethod make-method :post
  make-post-method
  [{:keys [plugin-name plugin-type endpoint summary
           body-schema query-schema path-schema
           response-schema
           handler]}]
  (hash-map (keyword endpoint)
            {:post {:summary (or summary (format "Create a(n) task for %s plugin %s." plugin-type plugin-name))
                    :parameters {:body body-schema
                                 :query query-schema
                                 :path path-schema}
                    :responses {201 {:body (or response-schema map?)}}
                    :handler (make-handler plugin-name plugin-type handler 201)}}))

(defmethod make-method :put
  make-put-method
  [{:keys [plugin-name plugin-type endpoint summary
           body-schema query-schema path-schema
           response-schema
           handler]}]
  (hash-map (keyword endpoint)
            {:put {:summary (or summary (format "Update a(n) task for %s plugin %s." plugin-type plugin-name))
                   :parameters {:body body-schema
                                :path path-schema
                                :query query-schema}
                   :responses {200 {:body (or response-schema map?)}}
                   :handler (make-handler plugin-name plugin-type handler 200)}}))

(defmethod make-method :delete
  make-delete-method
  [{:keys [plugin-name plugin-type endpoint summary
           path-schema body-schema query-schema
           response-schema
           handler]}]
  (hash-map (keyword endpoint)
            {:delete {:summary (or summary (format "Delete a(n) task for %s plugin %s." plugin-type plugin-name))
                      :parameters {:path path-schema :body body-schema :query query-schema}
                      :responses {200 {:body (or response-schema map?)}}
                      :handler (make-handler plugin-name plugin-type handler 200)}}))

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
                                    (symbol k))  ;; (name :test/:sample_id) will return :sample_id, but we need test/:sample_id
                               (merge {:tags [(get-tag plugin-type)]}
                                      v)]) endpoints)}))
