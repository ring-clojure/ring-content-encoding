(ns ring.middleware.content-encoding-test
  (:require [clojure.test :refer [deftest is]]
            [ring.core.protocols :as p]
            [ring.middleware.content-encoding :as ce])
  (:import [java.io ByteArrayOutputStream]))

(deftest no-content-encoding-test
  (let [response {:status  200
                  :headers {"Content-Type" "text/plain; charset=utf-8"}
                  :body    "Hello World"}]
    (is (= response (ce/content-encoding-response response {:headers {}})))))

(deftest gzip-content-encoding-test
  (let [response (ce/content-encoding-response
                  {:status  200
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body    "Hello World"}
                  {:headers {"accept-encoding" "gzip"}})
        out      (ByteArrayOutputStream.)]
    (p/write-body-to-stream (:body response) response out)
    (is (= {:status 200
            :headers {"Content-Type"     "text/plain; charset=utf-8"
                      "Content-Encoding" "gzip"}}
           (dissoc response :body)))
    (is (= [31 -117 8 0 0 0 0 0 0 -1 -13 72 -51 -55 -55 87 8 -49 47 -54 73 1
            0 86 -79 23 74 11 0 0 0]
           (vec (.toByteArray out))))))
