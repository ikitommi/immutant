(ns immutant.web.perf.perf-test
  (:require [clojure.test :refer :all]
            [immutant.web.internal.undertow :as undertow]
            [testing.web :refer :all]
            [criterium.core :as cc])
  (:import (java.io File)))

(defn force-dispatch-bench []
  (let [file (File. ".")]
    (assert (not (nil? (#'undertow/force-dispatch? file))))

    ;; 3.5ms
    ;; 6.9µs (protocol dispatch)
    (cc/quick-bench
      (dotimes [_ 1000]
        (#'undertow/force-dispatch? file)))))

(comment
  (force-dispatch-bench))
