(ns ring.middleware.content-encoding)

(defn wrap-content-encoding [handler]
  (fn
    ([request]
     (handler request))
    ([request respond raise]
     (handler request respond raise))))
