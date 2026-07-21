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

(def default-encoder-minimums
  {"gzip" 48})

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

(defn- find-header [headers ^String name]
  (some #(when (.equalsIgnoreCase name (key %)) %) headers))

(defn- under-minimum-length? [content-length min-length]
  (and content-length (>= min-length (Long/parseLong (val content-length)))))

(defn- apply-content-encoding
  [{:keys [headers] :as response} [encoding encoder] encoder-mins]
  (let [content-length (find-header headers "content-length")
        min-length     (encoder-mins encoding)]
    (if (under-minimum-length? content-length min-length)
      response
      (-> response
          (assoc-in [:headers "Content-Encoding"] encoding)
          (cond-> content-length
            (update :headers dissoc (key content-length)))
          (update :body encoded-body encoder)))))

(defn content-encoding-response
  ([response request] (content-encoding-response response request {}))
  ([response
    {{:strs [accept-encoding]} :headers}
    {:keys [encoders encoder-weights encoder-minimums]
     :or {encoders         default-encoders
          encoder-weights  default-encoder-weights
          encoder-minimums default-encoder-minimums}}]
   (let [encodings (parse-accept-encoding accept-encoding)]
     (if-some [encoding (best-encoding encodings encoders encoder-weights)]
       (if (= encoding "identity")
         response
         (let [encoder (find encoders encoding)]
           (apply-content-encoding response encoder encoder-minimums)))
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
