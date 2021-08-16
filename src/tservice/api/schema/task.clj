(ns tservice.api.schema.task
  (:require [clojure.spec.alpha :as s]))

(s/def ::log string?)

(s/def ::report string?)

(s/def ::data2report (s/keys :req-un [::log ::report]))

(def ^:private schemas
  {:data2report ::data2report})

(defn get-response-schema
  [response-type]
  ((keyword response-type) schemas))
