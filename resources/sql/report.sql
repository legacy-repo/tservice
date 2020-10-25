-- Author: Jingcheng Yang <yjcyxky@163.com>
-- Date: 2020.03.27
-- License: See the details in license.md

---------------------------------------------------------------------------------------------
-- Table Name: tservice_report
-- Description: Managing reports
-- Functions: create-report!, update-report!, get-report-count, search-reports, delete-report!
---------------------------------------------------------------------------------------------

-- :name create-report!
-- :command :returning-execute
-- :result :affected
/* :doc
  Args:
    | key                | required  | description |
    | -------------------|-----------|-------------|
    | :id                | true/uniq | UUID string
    | :report_name       | true      | The report name, required, [a-zA-Z0-9]+
    | :project_id        | false     | The id  of the related project
    | :app_name          | false     | Auto generated script for making a report
    | :description       | false     | A description of the report
    | :started_time      | true      | Bigint
    | :finished_time     | false     | Bigint
    | :archived_time     | false     | Bigint
    | :report_path       | false     | A relative path of a report based on the report directory
    | :report_type       | true      | multiqc
    | :status            | true      | Started, Finished, Archived, Failed
  Description:
    Create a new report record and then return the number of affected rows.
  Examples: 
    Clojure: (create-report! {})
*/
INSERT INTO tservice_report (id, report_name, project_id, app_name, started_time, finished_time, archived_time, report_path, report_type, description, log, status)
VALUES (:id, :report_name, :project_id, :app_name, :started_time, :finished_time, :archived_time, :report_path, :report_type, :description, :log, :status)
RETURNING id


-- :name update-report!
-- :command :execute
-- :result :affected
/* :doc
  Args:
    {:updates {:status "status" :finished_time ""} :id "3"}
  Description: 
    Update an existing report record.
  Examples:
    Clojure: (update-report! {:updates {:finished_time "finished-time" :status "status"} :id "3"})
    HugSQL: UPDATE tservice_report SET finished_time = :v:query-map.finished-time,status = :v:query-map.status WHERE id = :id
    SQL: UPDATE tservice_report SET finished_time = "finished_time", status = "status" WHERE id = "3"
  TODO:
    It will be raise exception when (:updates params) is nil.
*/
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
UPDATE tservice_report
SET
/*~
(string/join ","
  (for [[field _] (:updates params)]
    (str (identifier-param-quote (name field) options)
      " = :v:updates." (name field))))
~*/
WHERE id = :id


-- :name get-report-count
-- :command :query
-- :result :one
/* :doc
  Args:
    {:query-map {:status "XXX"}}
  Description:
    Get count.
  Examples:
    Clojure: (get-report-count)
    SQL: SELECT COUNT(id) FROM tservice_report

    Clojure: (get-report-count {:query-map {:status "XXX"}})
    HugSQL: SELECT COUNT(id) FROM tservice_report WHERE status = :v:query-map.status
    SQL: SELECT COUNT(id) FROM tservice_report WHERE status = "XXX"
  TODO: 
    Maybe we need to support OR/LIKE/IS NOT/etc. expressions in WHERE clause.
  FAQs:
    1. why we need to use :one as the :result
      Because the result will be ({:count 0}), when we use :raw to replace :one.
*/
/* :require [tservice.db.sql-helper :as sql-helper] */
SELECT COUNT(id)
FROM tservice_report
/*~
; TODO: May be raise error, when the value of :query-map is unqualified.
(cond
  (:query-map params) (sql-helper/where-clause (:query-map params) options)
  (:where-clause params) ":snip:where-clause")
~*/


-- :name search-reports
-- :command :query
-- :result :many
/* :doc
  Args:
    {:query-map {:status "XXX"} :limit 1 :offset 0}
  Description:
    Get reports by using query map
  Examples: 
    Clojure: (search-reports {:query-map {:status "XXX"}})
    HugSQL: SELECT * FROM tservice_report WHERE status = :v:query-map.status
    SQL: SELECT * FROM tservice_report WHERE status = "XXX"
  TODO:
    1. Maybe we need to support OR/LIKE/IS NOT/etc. expressions in WHERE clause.
    2. Maybe we need to use exact field name to replace *.
*/
/* :require [tservice.db.sql-helper :as sql-helper] */
SELECT * 
FROM tservice_report
/*~
; TODO: May be raise error, when the value of :query-map is unqualified.
(cond
  (:query-map params) (sql-helper/where-clause (:query-map params) options)
  (:where-clause params) ":snip:where-clause")
~*/
ORDER BY id
--~ (when (and (:limit params) (:offset params)) "LIMIT :limit OFFSET :offset")


-- :name search-reports-with-tags
-- :command :query
-- :result :many
/* :doc
  Args:
    {:query-map {:status "XXX"} :limit 1 :offset 0}
  Description:
    Get reports with tags by using query map
  Examples: 
    Clojure: (search-reports-with-tags {:query-map {:status "XXX"}})
    HugSQL:
      SELECT  tservice_report.id,
              tservice_report.report_name,
              tservice_report.project_id,
              tservice_report.app_name,
              tservice_report.started_time,
              tservice_report.finished_time,
              tservice_report.archived_time,
              tservice_report.report_path,
              tservice_report.log,
              tservice_report.status
              array_agg( tservice_tag.id ) as tag_ids,
              array_agg( tservice_tag.title ) as tags
      FROM tservice_entity_tag
      JOIN tservice_report ON tservice_entity_tag.entity_id = tservice_report.id
      JOIN tservice_tag ON tservice_entity_tag.tag_id = tservice_tag.id
      WHERE tservice_report.status = :v:query-map.status
      GROUP BY tservice_report.id
  TODO:
    1. Maybe we need to support OR/LIKE/IS NOT/etc. expressions in WHERE clause.
    2. Maybe we need to use exact field name to replace *.
    3. Maybe we need to add tservice_entity_tag.entity_type = "report" condition.
*/
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
SELECT  tservice_report.id,
        tservice_report.report_name,
        tservice_report.project_id,
        tservice_report.app_name,
        tservice_report.started_time,
        tservice_report.finished_time,
        tservice_report.archived_time,
        tservice_report.report_path,
        tservice_report.log,
        tservice_report.status
        array_agg( tservice_tag.id ) as tag_ids,
        array_agg( tservice_tag.title ) as tags
FROM tservice_entity_tag
JOIN tservice_report ON tservice_entity_tag.entity_id = tservice_report.id
JOIN tservice_tag ON tservice_entity_tag.tag_id = tservice_tag.id
/*~
(when (:query-map params) 
 (str "WHERE "
  (string/join " AND "
    (for [[field _] (:query-map params)]
      (str "tservice_report."
        (identifier-param-quote (name field) options)
          " = :v:query-map." (name field))))))
~*/
GROUP BY tservice_report.id
ORDER BY tservice_report.id
--~ (when (and (:limit params) (:offset params)) "LIMIT :limit OFFSET :offset")


-- :name delete-report!
-- :command :execute
-- :result :affected
/* :doc
  Args:
    {:id "XXX"}
  Description:
    Delete a report record given the id
  Examples:
    Clojure: (delete-report! {:id "XXX"})
    SQL: DELETE FROM tservice_report WHERE id = "XXX"
*/
DELETE
FROM tservice_report
WHERE id = :id


-- :name delete-all-reports!
-- :command :execute
-- :result :affected
/* :doc
  Description:
    Delete all report records.
  Examples:
    Clojure: (delete-all-reports!)
    SQL: TRUNCATE tservice_report;
*/
TRUNCATE tservice_report;
