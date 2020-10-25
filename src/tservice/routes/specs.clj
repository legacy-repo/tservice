(ns tservice.routes.specs
  (:require [clojure.spec.alpha :as s]
            [spec-tools.core :as st]))

(s/def ::uuid
  (st/spec
   {:spec                #(re-find #"^[a-f0-9]{8}(-[a-f0-9]{4}){4}[a-f0-9]{8}$" %)
    :type                :string
    :description         "uuid string"
    :swagger/default     "40644dec-1abd-489f-a7a8-1011a86f40b0"
    :reason              "Not valid a uuid."}))

(def uuid-spec
  (s/keys :req-un [::uuid]
          :opt-un []))

(s/def ::page
  (st/spec
   {:spec                nat-int?
    :type                :long
    :description         "Page, From 1."
    :swagger/default     1
    :reason              "The page parameter can't be none."}))

(s/def ::per_page
  (st/spec
   {:spec                nat-int?
    :type                :long
    :description         "Num of items per page."
    :swagger/default     10
    :reason              "The per-page parameter can't be none."}))

(s/def ::id
  (st/spec
   {:spec                #(some? (re-matches #"[a-f0-9]{8}(-[a-f0-9]{4}){4}[a-f0-9]{8}" %))
    :type                :string
    :description         "report-id"
    :swagger/default     "40644dec-1abd-489f-a7a8-1011a86f40b0"
    :reason              "Not valid a report-id."}))

;; -------------------------------- Report Spec --------------------------------
(s/def ::app_name
  (st/spec
   {:spec                string?
    :type                :string
    :description         "The name of the app"
    :swagger/default     ""
    :reason              "Not a valid app name"}))

(s/def ::report_name
  (st/spec
   {:spec                string?
    :type                :string
    :description         "The name of the record"
    :swagger/default     ""
    :reason              "Not a valid report name"}))

(s/def ::description
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Description of the record"
    :swagger/default     ""
    :reason              "Not a valid description."}))

; {"plugin-name": "quartet-rnaseq-report", "metadata": "{}"}
; (s/def ::script
;   (st/spec
;    {:spec                string?
;     :type                :string
;     :description         "Script of the record"
;     :swagger/default     ""
;     :reason              "Not a valid script"}))

(s/def ::report_path
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Report path of the record"
    :swagger/default     ""
    :reason              "Not a valid report_path"}))

(s/def ::log
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Log of the record"
    :swagger/default     ""
    :reason              "Not a valid log"}))

(s/def ::started_time
  (st/spec
   {:spec                nat-int?
    :type                :integer
    :description         "Started time of the record"
    :swagger/default     ""
    :reason              "Not a valid started_time"}))

(s/def ::finished_time
  (st/spec
   {:spec                nat-int?
    :type                :integer
    :description         "Finished time of the record"
    :swagger/default     ""
    :reason              "Not a valid finished_time"}))

(s/def ::archived_time
  (st/spec
   {:spec                nat-int?
    :type                :integer
    :description         "Archived time of the record"
    :swagger/default     ""
    :reason              "Not a valid archived_time"}))

(s/def ::project_id
  (st/spec
   {:spec                #(some? (re-matches #"[a-f0-9]{8}(-[a-f0-9]{4}){4}[a-f0-9]{8}" %))
    :type                :string
    :description         "project-id"
    :swagger/default     "40644dec-1abd-489f-a7a8-1011a86f40b0"
    :reason              "Not valid a project-id."}))

(s/def ::report_type
  (st/spec
   {:spec                #(#{"multiqc"} %)
    :type                :string
    :description         "Filter results by report-type field."
    :swagger/default     "multiqc"
    :reason              "Not valid report-type, only support multiqc."}))

(s/def ::status
  (st/spec
   {:spec                #(#{"Started" "Finished" "Archived" "Failed"} %)
    :type                :string
    :description         "Filter results by status field."
    :swagger/default     "Started"
    :reason              "Not valid status, only support Started, Finished, Archived, Failed."}))

(s/def ::report-id
  (st/spec
   {:spec                #(some? (re-matches #"[a-f0-9]{8}(-[a-f0-9]{4}){4}[a-f0-9]{8}" %))
    :type                :string
    :description         "report-id"
    :swagger/default     ""
    :reason              "Not valid a report-id"}))

(def report-id
  (s/keys :req-un [::id]
          :opt-un []))

(def report-params-query
  "A spec for the query parameters."
  (s/keys :req-un []
          :opt-un [::page ::per_page ::app_name ::project_id ::report_type ::status]))

(def report-body
  "A spec for the report body."
  (s/keys :req-un [::report_name ::description ::started_time ::report_type ::status ::app_name]
          :opt-un [::archived_time ::finished_time ::log ::report_path ::project_id ::report-id]))