(ns tservice.db.handler
  (:require
   [clojure.java.jdbc :as jdbc]
   [tservice.db.core :refer [*db*] :as db]
   [clojure.tools.logging :as log]
   [tservice.util :as util]
   [clojure.data.json :as json]
   [tservice.config :refer [get-workdir]]
   [tservice.lib.fs :as fs-lib]))

(defn- filter-query-map
  "Filter unqualified attribute or value.

   Change Log:
   1. Fix bug: PSQLException
      `filter-query-map` need to return nil when query-map is nil
  "
  [query-map]
  (let [query-map (into {} (filter (comp some? val) query-map))]
    (if (empty? query-map)
      nil
      query-map)))

(defn- page->offset
  "Tranform page to offset."
  [page per-page]
  (* (- page 1) per-page))

(defn- search-entities
  ([func-map] (search-entities func-map nil 1 10))
  ([func-map page] (search-entities func-map nil page 10))
  ([func-map page per-page] (search-entities func-map nil page per-page))
  ([func-map where-map page per-page]
   (let [page     (if (nil? page) 1 page)
         per-page (if (nil? per-page) 10 per-page)
         params   {:limit  per-page
                   :offset (page->offset page per-page)}
         params   (merge params (-> where-map
                                    (assoc :query-map (filter-query-map (:query-map where-map)))))
         metadata {:total    (:count ((:count-func func-map) params))
                   :page     page
                   :per_page per-page}]
     (log/info "Query db by: " params)
     (merge metadata {:data ((:query-func func-map) params)}))))

(defn- search-entity
  [func-map id]
  (let [data   (:data (search-entities func-map {:query-map {:id id}} 1 10))
        record (first data)]
    (if record
      record
      {})))

(defn- update-entity!
  [func id record]
  (when record
    (func {:updates record
           :id      id})))

;; --------------------- Report Record ---------------------
(def search-reports
  (partial
   search-entities
   {:query-func db/search-reports
    :count-func db/get-report-count}))

(def search-report
  (partial
   search-entity
   {:query-func db/search-reports
    :count-func db/get-report-count}))

(defn update-report! [id record]
  (update-entity! db/update-report! id record))

(defn delete-report! [id]
  (db/delete-report! {:id id}))

(defn create-report!
  ([record]
   (db/create-report! record))
  ([report-name description project-id app-name report-path report-type]
   (let [started-time (util/time->int (util/now))
         id (util/uuid)]
     (db/create-report! {:id id
                         :report_name report-name
                         :project_id project-id
                         :app_name app-name
                         :description description
                         :report_path report-path
                         :started_time started-time
                         :finished_time nil
                         :archived_time nil
                         :report_type report-type
                         :status "Started"
                         :log nil}))))

(defn sync-report [uuid]
  (let [report (search-report uuid)
        report-path (str (:report_path report))
        path (fs-lib/join-paths (get-workdir (:report_type report)) report-path "log")]
    (when (fs-lib/exists? path)
      (json/read-str (slurp path) :key-fn keyword))))