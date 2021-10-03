(ns tservice.api.task-test
  "Test the api library for task."
  (:require
   [mount.core :as mount]
   [clojure.test :refer [use-fixtures deftest is testing]]
   [tservice.api.task :as task]))

;; Use the test-config.edn as the config file.
(use-fixtures
  :once
  (fn [f]
    (mount/start #'tservice.config/env)
    (f)))

(deftest test-get-owner-from-headers
  (testing "x-auth-users is nil"
    (is (= nil
           (task/get-owner-from-headers {}))))

  (testing "x-auth-users is set incorrectly, not split by ,"
    (is (= "user1;user2"
           (task/get-owner-from-headers {"x-auth-users" "user1;user2"}))))

  (testing "x-auth-users is set correctly"
    (is (= "owner"
           (task/get-owner-from-headers {"x-auth-users" "owner,not-owner"})))))

(deftest test-make-response
  (testing "make a response for data2report type."
    (is (= {:log "/test.log"
            :report "/report"
            :response_type :data2report}
           (task/make-response {:response-type :data2report
                                :log "/tservice/test.log"
                                :report "/tservice/report"}))))

  (testing "make a response for data2data type."
    (is (= {:log "/test.log"
            :data {}
            :response_type :data2data}
           (task/make-response {:response-type :data2data
                                :log "/tservice/test.log"
                                :data {}}))))

  (testing "make a response for data2files type."
    (is (= {:log "/test.log"
            :files ["/test.csv"]
            :response_type :data2files}
           (task/make-response {:response-type :data2files
                                :log "/tservice/test.log"
                                :files ["/tservice/test.csv"]}))))

  (testing "make a response for data2chart type."
    (is (= {:log "/test.log"
            :data {:charts ["/chart1.json"]
                   :results ["/result1.json"]}
            :response_type :data2chart}
           (task/make-response {:response-type :data2chart
                                :log "/tservice/test.log"
                                :charts ["/tservice/chart1.json"]
                                :results ["/tservice/result1.json"]})))))
