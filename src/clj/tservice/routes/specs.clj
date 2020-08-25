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
