(ns tservice.vendor.multiqc
  "A wrapper for multiqc instance."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as clj-str]
            [tservice.lib.files :refer [call-command!]]))

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
        command (clj-str/join " " multiqc-command)]
    (if dry-run?
      (log/debug command)
      (call-command! command))))
