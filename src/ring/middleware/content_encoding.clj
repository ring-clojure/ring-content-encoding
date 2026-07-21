(ns ring.middleware.content-encoding
  (:require [ring.core.protocols :as p])
  (:import [java.io OutputStream]
           [java.util.zip GZIPOutputStream]))

(defn gzip-encoder ^OutputStream [^OutputStream out]
  (GZIPOutputStream. out))

(def encoders
  {"gzip" gzip-encoder})

(defn- parse-accept-encoding [s]
  {s 1.0}) ;; simple case

(defn- best-encoding [encodings encoders]
  (->> encodings (sort-by val) (map key) (filter encoders) first))

(defn- encoded-body [body encoder]
  (reify p/StreamableResponseBody
    (write-body-to-stream [_ response out]
      (p/write-body-to-stream body response (encoder out)))))

(defn- apply-content-encoding [response [encoding encoder]]
  (-> response
      (assoc-in [:headers "Content-Encoding"] encoding)
      (update :body encoded-body encoder)))

(defn content-encoding-response [response request]
  (let [{{:strs [accept-encoding]} :headers} request
        encodings (parse-accept-encoding accept-encoding)]
    (if-some [encoding (best-encoding encodings encoders)]
      (apply-content-encoding response (find encoders encoding))
      response)))

(defn wrap-content-encoding [handler]
  (fn
    ([request]
     (-> (handler request)
         (content-encoding-response request)))
    ([request respond raise]
     (handler request
              #(respond (content-encoding-response % request))
              raise))))
