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
            [ring.util.http-response :refer [ok]]
            [tservice.lib.files :refer [get-workdir get-relative-filepath]]
            [tservice.lib.fs :as fs-lib]
            [tservice.middleware.exception :as exception]
            [tservice.middleware.formats :as formats]
            [tservice.plugin :as plugin]
            [tservice.plugin-jars :as plugin-jars]
            [tservice.routes.task :as task-route]))

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

   ["/manifest"
    {:tags ["Utility"]
     :get {:summary "Get the manifest data of all plugins."
           :parameters {}
           :responses {200 {:body any?}}
           :handler (fn [_]
                      (let [manifest (concat (plugin/get-manifest) (plugin-jars/get-manifest))]
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
                       (let [files (if (map? files) [files] files)
                             to-dir (get-workdir)]
                         (doseq [file files]
                           (let [filename (:filename file)
                                 tempfile (:tempfile file)
                                 to-path (fs-lib/join-paths to-dir filename)]
                             (log/debug "Upload file: " filename)
                             (fs-lib/create-directories! to-dir)
                             (fs-lib/copy tempfile to-path)))
                         {:status 201
                          :body {:upload_path (str "file://" (get-relative-filepath to-dir))
                                 :files (map #(:filename %) files)
                                 :total (count files)}}))}}]
   task-route/task])
