# Ring-Content-Encoding [![Build Status](https://github.com/ring-clojure/ring-content-encoding/actions/workflows/test.yml/badge.svg)](https://github.com/ring-clojure/ring-content-encoding/actions/workflows/test.yml)

[Ring][] middleware for adding modern response compression to your web
application. It supports [Brotli][], [Deflate][], [GZip][] and
[ZStandard][] content encoding, and has sensible defaults for when to
apply compression and when not avoid it (such as for image formats).

> [!NOTE]
> Many reverse proxies and CDNs will automatically handle content
> encoding for you. However, you may want more control over how content
> encoding is handled, or you may not be using a reverse proxy at all.

[ring]: https://github.com/ring-clojure/ring
[brotli]: https://github.com/google/brotli
[deflate]: https://datatracker.ietf.org/doc/html/rfc1951
[gzip]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Encoding#gzip
[zstandard]: https://facebook.github.io/zstd/

## Installation

Add the following dependency to your deps.edn file:

    org.ring-clojure/ring-content-encoding {:mvn/version "0.1.0-SNAPSHOT"}

Or to your Leiningen project file:

    [org.ring-clojure/ring-content-encoding "0.1.0-SNAPSHOT"]

## Usage

Use the `wrap-content-encoding` function to add support for various
types of content encoding (i.e. compression).

```clojure
(require '[ring.middleware.content-encoding :refer [wrap-content-encoding]])

(defn handler [_request]
  {:status  200
   :headers {"Content-Type"   "text/plain; charset=utf-8"
             "Content-Length" "55"}
   :body    "This text will be compressed if the client supports it."})

(def app
  (wrap-content-encoding handler))
```

## Documentation

- [API Documentation](https://ring-clojure.github.io/ring-content-encoding/ring.middleware.content-encoding.html)

## License

Copyright © 2026 James Reeves

Released under the MIT license.
