(defproject org.ring-clojure/ring-content-encoding "0.1.0-SNAPSHOT"
  :description "Ring middleware for response content encoding (compression)"
  :url "https://github.com/ring-clojure/ring-content-encoding"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.ring-clojure/ring-core-protocols "1.15.5"]
                 [com.github.luben/zstd-jni "1.5.7-11"]
                 [com.nixxcode.jvmbrotli/jvmbrotli "0.2.0"]]
  :global-vars {*warn-on-reflection* true}
  :profiles
  {:dev {:dependencies [[ring/ring-jetty-adapter "1.15.5"]]}})
