(ns ring.middleware.content-encoding
  (:require [clojure.string :as str]
            [ring.core.protocols :as p])
  (:import [com.github.luben.zstd ZstdOutputStream]
           [com.nixxcode.jvmbrotli.common BrotliLoader]
           [com.nixxcode.jvmbrotli.enc BrotliOutputStream Encoder$Parameters]
           [java.io OutputStream]
           [java.util.zip DeflaterOutputStream GZIPOutputStream]))

(defn brotli-encoder
  ([] (brotli-encoder {}))
  ([{:keys [quality window]}]
   (assert (BrotliLoader/isBrotliAvailable))
   (let [params (doto (Encoder$Parameters.)
                  (.setQuality (or quality -1))
                  (.setWindow  (or window -1)))]
     (fn [^OutputStream out]
       (BrotliOutputStream. out params)))))

(defn gzip-encoder ^OutputStream [^OutputStream out]
  (GZIPOutputStream. out))

(defn deflate-encoder ^OutputStream [^OutputStream out]
  (DeflaterOutputStream. out))

(defn zstandard-encoder
  ([] (zstandard-encoder {}))
  ([{:keys [chain-log hash-log job-size level long min-match overlap-log
            search-log strategy target-length window-log workers]}]
   (fn [^OutputStream out]
     (-> (ZstdOutputStream. out)
         (cond-> level         (.setLevel level))
         (cond-> long          (.setLong long))
         (cond-> workers       (.setWorkers workers))
         (cond-> overlap-log   (.setOverlapLog overlap-log))
         (cond-> job-size      (.setJobSize job-size))
         (cond-> target-length (.setTargetLength target-length))
         (cond-> min-match     (.setMinMatch min-match))
         (cond-> search-log    (.setSearchLog search-log))
         (cond-> chain-log     (.setChainLog chain-log))
         (cond-> hash-log      (.setHashLog hash-log))
         (cond-> window-log    (.setWindowLog window-log))
         (cond-> strategy      (.setStrategy strategy))))))

(def default-encoder-weights
  {"zstd"     5
   "br"       4
   "gzip"     3
   "deflate"  2
   "identity" 1})

(def default-encoder-minimums
  {"br"      50
   "deflate" 48
   "gzip"    48
   "zstd"    50})

(def default-encoders
  {"br"       (brotli-encoder)
   "deflate"  deflate-encoder
   "gzip"     gzip-encoder
   "identity" identity
   "zstd"     (zstandard-encoder)})

(def default-status-codes #{200 404 403})

(def default-media-types
  #{"application/eot"
    "application/font"
    "application/font-sfnt"
    "application/font-woff"
    "application/geo+json"
    "application/graphql+json"
    "application/javascript"
    "application/javascript-binast"
    "application/json"
    "application/ld+json"
    "application/manifest+json"
    "application/opentype"
    "application/otf"
    "application/rss+xml"
    "application/truetype"
    "application/ttf"
    "application/vnd.api+json"
    "application/vnd.ms-fontobject"
    "application/wasm"
    "application/x-javascript"
    "application/x-opentype"
    "application/x-otf"
    "application/x-protobuf"
    "application/x-ttf"
    "application/xhtml+xml"
    "application/xml"
    "font/otf"
    "font/ttf"
    "font/x-woff"
    "image/svg+xml"
    "image/vnd.microsoft.icon"
    "image/x-icon"
    "multipart/bag"
    "multipart/mixed"
    "text/css"
    "text/html"
    "text/javascript"
    "text/js"
    "text/plain"
    "text/richtext"
    "text/x-component"
    "text/x-java-source"
    "text/x-markdown"
    "text/x-script"
    "text/xml"})

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

(def ^:private re-content-type #"^[^\s;]+")

(defn- allowed-media-type? [{:keys [headers]} media-types]
  (when-some [content-type (some-> (find-header headers "content-type") val)]
    (when-some [media-type (re-find re-content-type content-type)]
      (contains? media-types media-type))))

(defn content-encoding-response
  ([response request] (content-encoding-response response request {}))
  ([{:keys [status] :as response}
    {{:strs [accept-encoding]} :headers}
    {:keys [encoders encoder-weights encoder-minimums media-types status-codes]
     :or {encoders         default-encoders
          encoder-weights  default-encoder-weights
          encoder-minimums default-encoder-minimums
          media-types      default-media-types
          status-codes     default-status-codes}}]
   (if (and (allowed-media-type? response media-types) (status-codes status))
     (let [encodings (parse-accept-encoding accept-encoding)]
       (if-some [encoding (best-encoding encodings encoders encoder-weights)]
         (if (= encoding "identity")
           response
           (let [encoder (find encoders encoding)]
             (apply-content-encoding response encoder encoder-minimums)))
         response))
     response)))

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
