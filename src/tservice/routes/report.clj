(ns tservice.routes.report
  (:require
   [ring.util.http-response :refer [ok created no-content]]
   [tservice.db.handler :as db-handler]
   [tservice.routes.specs :as specs]
   [clojure.tools.logging :as log]
   [tservice.util :as util]))

(def report
  [""
   {:swagger {:tags ["Report Management"]}}

   ["/reports"
    {:get  {:summary    "Get reports."
            :parameters {:query specs/report-params-query}
            :responses  {200 {:body {:total    nat-int?
                                     :page     pos-int?
                                     :per_page pos-int?
                                     :data     any?}}}
            :handler    (fn [{{{:keys [page per_page project_id status report_type]} :query} :parameters}]
                          (let [query-map {:project_id  project_id
                                           :status      status
                                           :report_type report_type}]
                            (log/debug "page: " page, "per-page: " per_page, "query-map: " query-map)
                            (ok (db-handler/search-reports {:query-map query-map}
                                                           page
                                                           per_page))))}

     :post {:summary    "Create an report."
            :parameters {:body specs/report-body}
            :responses  {201 {:body {:message specs/report-id}}}
            :handler    (fn [{{:keys [body]} :parameters}]
                          (let [body (util/merge-diff-map body {:id (util/uuid)
                                                                :archived_time nil
                                                                :checked_time nil
                                                                :finished_time nil
                                                                :log nil
                                                                :report_path nil
                                                                :script nil
                                                                :report_id nil
                                                                :project_id nil})]
                            (log/debug "Create an report: " body)
                            (created (str "/reports/" (:id body))
                                     {:message (db-handler/create-report! body)})))}}]

   ["/reports/:id"
    {:get    {:summary    "Get a report by id."
              :parameters {:path specs/report-id}
              :responses  {200 {:body map?}}
              :handler    (fn [{{{:keys [id]} :path} :parameters}]
                            (log/debug "Get report: " id)
                            (ok (db-handler/search-report id)))}

     :delete {:summary    "Delete a report."
              :parameters {:path specs/report-id}
              :responses  {204 nil}
              :handler    (fn [{{{:keys [id]} :path} :parameters}]
                            (db-handler/delete-report! id)
                            (no-content))}}]])