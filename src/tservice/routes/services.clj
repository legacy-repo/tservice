(ns tservice.routes.services
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [reitit.coercion.spec :as spec-coercion]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.swagger :as swagger]
            [ring.util.http-response :refer [ok not-found]]
            [tservice.config :refer [get-workdir]]
            [tservice.lib.fs :as fs-lib]
            [tservice.middleware.exception :as exception]
            [tservice.middleware.formats :as formats]
            [tservice.util :as u]
            [tservice.db.handler :as db-handler]
            [tservice.plugin :as plugin]
            [tservice.routes.specs :as specs]
            [tservice.routes.report :as report-route]))

(defn service-routes []
  ["/api"
   {:coercion spec-coercion/coercion
    :muuntaja formats/instance
    :swagger {:id ::api}
    :middleware [;; query-params & form-params
                 parameters/parameters-middleware
                 ;; content-negotiation
                 muuntaja/format-negotiate-middleware
                 ;; encoding response body
                 muuntaja/format-response-middleware
                 ;; exception handling
                 exception/exception-middleware
                 ;; decoding request body
                 muuntaja/format-request-middleware
                 ;; coercing response bodys
                 coercion/coerce-response-middleware
                 ;; coercing request parameters
                 coercion/coerce-request-middleware
                 ;; multipart
                 multipart/multipart-middleware]}

   ;; swagger documentation
   ["" {:no-doc true
        :swagger {:info {:title "API Service for Tservice"
                         :description "https://cljdoc.org/d/metosin/reitit"}}}

    ["/swagger.json"
     {:get (swagger/create-swagger-handler)}]

    ["/api-docs/*"
     {:get (swagger-ui/create-swagger-ui-handler
            {:url "/api/swagger.json"
             :config {:validator-url nil}})}]]

   ["/ping"
    {:tags ["Utility"]
     :get (constantly (ok {:message "pong"}))}]

   ["/metadata"
    {:tags ["Utility"]
     :get {:summary "Get all metadata"
           :parameters {:query specs/report-params-query}
           :responses  {200 {:body {:total    nat-int?
                                    :page     pos-int?
                                    :per_page pos-int?
                                    :data     any?}}}
           :handler (fn [{{{:keys [page per_page app_name report_type status project_id]} :query} :parameters}]
                      (let [query-map {:app_name     app_name
                                       :status       status
                                       :report_type  report_type
                                       :project_id   project_id}]
                        (log/debug "page: " page, "per-page: " per_page, "query-map: " query-map)
                        (ok (db-handler/search-reports {:query-map query-map}
                                                       page
                                                       per_page))))}}]

   ["/metadata/:uuid"
    {:tags ["Utility"]
     :get {:summary "Get the metadata of a specified report"
           :parameters {:path specs/uuid-spec}
           :responses  {200 {:body any?}}
           :handler (fn [{{{:keys [uuid]} :path} :parameters}]
                      (log/debug "Get report metadata: " uuid)
                      (ok (db-handler/search-report uuid)))}}]

   ["/status/:uuid"
    {:tags ["Utility"]
     :get {:summary "Check the status of a specified task."
           :parameters {:path specs/uuid-spec}
           :responses  {200 {:body {:status string?
                                    :msg string?}}}
           :handler (fn [{{{:keys [uuid]} :path} :parameters}]
                      (let [log (db-handler/sync-report uuid)]
                        (if (nil? log)
                          (not-found {:msg "No such uuid."})
                          (ok log))))}}]

   ["/manifest"
    {:tags ["Utility"]
     :get {:summary "Get the manifest data."
           :parameters {}
           :responses {200 {:body any?}}
           :handler (fn [_]
                      (let [manifest (plugin/get-manifest)]
                        (if manifest
                          {:body manifest
                           :status 200}
                          {:body {:msg "No manifest file."
                                  :status 404}})))}}]
   ["/upload"
    {:tags ["File Management"]
     :post {:summary "Uploading File."
            :parameters {:multipart {:files (s/or :file multipart/temp-file-part :files (s/coll-of multipart/temp-file-part))}}
            :handler (fn [{{{:keys [files]} :multipart} :parameters}]
                       (let [uuid (u/uuid)
                             files (if (map? files) [files] files)]
                         (doseq [file files]
                           (let [filename (:filename file)
                                 tempfile (:tempfile file)
                                 to-dir (fs-lib/join-paths (get-workdir)
                                                           "upload"
                                                           uuid)
                                 to-path (fs-lib/join-paths to-dir filename)]
                             (log/debug "Upload file: " filename)
                             (fs-lib/create-directories! to-dir)
                             (fs-lib/copy tempfile to-path)))
                         {:status 201
                          :body {:upload_path (str "file://./" (fs-lib/join-paths "upload" uuid))
                                 :files (map #(:filename %) files)
                                 :total (count files)}}))}}]
   report-route/report])
