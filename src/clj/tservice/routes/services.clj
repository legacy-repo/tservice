(ns tservice.routes.services
  (:require
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [reitit.ring.coercion :as coercion]
   [clojure.spec.alpha :as s]
   [reitit.coercion.spec :as spec-coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [tservice.middleware.formats :as formats]
   [tservice.middleware.exception :as exception]
   [tservice.events :as events]
   [tservice.config :refer [env get-workdir]]
   [tservice.util :as u]
   [me.raynes.fs :as fs]
   [tservice.lib.fs :as fs-lib]
   [clojure.tools.logging :as log]
   [reitit.ring.middleware.multipart :as multipart]
   [ring.util.http-response :refer :all]
   [tservice.routes.specs :as specs]
   [clojure.string :as str]
   [clojure.data.csv :as csv]
   [clojure.data.json :as json]
   [clojure.java.io :as io]))

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
                                 :total (count files)}}))}}]

   ["/xps2pdf"
    {:post {:summary "Convert xps to pdf."
            :parameters {:body specs/xps2pdf-params-body}
            :responses {201 {:body {:download_url string? :files [string?] :log_url string?}}}
            :handler (fn [{{{:keys [filepath]} :body} :parameters}]
                       (let [workdir (get-workdir)
                             from-path (if (re-matches #"^file:\/\/\/.*" filepath)
                                         ; Absolute path with file://
                                         (str/replace filepath #"^file:\/\/" "")
                                         (fs-lib/join-paths workdir (str/replace filepath #"^file:\/\/" "")))
                             from-files (if (fs-lib/directory? from-path)
                                          (filter #(fs-lib/regular-file? %)
                                                  (map #(.getPath %) (file-seq (io/file from-path))))
                                          [from-path])
                             relative-dir (fs-lib/join-paths "download" (u/uuid))
                             to-dir (fs-lib/join-paths workdir relative-dir)
                             to-files (vec (map #(fs-lib/join-paths to-dir (str (fs/base-name % ".xps") ".pdf")) from-files))
                             zip-path (fs-lib/join-paths relative-dir "merged.zip")
                             pdf-path (fs-lib/join-paths relative-dir "merged.pdf")
                             log-path (fs-lib/join-paths relative-dir "log")]
                         (fs-lib/create-directories! to-dir)
                         ; Launch the batchxps2pdf-convert
                         (spit (fs-lib/join-paths workdir log-path) (json/write-str {:status "Running" :msg ""}))
                         (events/publish-event! :batchxps2pdf-convert {:from-files from-files :to-dir to-dir})
                         {:status 201
                          :body {:download_url relative-dir
                                 :files (vec (map #(str/replace % (re-pattern workdir) "") to-files))
                                 :log_url log-path
                                 :zip_url zip-path
                                 :pdf_url pdf-path}}))}}]

   ["/ballgown2exp"
    {:post {:summary "Convert ballgown files to experiment table."
            :parameters {:body specs/ballgown2exp-params-body}
            :responses {201 {:body {:download_url string? :log_url string?}}}
            :handler (fn [{{{:keys [filepath phenotype]} :body} :parameters}]
                       (let [workdir (get-workdir)
                             from-path (if (re-matches #"^file:\/\/\/.*" filepath)
                                         ; Absolute path with file://
                                         (str/replace filepath #"^file:\/\/" "")
                                         (fs-lib/join-paths workdir (str/replace filepath #"^file:\/\/" "")))
                             relative-dir (fs-lib/join-paths "download" (u/uuid))
                             to-dir (fs-lib/join-paths workdir relative-dir)
                             phenotype-filepath (fs-lib/join-paths to-dir "phenotype.txt")
                             phenotype-data (cons ["sample_id" "group"]
                                                  (map vector (:sample_id phenotype) (:group phenotype)))
                             log-path (fs-lib/join-paths relative-dir "log")]
                         (log/info phenotype phenotype-data)
                         (fs-lib/create-directories! to-dir)
                         (with-open [file (io/writer phenotype-filepath)]
                           (csv/write-csv file phenotype-data :separator \tab))
                         ; Launch the ballgown2exp
                         (spit log-path (json/write-str {:status "Running" :msg ""}))
                         (events/publish-event! :ballgown2exp-convert
                                                {:ballgown-dir from-path
                                                 :phenotype-filepath phenotype-filepath
                                                 :dest-dir to-dir})
                         {:status 201
                          :body {:download_url (fs-lib/join-paths relative-dir "fpkm.txt")
                                 :log_url log-path}}))}}]])
