(ns tservice.routes.fs
  (:require
   [ring.util.http-response :refer [ok created no-content bad-request not-found]]
   [tservice.routes.fs-spec :as fs-spec]
   [clojure.tools.logging :as log]
   [clj-filesystem.core :as fs]
   [tservice.config :refer [default-fs-service filter-by-whitelist filter-by-blacklist]]
   [clojure.string :as str])
  (:import [java.io File]))

(def fs-service
  [""
   {:swagger {:tags ["File System Service"]}}

   ["/fs-services"
    {:get {:summary "Get all file services"
           :parameters {}
           :responses {200 {:body {:services coll?
                                   :default_service string?}}}
           :handler (fn [_] (ok {:services (filter some? (map (fn [[key value]] (when value key)) @fs/services))
                                 :default_service (default-fs-service)}))}}]

   ["/services/:service/buckets"
    {:get  {:summary    "Get buckets"
            :parameters {:path fs-spec/bucket-spec}
            :responses  {200 {:body {:data any?}}}
            :handler    (fn [{{{:keys [service]} :path} :parameters}]
                          (ok {:data (-> (fs/with-conn service (fs/list-buckets))
                                         (filter-by-whitelist service)
                                         (filter-by-blacklist service))}))}

     :post {:summary    "Create a bucket."
            :parameters {:body {:name string?}}
            :responses  {201 {:body {:name string?}}}
            :handler    (fn [{{{:keys [service]} :path
                               {:keys [name]} :body} :parameters}]
                          (created (str "/buckets/" name)
                                   {:name (fs/with-conn service (fs/make-bucket! name))}))}}]

   ["/services/:service/buckets/:name"
    {:get    {:summary    "Get the objects of a bucket."
              :parameters {:path  fs-spec/bucket-name-spec
                           :query fs-spec/bucket-params-query}
              :responses  {200 {:body {:total    nat-int?
                                       :page     pos-int?
                                       :per_page pos-int?
                                       :data     any?}}}
              :handler    (fn [{{{:keys [service name]}                 :path
                                 {:keys [page per_page prefix]} :query} :parameters}]
                            (let [objects  (fs/with-conn service (fs/list-objects name prefix false))
                                  page     (if (nil? page) 1 page)
                                  per_page (if (nil? per_page) 10 per_page)
                                  prefix   (if (nil? prefix) "" prefix)]
                              (log/debug "page: " page, "per-page: " per_page)
                              (ok {:data     (fs/format-objects name
                                                                (->> (drop (* (- page 1) per_page) objects)
                                                                     (take per_page)))
                                   :page     page
                                   :per_page per_page
                                   :total    (count objects)
                                   :bucket   name
                                   :location (str (fs/get-protocol) name "/" (str/replace prefix #"[^\/]+$" ""))})))}
     :post   {:summary    "Create an directory in a bucket."
              :parameters {:path  fs-spec/bucket-name-spec
                           :query {:key string?}}
              :responses  {201 {:body {:bucket string?
                                       :name   string?}}}
              :handler    (fn [{{{:keys [key]}  :query
                                 {:keys [service name]} :path} :parameters}]
                            (let [key      (str (str/replace key #"\/$" "") "/")
                                  tempfile (.getPath (File/createTempFile "tempfile" "txt"))
                                  object   (fs/with-conn service (fs/put-object! name key tempfile))]
                              (created (str "/buckets/" name "/object-meta" "?key=" key) object)))}
     :delete {:summary    "Delete the bucket."
              :parameters {:path fs-spec/bucket-name-spec}
              :responses  {204 {:body any?}
                           400 {:body {:message string?}}}
              :handler    (fn [{{{:keys [service name]} :path} :parameters}]
                            (try
                              (fs/with-conn service (fs/remove-bucket! name))
                              (no-content)
                              (catch Exception e
                                (bad-request {:message "The bucket you tried to delete is not empty."}))))}}]

   ["/services/:service/buckets/:name/object"
    {:get    {:summary    "Get the download url of object."
              :parameters {:path  fs-spec/bucket-name-spec
                           :query {:key string?}}
              :responses  {200 {:body {:download_url string?}}}
              :handler    (fn [{{{:keys [key]}  :query
                                 {:keys [service name]} :path} :parameters}]
                            (ok {:download_url (fs/with-conn service (fs/get-download-url name key))}))}
     :post   {:summary    "Create an upload url for an object."
              :parameters {:path  fs-spec/bucket-name-spec
                           :query {:key string?}}
              :responses  {201 {:body {:upload_url string?}}}
              :handler    (fn [{{{:keys [key]}  :query
                                 {:keys [service name]} :path} :parameters}]
                            (let [upload-url (fs/with-conn service (fs/get-upload-url name key))]
                              (created upload-url {:upload_url upload-url})))}
     :delete {:summary    "Delete an object."
              :parameters {:path  fs-spec/bucket-name-spec
                           :query {:key string?}}
              :responses  {204 {:body any?}}
              :handler    (fn [{{{:keys [key]}  :query
                                 {:keys [service name]} :path} :parameters}]
                            (fs/with-conn service (fs/remove-object! name key))
                            (no-content))}}]

   ["/services/:service/buckets/:name/object-meta"
    {:get {:summary    "Get the meta of an object."
           :parameters {:path  fs-spec/bucket-name-spec
                        :query {:key string?}}
           :responses  {200 {:body {:meta any?}}
                        404 {:body {:message string?}}}
           :handler    (fn [{{{:keys [key]}  :query
                              {:keys [service name]} :path} :parameters}]
                         (try
                           (ok {:meta (fs/with-conn service (fs/get-object-meta name key))})
                           (catch Exception e
                             (not-found {:message "Object does not exist"}))))}}]])
