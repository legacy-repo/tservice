(ns tservice.routes.specs
  (:require [clojure.spec.alpha :as s]
            [spec-tools.core :as st]))

(s/def ::zip-mode
  (st/spec
   {:spec                boolean?
    :type                :boolean
    :description         "ZIP Mode?"
    :swagger/default     true
    :reason              "The zip-mode must be boolean."}))

(s/def ::pdf-mode
  (st/spec
   {:spec                boolean?
    :type                :boolean
    :description         "PDF Mode?"
    :swagger/default     true
    :reason              "The pdf-mode must be boolean."}))

(s/def ::filepath
  (st/spec
   {:spec                (s/or :string string? :regex #"^file:\/\/(\/|\.\/)[a-zA-Z0-9_]+.*")
    :type                :string
    :description         "File path for covertor."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(def xps2pdf-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::filepath]
          :opt-un [::pdf-mode ::zip-mode]))

(def ballgown2exp-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::filepath]))