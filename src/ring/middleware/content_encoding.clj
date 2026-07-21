(ns ring.middleware.content-encoding
  (:require [clojure.string :as str]
            [ring.core.protocols :as p])
  (:import [java.io OutputStream]
           [java.util.zip GZIPOutputStream]))

(defn gzip-encoder ^OutputStream [^OutputStream out]
  (GZIPOutputStream. out))

(def encoders
  {"gzip" gzip-encoder
   "identity" identity})

(def re-accept-encoding
  #"(?x)
    ([!\#$%&'*\-+.0-9A-Z\^_`a-z\|~]+)              # token
    (?:\s*;\s*q=(0(?:\.\d{0,3})?|1(?:\.0{0,3})))?  # weight")

(defn- assoc-encoding [encodings enc-str]
  (if-some [[_ enc weight] (re-matches re-accept-encoding enc-str)]
    (assoc encodings enc (if weight (Double/parseDouble weight) 1.0))
    encodings))
    
(defn- parse-accept-encoding [header]
  (when header
    (reduce assoc-encoding {} (str/split header #"\s*,\s*"))))

(defn- best-encoding [encodings encoders]
  (->> encodings (sort-by val) reverse (map key) (filter encoders) first))

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
      (if (= encoding "identity")
        response
        (apply-content-encoding response (find encoders encoding)))
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
