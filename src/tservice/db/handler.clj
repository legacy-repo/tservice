(ns tservice.db.handler
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :refer [function?]]
   [tservice.db.core :as db]
   [clojure.tools.logging :as log]
   [tservice.util :as util]))

;; -------------------------------- Spec --------------------------------
;; Where Map
(s/def ::query-map map?)
(s/def ::where-clause coll?)
(s/def ::where-map (s/keys :opt-un [::query-map ::where-clause]))

;; Function Map
(s/def ::query-func function?)
(s/def ::count-func function?)
(s/def ::func-map (s/keys :req-un [::query-func ::count-func]))

;; Searching Results
(s/def ::page number?)
(s/def ::per-page number?)
(s/def ::total number?)
(s/def ::offset number?)
(s/def ::id (or string? number?))
(s/def ::data any?)
(s/def ::search-results (s/keys :req-un [::total ::page ::per-page ::data]))

;; Task Record
(s/def ::name string?)
(s/def ::description string?)
(s/def ::payload map?)
(s/def ::plugin-name string?)
(s/def ::plugin-version string?)
(s/def ::plugin-type string?)
(s/def ::response map?)
(s/def ::started-time integer?)
(s/def ::finished-time integer?)
(s/def ::status string?)
(s/def ::percentage integer?)
(s/def ::task (s/keys :req-un [::name ::plugin-name ::plugin-version ::plugin-type]
                      :opt-un [::description ::payload ::response ::finished-time ::started-time ::status ::percentage]))

;; ----------------------------- Function -------------------------------
(defn- filter-query-map
  "Filter unqualified attribute or value.

   Change Log:
   1. Fix bug: PSQLException
      `filter-query-map` need to return nil when query-map is nil
  "
  [query-map]
  {:pre [(s/valid? ::where-map query-map)]
   :post [(or (s/valid? ::where-map %) (s/valid? nil? %))]}
  (let [query-map (into {} (filter (comp some? val) query-map))]
    (if (empty? query-map)
      nil
      query-map)))

(defn- page->offset
  "Tranform page to offset."
  [page per-page]
  {:pre [(s/valid? ::page page)
         (s/valid? ::per-page per-page)]
   :post [(s/valid? ::offset %)]}
  (* (- page 1) per-page))

(defn- make-query-map
  [where-map]
  {:pre [(s/valid? ::where-map where-map)]
   :post [(s/valid? ::where-map where-map)]}
  (-> where-map
      (assoc :query-map (filter-query-map (:query-map where-map)))
      (assoc :where-clause (:where-clause where-map))))

(defn- search-entities
  "Query database using query-map, page and per-page.
   
   Arguments:
     func-map[map]: query and count function from the specified database.
     page[number]: which page?
     per-page[number]: how many items in one page?
     where-map[map]: query map.

   Examples:
     (search-entities {:query-func db/search-tasks
                       :count-func db/count-tasks}
                      {:query-map {:id \"XXXX\"}}
                      1 10)
  "
  ([func-map] (search-entities func-map nil 1 10))
  ([func-map page] (search-entities func-map nil page 10))
  ([func-map page per-page] (search-entities func-map nil page per-page))
  ([func-map where-map page per-page]
   {:pre [(s/valid? ::func-map func-map)
          (s/valid? ::where-map where-map)
          (s/valid? ::page page)
          (s/valid? ::per-page per-page)]
    :post [(s/valid? ::search-results %)]}
   (let [page     (if (nil? page) 1 page)
         per-page (if (nil? per-page) 10 per-page)
         params   {:limit  per-page
                   :offset (page->offset page per-page)}
         params   (merge params (make-query-map where-map))
         metadata {:total    (:count ((:count-func func-map) params))
                   :page     page
                   :per_page per-page}]
     (log/info "Query db by: " params)
     (merge metadata {:data ((:query-func func-map) params)}))))

(defn- search-entity
  [func-map id]
  {:pre [(s/valid? ::func-map func-map)
         (s/valid? ::id id)]
   :post [(s/valid? map? %)]}
  (let [data   (:data (search-entities func-map {:query-map {:id id}} 1 10))
        record (first data)]
    (if record
      record
      {})))

(defn- update-entity!
  "Update record using the specified function."
  [func id record]
  {:pre [(s/valid? function? func)
         (s/valid? ::id id)
         (s/valid? map? record)]
   :post [(s/valid? (s/nilable map?) %)]}
  (when record
    (func {:updates record
           :id      id})))

;; --------------------- Task Record ---------------------
(def search-tasks
  (partial
   search-entities
   {:query-func db/search-tasks
    :count-func db/count-tasks}))

(def search-task
  (partial
   search-entity
   {:query-func db/search-tasks
    :count-func db/count-tasks}))

(defn get-task-count
  [where-map]
  (db/count-tasks (make-query-map where-map)))

(defn update-task!
  [id record]
  {:pre [(s/valid? ::id id)
         (s/valid? map? record)]}
  (update-entity! db/update-task! id record))

(defn delete-task!
  [id]
  {:pre [(s/valid? ::id id)]}
  (db/delete-task! {:id id}))

(defn create-task!
  [& {:keys [name
             description
             payload
             plugin-name
             plugin-version
             plugin-type
             response
             started-time
             finished-time
             status
             percentage]
      :or {description ""
           payload {}
           response {}
           started-time (util/time->int (util/now))
           finished-time nil
           status "Started"
           percentage 0}
      :as task}]
  {:pre [(s/valid? ::task task)]}
  (let [id (util/uuid)]
    (db/create-task! {:id id
                      :name name
                      :description description
                      :payload payload
                      :plugin_name plugin-name
                      :plugin_version plugin-version
                      :plugin_type plugin-type
                      :response response
                      :started_time started-time
                      :finished_time finished-time
                      :status status
                      :percentage percentage})))
