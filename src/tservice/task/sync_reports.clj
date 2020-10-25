(ns tservice.task.sync-reports
  "Tasks related to submit jobs to cromwell instance from workflow table."
  (:require [clojure.tools.logging :as log]
            [tservice.config :refer [env]]
            [clojurewerkz.quartzite
             [jobs :as jobs]
             [triggers :as triggers]]
            [clojurewerkz.quartzite.schedule.cron :as cron]
            [tservice.task :as task]
            [tservice.db.handler :as db-handler]
            [tservice.db.core :refer [*db*] :as db]
            [clojure.java.jdbc :as jdbc]))

;;; ------------------------------------------------- Submit Jobs ---------------------------------------------------
(defn- count-started-reports []
  (:count (db/get-report-count {:query-map {:status "Started"}})))

(defn- get-started-reports [page per-page]
  (:data (db-handler/search-reports
          {:query-map {:status "Started"}}
          page per-page)))

(defn- total-page [total per-page]
  (if (= (rem total per-page) 0)
    (quot total per-page)
    (+ (quot total per-page) 1)))

(defn- sync-reports! []
  (let [per-page   10
        nums-of-reports (count-started-reports)
        total-page (+ (total-page nums-of-reports per-page) 1)]
    (log/debug "Num of Reports: " nums-of-reports)
    (doseq [which-page (range 1 total-page)]
      (let [reports (get-started-reports which-page per-page)]
        (log/debug "Reports: " reports)
        (jdbc/with-db-transaction [t-conn *db*]
          (doseq [report reports]
            (log/debug "Syncing Report: " report)
            (try
              (let [report-id (:id report)
                    result (db-handler/sync-report report-id)
                    status (:status result)]
                (when (some? status)
                  (db/update-report! t-conn {:updates {:status (cond
                                                                 (= status "Running") "Started"
                                                                 (= status "Success") "Finished"
                                                                 :else "Failed")}
                                             :id      (:id report)}))
                (comment "Add exception handler"))
              (catch Exception e
                (log/error (format "Sync Report Failed: %s" (.printStackTrace e)))))))))))

;;; ------------------------------------------------------ Task ------------------------------------------------------
;; triggers the submitting of all reports which are scheduled to run in the current minutes
(jobs/defjob SyncReports [_]
  (try
    (log/info "Submit reports in started status...")
    (sync-reports!)
    (catch Throwable e
      (log/error e "SyncReports task failed"))))

(def ^:private sync-reports-job-key     "tservice.task.sync-reports.job")
(def ^:private sync-reports-trigger-key "tservice.task.sync-reports.trigger")

(defn get-cron-conf
  []
  (let [cron (get-in env [:tasks :sync-reports :cron])]
    (if cron
      cron
      ;; run at the top of every minute
      "0 */1 * * * ?")))

(defmethod task/init! ::SyncReports [_]
  (let [job     (jobs/build
                 (jobs/of-type SyncReports)
                 (jobs/with-identity (jobs/key sync-reports-job-key)))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key sync-reports-trigger-key))
                 (triggers/start-now)
                 (triggers/with-schedule
                   (cron/schedule
                    (cron/cron-schedule (get-cron-conf))
                    ;; If sync-reports! misfires, don't try to re-submit all the misfired jobs. Retry only the most
                    ;; recent misfire, discarding all others. This should hopefully cover cases where a misfire
                    ;; happens while the system is still running; if the system goes down for an extended period of
                    ;; time we don't want to re-send tons of (possibly duplicate) jobs.
                    ;;
                    ;; See https://www.nurkiewicz.com/2012/04/quartz-scheduler-misfire-instructions.html
                    (cron/with-misfire-handling-instruction-fire-and-proceed))))]
    (task/schedule-task! job trigger)))
