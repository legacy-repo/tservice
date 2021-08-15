(ns tservice.test.api.task
  (:require
   [clojure.test :refer :all]
   [tservice.api.task :as task]))

(use-fixtures
  :once
  (fn [f]
    (mount/start #'tservice.config/env)
    (f)))

(deftest test-app
  (testing "Match a response"
    (is true)))
