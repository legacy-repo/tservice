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
            [tservice.routes.specs :as specs]
            [clojure.java.io :as io]
            [ring.util.http-response :refer [ok]]
            [ring.util.mime-type :as mime-type]
            [tservice.lib.files :refer [get-workdir get-relative-filepath get-tservice-workdir]]
            [tservice.lib.fs :as fs-lib]
            [tservice.middleware.exception :as exception]
            [tservice.middleware.formats :as formats]
            [tservice.plugin :as plugin]
            [tservice.plugin-jars :as plugin-jars]
            [tservice.routes.task :as task-route]
            [tservice.version :as v]
            [tservice.db.core :as db]))

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
                         :description "https://cljdoc.org/d/metosin/reitit"
                         :version "v1"}}}

    ["/swagger.json"
     {:get (swagger/create-swagger-handler)}]

    ["/api-docs/*"
     {:get (swagger-ui/create-swagger-ui-handler
            {:url "/api/swagger.json"
             :config {:validator-url nil}})}]]

   ["/version"
    {:tags ["Utility"]
     :get {:summary "Get the version of tservice instance."
           :parameters {}
           :responses {200 {:body specs/instance-version}}
           :handler (fn [_]
                      (ok {:version (v/get-version "org.clojars.yjcyxky" "tservice")
                           :db_version (db/get-db-version)}))}}]

   ["/manifest"
    {:tags ["Utility"]
     :get {:summary "Get the manifest data of all plugins."
           :parameters {}
           :responses {200 {:body any?}}
           :handler (fn [_]
                      (let [manifest (concat (plugin/get-manifest) (plugin-jars/get-manifest))]
                        (if manifest
                          {:body {:data manifest
                                  :success true
                                  :total (count manifest)}
                           :status 200}
                          {:body {:msg "No manifest file."
                                  :status 404}})))}}]

   ["/download"
    {:tags ["File"]
     :get {:summary "Downloads a file"
           :parameters {:query specs/filelink-params-query}
           :handler (fn [{{{:keys [filelink]} :query} :parameters}]
                      {:status 200
                       :headers {"Content-Type" (or (mime-type/ext-mime-type filelink) "text/plain")}
                       :body (-> (fs-lib/join-paths (get-tservice-workdir) (str "." filelink))
                                 (io/input-stream))})}}]

   ["/upload"
    {:tags ["File"]
     :post {:summary "Uploads File(s)."
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
