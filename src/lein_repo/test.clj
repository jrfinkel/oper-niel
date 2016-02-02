(ns lein-repo.test
  (:require
   [clojure.test :as ct]
   [clojure.tools.namespace.find :as namespace-find]
   [lazytest.tracker :as tracker]))

;; Import all clojure.test stuff
(doseq [v (keys (ns-publics (find-ns 'clojure.test)))]
  (when-not (or (#{"deftest" "deftest-"} (name v))
                (.startsWith ^String (name v) "*"))
    (let [ct-sym (symbol "clojure.test" (name v))]
      (eval `(alter-meta!
              (def ~v @(var ~ct-sym))
              #(merge % (meta (var ~ct-sym))))))))

(def ^:dynamic *time-tests* false)
(def ^:dynamic *report-ns-results* false)

(defmacro with-out-strs [& body]
  `(let [w# (java.io.StringWriter.)]
     (binding [*out* w *err* w]
       ~@body
       (str w#))))

;; Following functions adapted from clojure.test
(defn test-vars [ns]
  (let [once-fixture-fn (ct/join-fixtures (::ct/once-fixtures (meta ns)))
        each-fixture-fn (ct/join-fixtures (::ct/each-fixtures (meta ns)))]
    (once-fixture-fn
     (fn []
       (doseq [v (vals (ns-interns ns))]
         (when (and (:test (meta v)))
           (assert (<= (count (filter (meta v) [:unit :system :integration])) 1))
           (let [weird-keys
                 (remove #{:ns :name :file :line :test :column}
                         (keys (meta v)))]
             (when (seq weird-keys)
               (println "WARNING: got unexpected metadata keys" weird-keys "for" (meta v))))
           (if *time-tests*
             (let [start (System/currentTimeMillis)]
               (each-fixture-fn (fn [] (ct/test-var v)))
               (let [elapsed (- (System/currentTimeMillis) start)]
                 (when (> elapsed 20)
                   (println elapsed "\tms for test" v))))
             (each-fixture-fn (fn [] (ct/test-var v))))))))))

(defn test-ns [ns]
  (binding [ct/*report-counters* (ref ct/*initial-report-counters*)]
    (let [ns-obj (the-ns ns)]
      (do-report {:type :begin-test-ns, :ns ns-obj})
      (test-vars ns-obj)
      (let [rep @ct/*report-counters*
            {:keys [test pass fail error]} rep]
        (when *report-ns-results*
          (println (format "%s pass, %s fail, %s error"
                           (- test fail error) fail error))))
      (do-report {:type :end-test-ns, :ns ns-obj}))
    @ct/*report-counters*))

(defn run-tests [namespaces]
  (let [summary (assoc (apply merge-with + (map test-ns namespaces))
                       :type :summary)]
    (do-report summary)
    (.flush *out*)
    summary))

(defn test-dirs [dirs &
                 {:keys [preprocess-nss skip-system-tests?]
                  :or {preprocess-nss (partial filter #(.endsWith ^String (name %) "-test"))}}]
  (println "Testing on dirs:"  dirs)
  (let [namespaces (sort (mapcat #(namespace-find/find-namespaces-in-dir (java.io.File. %)) dirs))]
    (apply require namespaces)
    (run-tests (preprocess-nss namespaces))))
