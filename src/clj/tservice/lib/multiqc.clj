(ns tservice.lib.multiqc
  (:require [me.raynes.fs :as fs]
            [clojure.tools.logging :as log]
            [clojure.string :as clj-str])
  (:use [clojure.java.shell :only [sh]]))

(defn ok?
  "True if multiqc is installed, otherwise return false."
  []
  (= 0 (:exit (sh "which" "multiqc"))))

(def ^:private report-dir (atom "~/.datains/reports"))

(defn setup-report-dir
  [new-report-dir]
  (reset! report-dir new-report-dir))

(defn get-report-dir
  []
  (fs/expand-home @report-dir))

(defn run-command
  [command-lst {:keys [out-log?]
                :or   {out-log? true}}]
  (let [result (apply sh command-lst)]
    (if out-log?
      result
      (= 0 (:exit result)))))

(defn multiqc
  "A multiqc wrapper for generating multiqc report:
   TODO: set the absolute path of multiqc binary instead of environment variable

  | key                | description |
  | -------------------|-------------|
  | :analysis-dir      | Analysis directory, e.g. data directory from project 
  | :outdir            | Create report in the specified output directory.
  | :filename          | Report filename. Use 'stdout' to print to standard out.
  | :comment           | Custom comment, will be printed at the top of the report.
  | :title             | Report title. Printed as page header, used for filename if not otherwise specified.
  | :force?            | Overwrite any existing reports
  | :prepend-dirs?     | Prepend directory to sample names
  | :out-log?          | Output log or return true/false

  Example:
  (multiqc {:analysis-dir   'XXX'
            :outdir         'YYY'
            :filename       'ZZZ'
            :comment        ''
            :title          ''
            :force?         true
            :prepend-dirs?  true})"
  [analysis-dir outdir dry-run? {:keys [out-log? filename comment title force? prepend-dirs?]
                                 :or   {force?        true
                                        prepend-dirs? false
                                        filename      "multiqc_report.html"
                                        comment       ""
                                        title         "iSEQ Analyzer Report"
                                        out-log?      true}}]
  (let [force-arg   (if force? "--force" "")
        dirs-arg    (if prepend-dirs? "--dirs" "")
        command-lst (filter #(> (count %) 0) ["multiqc"
                                              force-arg dirs-arg
                                              "--title" (format "'%s'" title)
                                              "--comment" (format "'%s'" comment)
                                              "--filename" filename
                                              "--outdir" outdir
                                              analysis-dir])]
    (if dry-run?
      (clj-str/join " " command-lst)
      (run-command command-lst {:out-log? out-log?}))))
