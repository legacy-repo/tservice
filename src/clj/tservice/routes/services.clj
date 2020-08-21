(ns tservice.routes.services
  (:require
   [clojure.data.csv :as csv]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [me.raynes.fs :as fs]
   [reitit.coercion.spec :as spec-coercion]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.multipart :as multipart]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.swagger-ui :as swagger-ui]
   [reitit.swagger :as swagger]
   [ring.util.http-response :refer [ok]]
   [tservice.config :refer [get-workdir]]
   [tservice.events :as events]
   [tservice.lib.fs :as fs-lib]
   [tservice.middleware.exception :as exception]
   [tservice.middleware.formats :as formats]
   [tservice.routes.specs :as specs]
   [tservice.util :as u]))

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

   ["/status/:id"
    {:get {:summary "Check the status of a specified task."
           :parameters {}
           :responses  {200 {:body {:status string?
                                    :msg string?}}}
           :handler (fn [{{{:keys [id]} :path} :parameters}]
                      (let [path (fs-lib/join-paths "download" id)]
                        (if (fs-lib/exists? path)
                          {:body (json/read-str (slurp path))
                           :status 200}
                          {:body {:msg "No such id."}
                           :status 404})))}}]
   ["/manifest"
    {:get {:summary "Get the manifest data."
           :parameters {}
           :responses {200 {:body any?}}
           :handler (fn [_]
                      (let [path (.getPath (io/resource "manifest.json"))]
                        (if (fs-lib/exists? path)
                          {:body (json/read-str (slurp path))
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
    {:post {:summary "Convert ballgown files to experiment table and generate report."
            :parameters {:body specs/ballgown2exp-params-body}
            :responses {201 {:body {:download_url string? :log_url string?}}}
            :handler (fn [{{{:keys [filepath phenotype]} :body} :parameters}]
                       (let [workdir (get-workdir)
                             from-path (u/replace-path filepath workdir)
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
                          :body {:download_url (fs-lib/join-paths relative-dir)
                                 :report (fs-lib/join-paths relative-dir "multiqc.html")
                                 :log_url log-path}}))}}]

   ["/quartet-dna-report"
    {:post {:summary "Parse the results of the quartet-dna-qc app and generate the report."
            :parameters {:body specs/quartet-dna-report-params-body}
            :responses {201 {:body {:download_url string? :log_url string?}}}
            :handler (fn [{{{:keys [filepath metadata]} :body} :parameters}]
                       (let [workdir (get-workdir)
                             from-path (u/replace-path filepath workdir)
                             relative-dir (fs-lib/join-paths "download" (u/uuid))
                             to-dir (fs-lib/join-paths workdir relative-dir)
                             log-path (fs-lib/join-paths to-dir "log")]
                         (fs-lib/create-directories! to-dir)
                         (spit log-path (json/write-str {:status "Running" :msg ""}))
                         (events/publish-event! :quartet_dnaseq_report-convert
                                                {:datadir from-path
                                                 :metadata metadata
                                                 :dest-dir to-dir})
                         {:status 201
                          :body {:download_url (fs-lib/join-paths relative-dir)
                                 :report (fs-lib/join-paths relative-dir "multiqc.html")
                                 :log_url (fs-lib/join-paths relative-dir "log")}}))}}]])
