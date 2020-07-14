(ns tservice.lib.r2r
  (:require [me.raynes.fs :as fs]
            [datains.config :refer [env]]
            [clojure.tools.logging :as log]
            [clojure.string :as clj-str])
  (:use [clojure.java.shell :as shell :refer [sh]]))

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
   anno-file:
   result-dir: 
  "
  [exp-table-file phenotype-file anno-file result-dir]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (if (exist-bin? "rnaseq2report.R")
      (let [command ["rnaseq2report.R"
                     (format "%s %s %s %s" exp-table-file phenotype-file anno-file result-dir)]
            result  (apply sh command)]
        result)
      {:exit 1
       :out   ""
       :err   "Command not found: rnaseq2report."})))

