(ns tservice.routes.fs-spec
  (:require [clojure.spec.alpha :as s]
            [spec-tools.core :as st]
            [clj-filesystem.core :as fs]))

(s/def ::page
  (st/spec
   {:spec            nat-int?
    :type            :long
    :description     "Page, From 1."
    :swagger/default 1
    :reason          "The page parameter can't be none."}))

(s/def ::per_page
  (st/spec
   {:spec            nat-int?
    :type            :long
    :description     "Num of items per page."
    :swagger/default 10
    :reason          "The per-page parameter can't be none."}))

;; -------------------------------- FS Spec --------------------------------
(s/def ::name
  (st/spec
   {:spec            (s/and string? #(re-find #"^[A-Za-z0-9][A-Za-z0-9\.\-\_\:]{1,61}[A-Za-z0-9]$" %))  ; 不超过 64 个字符
    :type            :string
    :description     "The name of the bucket."
    :swagger/default "test"
    :reason          "Not a valid bucket name, regex: '^[A-Za-z0-9][A-Za-z0-9.-_:]{1,61}[A-Za-z0-9]$'."}))

(s/def ::service
  (st/spec
   {:spec (fn [service] (set (map (fn [[key _]] (name key)) @fs/services)) service)
    :type :string
    :description "The name of the service."
    :swagger/default "minio"
    :reason "Not a valid service name."}))

(s/def ::prefix
  (st/spec
   {:spec            string?
    :type            :string
    :description     "The prefix of the object."
    :swagger/default "test"
    :reason          "Not a valid object prefix"}))

(def bucket-params-query
  "A spec for the query parameters."
  (s/keys :req-un []
          :opt-un [::page ::per_page ::prefix]))

(def bucket-name-spec
  (s/keys :req-un [::service ::name]
          :opt-un []))

(def bucket-spec
  (s/keys :req-un [::service]
          :opt-un []))
