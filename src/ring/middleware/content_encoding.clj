(ns ring.middleware.content-encoding
  (:require [clojure.string :as str]
            [ring.core.protocols :as p])
  (:import [java.io OutputStream]
           [java.util.zip GZIPOutputStream]))

(defn gzip-encoder ^OutputStream [^OutputStream out]
  (GZIPOutputStream. out))

(def default-encoder-weights
  {"gzip" 2
   "identity" 1})

(def default-encoders
  {"gzip" gzip-encoder
   "identity" identity})

(def ^:private re-accept-encoding
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

(defn- encoding-comparator [encoder-weights]
  (fn [a b]
    (if (= (val a) (val b))
      (> (encoder-weights (key a)) (encoder-weights (key b)))
      (> (val a) (val b)))))

(defn- best-encoding [encodings encoders encoder-weights]
  (->> encodings
       (filter (comp encoders key))
       (sort (encoding-comparator encoder-weights))
       (some key)))

(defn- encoded-body [body encoder]
  (reify p/StreamableResponseBody
    (write-body-to-stream [_ response out]
      (p/write-body-to-stream body response (encoder out)))))

(defn- apply-content-encoding [response [encoding encoder]]
  (-> response
      (assoc-in [:headers "Content-Encoding"] encoding)
      (update :body encoded-body encoder)))

(defn content-encoding-response
  ([response request] (content-encoding-response response request {}))
  ([response
    {{:strs [accept-encoding]} :headers}
    {:keys [encoders encoder-weights]
     :or {encoders        default-encoders
          encoder-weights default-encoder-weights}}]
   (let [encodings (parse-accept-encoding accept-encoding)]
     (if-some [encoding (best-encoding encodings encoders encoder-weights)]
       (if (= encoding "identity")
         response
         (apply-content-encoding response (find encoders encoding)))
       response))))

(defn wrap-content-encoding
  ([handler] (wrap-content-encoding handler {}))
  ([handler options]
   (fn
     ([request]
      (-> (handler request)
          (content-encoding-response request options)))
     ([request respond raise]
      (handler request
               #(respond (content-encoding-response % request options))
               raise)))))
