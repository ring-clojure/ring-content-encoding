(ns ring.middleware.content-encoding-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.middleware.content-encoding :refer [wrap-content-encoding]]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
