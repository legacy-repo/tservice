(ns tservice.routes.specs
  (:require [clojure.spec.alpha :as s]
            [spec-tools.core :as st]))

(s/def ::zip_mode
  (st/spec
   {:spec                boolean?
    :type                :boolean
    :description         "ZIP Mode?"
    :swagger/default     true
    :reason              "The zip-mode must be boolean."}))

(s/def ::pdf_mode
  (st/spec
   {:spec                boolean?
    :type                :boolean
    :description         "PDF Mode?"
    :swagger/default     true
    :reason              "The pdf-mode must be boolean."}))

(s/def ::filepath
  (st/spec
   {:spec                (s/and string? #(re-matches #"^file:\/\/(\/|\.\/)[a-zA-Z0-9_]+.*" %))
    :type                :string
    :description         "File path for covertor."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(s/def ::sample_id
  (st/spec
   {:spec                (s/coll-of string?)
    :type                :vector
    :description         "list of sample id."
    :swagger/default     []
    :reason              "The sample id must a list."}))

(s/def ::group
  (st/spec
   {:spec                (s/coll-of string?)
    :type                :vector
    :description         "list of group name which is matched with sample id."
    :swagger/default     []
    :reason              "The group must a list."}))

(s/def ::phenotype
  (s/keys :req-un [::sample_id ::group]))

(def xps2pdf-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::filepath]
          :opt-un [::pdf_mode ::zip_mode]))

(def ballgown2exp-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::filepath ::phenotype]))