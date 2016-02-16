(ns lein-repo.plugin
  (:use [clojure.java.io :only [copy file]])
  (:require [clojure.xml :as xml]
            [clojure.set :as set]
            [clojure.java.shell :as shell]
            leiningen.core.project)
  (:import [java.io File]
           [java.util HashMap Stack Queue]
           [java.util.jar Manifest JarEntry JarOutputStream]))

(set! *warn-on-reflection* true)

(defn pwd [] (System/getProperty "user.dir"))

(defn ^String projects-file [^String dir]
  (str dir "/projects.clj"))

(defn ^String find-repo-root [^String starting-dir]
  (cond (nil? starting-dir) (throw (RuntimeException. "projects.clj must be found in any enclosing directory in order to use the lein-repo plugin."))
        (.exists (File. (projects-file starting-dir))) starting-dir
        :else (recur (.getParent (File. starting-dir)))))

(defn mapify-deps [deps]
  (into {} (for [[lib versions] (group-by first deps)]
             (do (assert (= 1 (count versions))
                         (str "duplicate projects.clj dependency: " lib))
                 [lib (first versions)]))))

;; Always in the same mega-repo, so always the same root
(def repo-root (find-repo-root (pwd)))

(def repo-config
  (when repo-root
    (-> repo-root
        projects-file
        slurp
        read-string
        (update-in ['external-dependencies] mapify-deps))))

(def read-project-file
  (memoize
   (fn [project-name]
     (let [project-dir (get-in repo-config ['internal-dependencies project-name])
           _ (assert project-dir (format "internal dependency %s not declared in projects.clj" project-name))
           abs-project-dir (if (.isAbsolute (File. ^String project-dir))
                             project-dir
                             (str repo-root "/" project-dir))
           project-clj (str abs-project-dir "/project.clj")]
       (leiningen.core.project/read project-clj)))))

(defn topological-sort [child-map]
  (when (seq child-map)
    (let [sources (apply set/difference
                         (set (keys child-map))
                         (map set (vals child-map)))]
      (assert (seq sources))
      (concat (topological-sort (apply dissoc child-map sources))
              sources))))

(defn ordered-projects [project]
  (topological-sort
   (into {}
         (for [p (:internal-dependencies project)]
           [p (:internal-dependencies (read-project-file p))]))))

(defn distinct-deps
  "Distinctify deps, keeping the sole version of any repeated deps or
   the version marked with ^:force in projects, and otherwise
   throwing if there are multiple required versions."
  [deps]
  (for [[dep specs] (group-by first deps)]
    (or (when (= 1 (count (distinct specs)))
          (first specs))
        (when-let [forced (seq (distinct (filter #(:force (meta %)) specs)))]
          (assert (= 1 (count forced)) (str "Multiple forced specs: " forced))
          (first forced))
        (throw (RuntimeException.
                (str "\nCONFLICTING DEP: " dep " has "
                     (count specs) " versions required: "
                     (vec specs)))))))

(declare middleware)
(def read-middlewared-project-file (memoize #(middleware (read-project-file %))))

(defn merge-with-keyfn
  ([merge-fns] {})
  ([merge-fns m] m)
  ([merge-fns m1 m2]
   (into {}
         (for [k (distinct (concat (keys m1) (keys m2)))]
           [k
            (cond (not (contains? m1 k)) (get m2 k)
                  (not (contains? m2 k)) (get m1 k)
                  (merge-fns k) ((merge-fns k) (m1 k) (m2 k))
                  :else (m1 k))])))
  ([merge-fns m1 m2 & more]
   (reduce (partial merge-with-keyfn merge-fns) m1 (cons m2 more))))

(def concat-distinct
  (comp distinct concat))

(declare merge-projects)

(def project-merge-fns
  {:dependencies concat-distinct
   :external-dependencies concat-distinct
   :resource-paths concat-distinct
   :source-paths concat-distinct
   :java-source-paths concat-distinct
   :dev-dependencies concat-distinct
   :repositories merge
   :plugins concat-distinct
   :repl-options (fn [p1 p2] (merge-with concat-distinct p1 p2))
   :profiles (fn [p1 p2] (merge-with merge-projects p1 p2))

   ;; cljx specific
   :prep-tasks concat-distinct
   :cljx (fn [a b] {:builds (concat-distinct (:builds a) (:builds b))})})

(defn fix-cljx
  "Cljx paths are not properly normalized like other source paths; manually ensure they are
   absolute."
  [p]
  (if (and (:cljx p)
           (let [^String path (-> p :cljx :builds first :output-path)]
             (not (.startsWith path "/"))))
    (let [target ^String (:target-path p)
          dir (subs target 0 (- (count target) 6))]
      (assert (.endsWith target "target"))
      (update-in p [:cljx :builds]
                 (partial
                  mapv
                  (fn [m]
                    (-> m
                        (update-in [:output-path] #(str dir %))
                        (update-in [:source-paths] (fn [x] (mapv #(str dir %) x))))))))
    p))

(defn normalize-project [p]
  (-> p
      fix-cljx
      (update-in [:repositories] (partial into {}))))

(defn merge-projects [& projects]
  (->> projects
       (map normalize-project)
       (apply merge-with-keyfn project-merge-fns)))

(declare test-all-project*)

(defn middleware [my-project]
  (let [subprojects (keep read-middlewared-project-file (:internal-dependencies my-project))
        my-augmented-project (apply merge-projects my-project subprojects)
        {:keys [dependencies java-source-paths external-dependencies]} my-augmented-project
        lein-dep (->> my-project :plugins (filter #(= (name (first %)) "lein-repo")))
        deps (->> dependencies
                  (concat (get repo-config 'required-dependencies))
                  (concat (for [dep external-dependencies]
                            (let [spec (get-in repo-config ['external-dependencies dep])]
                              (assert spec (str "Missing external dep " dep))
                              spec)))
                  (concat lein-dep)
                  distinct-deps)]
    (-> my-project
        (merge my-augmented-project)
        (assoc
         :dependencies deps
         :direct-source-paths (:source-paths my-project)))))

(defn all-internal-deps []
  (keys (repo-config 'internal-dependencies)))

(defn test-all-project* [base]
  (let [internal-deps (all-internal-deps)
        internal-projs (map read-project-file internal-deps)
        test-paths (distinct (mapcat :test-paths internal-projs))]
    (-> base
        (assoc :internal-dependencies internal-deps
               :test-paths test-paths)
        middleware
        (update-in [:source-paths] concat test-paths)
        (dissoc :warn-on-reflection)
        (assoc :root repo-root
               :eval-in :subprocess))))

(def test-all-project
  (delay (test-all-project*
          (read-project-file (first (all-internal-deps))))))

(defn ordered-source-and-test-paths [& [no-tests?]]
  (let [mega-project @test-all-project
        blacklist []]
    (->> (ordered-projects mega-project)
         (mapcat (fn [p] (let [f (read-project-file p)]
                           (concat (when-not no-tests? (:test-paths f)) (:source-paths f)))))
         (remove (fn [^String n] (some #(>= (.indexOf n ^String %) 0) blacklist))))))

(set! *warn-on-reflection* false)
