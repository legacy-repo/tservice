(ns tservice.routes.task
  (:require
   [ring.util.http-response :refer [ok created no-content]]
   [tservice.db.handler :as db-handler]
   [tservice.routes.specs :as specs]
   [clojure.tools.logging :as log]))

(def task
  [""
   {:swagger {:tags ["Task Management"]}}

   ["/tasks"
    {:get  {:summary    "Get tasks."
            :parameters {:query specs/task-params-query}
            :responses  {200 {:body {:total    nat-int?
                                     :page     pos-int?
                                     :per_page pos-int?
                                     :data     any?}}}
            :handler    (fn [{{{:keys [page per_page status plugin_type]} :query} :parameters}]
                          (let [query-map {:status      status
                                           :plugin_type plugin_type}]
                            (log/debug "page: " page, "per-page: " per_page, "query-map: " query-map)
                            (ok (db-handler/search-tasks {:query-map query-map}
                                                         page
                                                         per_page))))}

     :post {:summary    "Create an task."
            :parameters {:body specs/task-body}
            :responses  {201 {:body {:message specs/task-id}}}
            :handler    (fn [{{:keys [body]} :parameters}]
                          (log/debug "Create an task: " body)
                          (created (str "/tasks/" (:id body))
                                   {:message (db-handler/create-task! body)}))}}]

   ["/tasks/:id"
    {:get    {:summary    "Get a task by id."
              :parameters {:path specs/task-id}
              :responses  {200 {:body map?}}
              :handler    (fn [{{{:keys [id]} :path} :parameters}]
                            (log/debug "Get task: " id)
                            (ok (db-handler/search-task id)))}

     :delete {:summary    "Delete a task."
              :parameters {:path specs/task-id}
              :responses  {204 nil}
              :handler    (fn [{{{:keys [id]} :path} :parameters}]
                            (db-handler/delete-task! id)
                            (no-content))}}]])
