(ns ring.middleware.content-encoding
  "Middleware for adding Content-Encoding to a response."
  (:require [clojure.string :as str]
            [ring.core.protocols :as p])
  (:import [com.github.luben.zstd ZstdOutputStream]
           [com.nixxcode.jvmbrotli.common BrotliLoader]
           [com.nixxcode.jvmbrotli.enc BrotliOutputStream Encoder$Parameters]
           [java.io OutputStream]
           [java.util.zip Deflater DeflaterOutputStream]
           [ring.middleware.content_encoding GZIPOutputStream]))

(defn brotli-encoder
  "Returns a function that adds [Brotli][] compression to an OutputStream.
  Accepts the following options:
  - `:quality` - compression quality
  - `:window` - log2(LZ window size)
  
  [brotli]: https://brotli.org/"
  ([] (brotli-encoder {}))
  ([{:keys [quality window]}]
   (assert (BrotliLoader/isBrotliAvailable))
   (let [params (doto (Encoder$Parameters.)
                  (.setQuality (or quality -1))
                  (.setWindow  (or window -1)))]
     (fn [^OutputStream out]
       (BrotliOutputStream. out params)))))

(defn gzip-encoder
  "Returns a function that adds GZip compression to an OutputStream.
  Accepts the following options:
  - `:level` - the compression level"
  ([] (gzip-encoder {}))
  ([{:keys [level]}]
   (fn [^OutputStream out]
     (GZIPOutputStream. out (or level Deflater/DEFAULT_COMPRESSION)))))

(defn deflate-encoder
  "Returns a function that adds DEFLATE compression to an OutputStream.
  Accepts the following options:
  - `:level` - the compression level"
  ([] (deflate-encoder {}))
  ([{:keys [level]}]
   (fn [^OutputStream out]
     (let [level (or level Deflater/DEFAULT_COMPRESSION)]
       (DeflaterOutputStream. out (Deflater. level))))))

(defn zstandard-encoder
  "Returns a function that adds [ZStandard][] compression to an OutputStream.
  Accepts the following basic options:
  - `:level` - the compression level

  And the following advanced options:
  - `:chain-log` - log2(multi-probe search table size)
  - `:hash-log` - log2(initial probe table size)
  - `:job-size` - size of compression job when workers > 1
  - `:long` - enable long distance matching
  - `:min-match` - min match size for long distance matcher
  - `:overlap-log` - overlap size as fraction of window size
  - `:search-log` - log2(number search attempts)
  - `:strategy` - see ZSTD_strategy enum definition
  - `:target-length` - depends on strategy
  - `:window-log` - log2(max allowed back-reference distance) 
  - `:workers` - number of worker threads
  
  [zstandard]: https://facebook.github.io/zstd/"
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
  "Weightings for which encoder to favor when the client has no preference.
  Higher values win out over lower ones."
  {"zstd"     5
   "br"       4
   "gzip"     3
   "deflate"  2
   "identity" 1})

(def default-encoder-minimums
  "The minimum size in bytes at which to apply the compression. Bodies that
  are too small typically don't benefit from compression."
  {"br"      50
   "deflate" 48
   "gzip"    48
   "zstd"    50})

(def default-encoders
  "A map of encoder names to their respective default functions."
  {"br"      (brotli-encoder)
   "deflate" (deflate-encoder)
   "gzip"    (gzip-encoder)
   "zstd"    (zstandard-encoder)})

(def default-status-codes
  "A set of default status codes that will get content encoding."
  #{200 404 403})

(def default-media-types
  "A set of default media types that will get content encoding. Media types that
  already have compression (such as images) should not be included in this set."
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
       (filter #(or (= (key %) "identity") (encoders (key %))))
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
  "Add Content-Encoding to a response, based on the types included in the
  Accept-Encoding header on the request. See [[wrap-content-encoding]] for
  information on the supported options."
  {:arglists '([response request] [response request options])}
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
  "Wrap a Ring handler to apply appropriate Content-Encoding to a response,
  based on the types included in the Accept-Encoding header on the request.
  Accepts the following options:
  - `:encoders` - a map of encoder names to functions that add encoding to
    OutputStreams.
  - `:encoder-weights` - a map of encoder names to numerial weights. When the
    client doesn't favor an encoder, the highest weighted encoder is used.
  - `:encoder-minimums` - a map of encoder names to the minimum size in bytes
    of the body (as set by the Content-Length header) at which that encoder is
    applied
  - `:media-types` - a set of media types that are allowed to have content
    encoding
  - `:status-codes` - a set of HTTP response status codes that are allowed to
    have content encoding"
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
