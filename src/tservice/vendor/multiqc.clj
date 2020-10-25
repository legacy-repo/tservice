(ns tservice.vendor.multiqc
  "A wrapper for multiqc instance."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as clj-str]
            [tservice.config :refer [env]]
            [clojure.java.shell :as shell :refer [sh]]))

(defn exist-bin?
  "True if multiqc is installed, otherwise return false."
  []
  (= 0 (:exit (sh "which" "multiqc"))))

(defn get-path-variable
  []
  (let [external-bin (get-in env [:external-bin])
        sys-path (System/getenv "PATH")]
    (if external-bin
      (str external-bin ":" sys-path)
      sys-path)))

(defn run-command
  [command-lst]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (let [result (apply sh command-lst)
          status (if (= (:exit result) 0) "Success" "Error")
          msg (str (:out result) "\n" (:err result))]
      {:status status
       :msg msg})))

(defn multiqc
  "A multiqc wrapper for generating multiqc report:
   TODO: set the absolute path of multiqc binary instead of environment variable

  Required:
  analysis-dir: Analysis directory, e.g. data directory from project
  outdir: Create report in the specified output directory.

  Options:
  | key                | description |
  | -------------------|-------------|
  | :dry-run?          | Dry run mode |
  | :filename          | Report filename. Use 'stdout' to print to standard out. |
  | :comment           | Custom comment, will be printed at the top of the report. |
  | :title             | Report title. Printed as page header, used for filename if not otherwise specified. |
  | :force?            | Overwrite any existing reports |
  | :prepend-dirs?     | Prepend directory to sample names |

  Example:
  (multiqc 'XXX' 'YYY' {:filename       'ZZZ'
                        :comment        ''
                        :title          ''
                        :force?         true
                        :prepend-dirs?  true})"
  [analysis-dir outdir {:keys [dry-run? filename comment title force? prepend-dirs? template config]
                        :or   {dry-run?      false
                               force?        true
                               prepend-dirs? false
                               filename      "multiqc_report.html"
                               comment       ""
                               template      "default"
                               title         "iSEQ Analyzer Report"}}]
  (let [force-arg   (if force? "--force" "")
        dirs-arg    (if prepend-dirs? "--dirs" "")
        config-arg  (if config (str "-c" config) "")
        multiqc-command (filter #(> (count %) 0) ["multiqc"
                                                  force-arg dirs-arg config-arg
                                                  "--title" (format "'%s'" title)
                                                  "--comment" (format "'%s'" comment)
                                                  "--filename" filename
                                                  "--outdir" outdir
                                                  "-t" template
                                                  analysis-dir])
        command-lst ["bash" "-c" (clj-str/join " " multiqc-command)]]
    (if dry-run?
      (log/debug (clj-str/join " " command-lst))
      (run-command command-lst))))
