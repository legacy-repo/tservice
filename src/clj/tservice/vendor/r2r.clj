(ns tservice.vendor.r2r
  "A wrapper for rnaseq2report instance."
  (:require [tservice.config :refer [env]]
            [clojure.java.shell :as shell :refer [sh]]))

(defn get-path-variable
  []
  (let [external-bin (get-in env [:external-bin])
        sys-path (System/getenv "PATH")]
    (if external-bin
      (str external-bin ":" sys-path)
      sys-path)))

(defn exist-bin?
  [name]
  (= 0 (:exit (sh "which" name))))

(defn call-r2r!
  "Call rnaseq2report RScript file.
   exp-table-file: 
   phenotype-file: 
   result-dir: 
  "
  [exp-table-file phenotype-file result-dir]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (let [command ["bash" "-c"
                   (format "rnaseq2report.R %s %s %s" exp-table-file phenotype-file result-dir)]
          result  (apply sh command)
          status (if (= (:exit result) 0) "Success" "Error")
          msg (str (:out result) "\n" (:err result))]
      {:status status
       :msg msg})))
