(ns tservice.routes.services
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [reitit.coercion.spec :as spec-coercion]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.swagger :as swagger]
            [ring.util.http-response :refer [ok]]
            [tservice.config :refer [get-workdir]]
            [tservice.lib.fs :as fs-lib]
            [tservice.middleware.exception :as exception]
            [tservice.middleware.formats :as formats]
            [tservice.util :as u]
            [tservice.plugin :as plugin]
            [tservice.routes.specs :as specs]))

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
        :swagger {:info {:title "my-api"
                         :description "https://cljdoc.org/d/metosin/reitit"}}}

    ["/swagger.json"
     {:get (swagger/create-swagger-handler)}]

    ["/api-docs/*"
     {:get (swagger-ui/create-swagger-ui-handler
            {:url "/api/swagger.json"
             :config {:validator-url nil}})}]]

   ["/ping"
    {:get (constantly (ok {:message "pong"}))}]

   ["/status/:uuid"
    {:get {:summary "Check the status of a specified task."
           :parameters {:path specs/uuid-spec}
           :responses  {200 {:body {:status string?
                                    :msg string?}}}
           :handler (fn [{{{:keys [uuid]} :path} :parameters}]
                      (let [path (fs-lib/join-paths (get-workdir) "download" uuid "log")]
                        (if (fs-lib/exists? path)
                          {:body (json/read-str (slurp path) :key-fn keyword)
                           :status 200}
                          {:body {:msg "No such uuid."}
                           :status 404})))}}]
   ["/manifest"
    {:get {:summary "Get the manifest data."
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
    {:post {:summary "Uploading File."
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
                                 :total (count files)}}))}}]])
