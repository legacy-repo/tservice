(ns tservice.task.sync-tasks
  "Tasks related to submit jobs to cromwell instance from workflow table."
  (:require [clojure.tools.logging :as log]
            [tservice.config :refer [env]]
            [clojurewerkz.quartzite
             [jobs :as jobs]
             [triggers :as triggers]]
            [clojurewerkz.quartzite.schedule.cron :as cron]
            [tservice.task :as task]
            [tservice.db.handler :as db-handler]))

;;; ------------------------------------------------- Submit Jobs ---------------------------------------------------
(defn- count-started-tasks []
  (:count (db-handler/get-task-count {:query-map {:status "Started"}})))

(defn- get-started-tasks [page per-page]
  (:data (db-handler/search-tasks
          {:query-map {:status "Started"}}
          page per-page)))

(defn- total-page [total per-page]
  (if (= (rem total per-page) 0)
    (quot total per-page)
    (+ (quot total per-page) 1)))

(defn- sync-tasks! []
  (let [per-page   10
        nums-of-tasks (count-started-tasks)
        total-page (+ (total-page nums-of-tasks per-page) 1)]
    (log/debug "Num of Tasks: " nums-of-tasks)
    (doseq [which-page (range 1 total-page)]
      (let [tasks (get-started-tasks which-page per-page)]
        (log/debug "Tasks: " tasks)
        (doseq [task tasks]
          (log/debug "Syncing Task: " task)
          (try
            (let [task-id (:id task)
                  result (db-handler/search-task task-id)
                  status (:status result)]
              (when (some? status)
                (db-handler/update-task! (:id task)
                                         {:status (cond
                                                    (= status "Running") "Started"
                                                    (= status "Success") "Finished"
                                                    :else "Failed")}))
              (comment "Add exception handler"))
            (catch Exception e
              (log/error (format "Sync Task Failed: %s" (.printStackTrace e))))))))))

;;; ------------------------------------------------------ Task ------------------------------------------------------
;; triggers the submitting of all tasks which are scheduled to run in the current minutes
(jobs/defjob SyncTasks [_]
  (try
    (log/info "Submit tasks in started status...")
    (sync-tasks!)
    (catch Throwable e
      (log/error e "SyncTasks task failed"))))

(def ^:private sync-tasks-job-key     "tservice.task.sync-tasks.job")
(def ^:private sync-tasks-trigger-key "tservice.task.sync-tasks.trigger")

(defn get-cron-conf
  []
  (let [cron (get-in env [:tasks :sync-tasks :cron])]
    (if cron
      cron
      ;; run at the top of every minute
      "0 */1 * * * ?")))

(defmethod task/init! ::SyncTasks [_]
  (let [job     (jobs/build
                 (jobs/of-type SyncTasks)
                 (jobs/with-identity (jobs/key sync-tasks-job-key)))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key sync-tasks-trigger-key))
                 (triggers/start-now)
                 (triggers/with-schedule
                   (cron/schedule
                    (cron/cron-schedule (get-cron-conf))
                    ;; If sync-tasks! misfires, don't try to re-submit all the misfired jobs. Retry only the most
                    ;; recent misfire, discarding all others. This should hopefully cover cases where a misfire
                    ;; happens while the system is still running; if the system goes down for an extended period of
                    ;; time we don't want to re-send tons of (possibly duplicate) jobs.
                    ;;
                    ;; See https://www.nurkiewicz.com/2012/04/quartz-scheduler-misfire-instructions.html
                    (cron/with-misfire-handling-instruction-fire-and-proceed))))]
    (task/schedule-task! job trigger)))
