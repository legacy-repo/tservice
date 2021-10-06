(ns tservice.api.schema.task
  (:require [clojure.spec.alpha :as s]))

(s/def ::task_id string?)

(s/def ::log string?)

(s/def ::report string?)

(s/def ::files (s/coll-of string?))

(s/def ::data any?)

(s/def ::response_type string?)

(s/def ::data2report (s/keys :req-un [::log ::report]))

(s/def ::data2files (s/keys :req-un [::log ::files ::response_type ::task_id]))

(s/def ::data2data (s/keys :req-un [::log ::data ::response_type ::task_id]))

(s/def ::charts (s/coll-of string?))

(s/def ::results (s/coll-of string?))

(s/def ::data (s/keys :req-un [::charts ::results]))

(s/def ::data2charts (s/keys :req-un [::log ::data ::response_type ::task_id]))

(def ^:private schemas
  {:data2report ::data2report
   :data2files  ::data2files
   :data2charts  ::data2charts
   :data2data   ::data2data})

(defn get-response-schema
  [response-type]
  ((keyword response-type) schemas))
