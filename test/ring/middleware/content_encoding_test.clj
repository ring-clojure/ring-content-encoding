(ns ring.middleware.content-encoding-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [ring.core.protocols :as p]
            [ring.middleware.content-encoding :as ce])
  (:import [java.io ByteArrayOutputStream]))

(def ^:private plain-response
  {:status  200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    "Hello World"})

(deftest no-content-encoding-test
  (is (= plain-response
         (ce/content-encoding-response plain-response {:headers {}}))))

(deftest gzip-content-encoding-test
  (let [response (ce/content-encoding-response
                  plain-response
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

(deftest deflate-content-encoding-test
  (let [response (ce/content-encoding-response
                  plain-response
                  {:headers {"accept-encoding" "deflate"}})
        out      (ByteArrayOutputStream.)]
    (p/write-body-to-stream (:body response) response out)
    (is (= {:status 200
            :headers {"Content-Type"     "text/plain; charset=utf-8"
                      "Content-Encoding" "deflate"}}
           (dissoc response :body)))
    (is (= [120 -100 -13 72 -51 -55 -55 87 8 -49 47 -54 73 1 0 24 11 4 29]
           (vec (.toByteArray out))))))

(deftest br-content-encoding-test
  (let [response (ce/content-encoding-response
                  plain-response
                  {:headers {"accept-encoding" "br"}})
        out      (ByteArrayOutputStream.)]
    (p/write-body-to-stream (:body response) response out)
    (is (= {:status 200
            :headers {"Content-Type"     "text/plain; charset=utf-8"
                      "Content-Encoding" "br"}}
           (dissoc response :body)))
    (is (= [11 5 -128 72 101 108 108 111 32 87 111 114 108 100 3]
           (vec (.toByteArray out))))))

(deftest br-content-encoding-params-test
  (let [response (ce/content-encoding-response
                  {:status  200
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body    "aaaaaaaaabbbbbbbbcccccdddeeeaaaabbbbbbbabbab"}
                  {:headers {"accept-encoding" "br"}}
                  {:encoders {"br" (ce/brotli-encoder {:quality 1})}})
        out      (ByteArrayOutputStream.)]
    (p/write-body-to-stream (:body response) response out)
    (is (= {:status 200
            :headers {"Content-Type"     "text/plain; charset=utf-8"
                      "Content-Encoding" "br"}}
           (dissoc response :body)))
    (is (= [-117 21 0 0 -128 -86 -86 -86 -22 -1 112 58 19 10 11 -94 114 75 126
            112 40 14 24 -57 6 112 1 14 43 -113 115 50 110 -5 -1 42 64 100]
           (vec (.toByteArray out))))))

(deftest zstd-content-encoding-test
  (let [response (ce/content-encoding-response
                  plain-response
                  {:headers {"accept-encoding" "zstd"}})
        out      (ByteArrayOutputStream.)]
    (p/write-body-to-stream (:body response) response out)
    (is (= {:status 200
            :headers {"Content-Type"     "text/plain; charset=utf-8"
                      "Content-Encoding" "zstd"}}
           (dissoc response :body)))
    (is (= [40 -75 47 -3 0 88 89 0 0 72 101 108 108 111 32 87 111 114 108 100]
           (vec (.toByteArray out))))))

(deftest zstd-content-encoding-params-test
  (let [response (ce/content-encoding-response
                  {:status  200
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body    "aaaaaaaaabbbbbbbbcccccdddeeeaaaabbbbbbbabbab"}
                  {:headers {"accept-encoding" "zstd"}}
                  {:encoders {"zstd" (ce/zstandard-encoder {:level 1})}})
        out      (ByteArrayOutputStream.)]
    (p/write-body-to-stream (:body response) response out)
    (is (= {:status 200
            :headers {"Content-Type"     "text/plain; charset=utf-8"
                      "Content-Encoding" "zstd"}}
           (dissoc response :body)))
    (is (= [40 -75 47 -3 0 72 -19 0 0 -104 97 97 98 99 99 99 99 99 100 100 100
            101 101 101 97 98 98 97 98 3 0 58 -115 -125 -52 50 -48 6]
           (vec (.toByteArray out))))))

(deftest different-content-encodings-test
  (let [response (ce/content-encoding-response
                  plain-response
                  {:headers {"accept-encoding" "gzip, deflate, identity"}})]
    (is (= {:status 200
            :headers {"Content-Type"     "text/plain; charset=utf-8"
                      "Content-Encoding" "gzip"}}
           (dissoc response :body)))))

(deftest weighted-encodings-test
  (let [response (ce/content-encoding-response
                  plain-response
                  {:headers {"accept-encoding" "gzip;q=0.5, identity"}})]
    (is (= {:status 200
            :headers {"Content-Type" "text/plain; charset=utf-8"}}
           (dissoc response :body)))))

(deftest remove-content-length-test
  (let [response
        (ce/content-encoding-response
         {:status 200
          :headers {"Content-Type"   "text/plain; charset=utf-8"
                    "Content-Length" "56"}
          :body    "This text is a little over the minimum compression size."}
         {:headers {"accept-encoding" "gzip"}})]
    (is (= {:status 200
            :headers {"Content-Type"     "text/plain; charset=utf-8"
                      "Content-Encoding" "gzip"}}
           (dissoc response :body)))))

(deftest minimum-content-length-test
  (let [response (ce/content-encoding-response
                  {:status 200
                   :headers {"Content-Type"   "text/plain; charset=utf-8"
                             "Content-Length" "11"}
                   :body    "Hello World"}
                  {:headers {"accept-encoding" "gzip"}})]
    (is (= {:status 200
            :headers {"Content-Type"   "text/plain; charset=utf-8"
                      "Content-Length" "11"}}
           (dissoc response :body)))))

(deftest images-not-compressed-test
  (let [response (ce/content-encoding-response
                  {:status 200
                   :headers {"Content-Type" "image/png"}
                   :body    (io/file "test/ring/middleware/test_image.png")}
                  {:headers {"accept-encoding" "gzip"}})]
    (is (= {:status 200
            :headers {"Content-Type" "image/png"}}
           (dissoc response :body)))))

(deftest error-messages-not-compressed-test
  (let [response  {:status  500
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body    "Internal Error"}]
    (is (= response
           (ce/content-encoding-response
            response
            {:headers {"accept-encoding" "gzip"}})))))
