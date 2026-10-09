#!/usr/bin/env bb

(require '[clojure.edn :as edn]
         '[clojure.java.shell :refer [sh]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def work-dir "build-workspace")
(def public-dir "public")
(def cdn-base (or (System/getenv "CDN_BASE")
                  "https://paradise-chat.github.io/community-repo"))

(defn setup-dirs []
  (sh "rm" "-rf" work-dir)
  (sh "rm" "-rf" public-dir)
  (sh "mkdir" "-p" work-dir)
  (sh "mkdir" "-p" public-dir))

(defn process-manifest! [out-dir category item-name version-hash data]
  (let [manifest-file (str out-dir "/manifest.edn")]
    (if (.exists (io/file manifest-file))
      (do
        (let [manifest (edn/read-string (slurp manifest-file))
              raw-file-name (:path manifest)
              file-name (if (str/starts-with? raw-file-name "/")
                          (subs raw-file-name 1)
                          raw-file-name)]
          (spit manifest-file (pr-str (assoc manifest :path file-name))))

        (println "Successfully aggregated:" (name item-name))

        (merge data
               {:manifest-url (str cdn-base "/" (name category) "/" (name item-name) "/" version-hash "/manifest.edn")}))
      (do
        (println "ERROR: Missing manifest.edn in" out-dir)
        (System/exit 1)))))

(defn extract-module! [category item-name data]
  (let [{:keys [repository commit local-path]} data]
    (if local-path

      (let [out-dir (str public-dir "/" (name category) "/" (name item-name) "/local")]
        (println (str "Extracting LOCAL " (name category) " '" (name item-name) "' from " local-path))
        (sh "mkdir" "-p" out-dir)
        (let [cp-res (sh "cp" "-R" (str local-path "/dist/.") out-dir)]
          (if (not= 0 (:exit cp-res))
            (do (println "ERROR: Missing dist folder in" local-path ". Did you run your build script?")
                (System/exit 1))
            (process-manifest! out-dir category item-name "local" data))))


      (let [item-dir (str work-dir "/" (name category) "/" (name item-name))
            out-dir (str public-dir "/" (name category) "/" (name item-name) "/" commit)]
        (println (str "Extracting REMOTE " (name category) " '" (name item-name) "' at commit " commit))
        (sh "mkdir" "-p" item-dir)
        (sh "mkdir" "-p" out-dir)
        (let [clone-res (sh "git" "clone" "--no-checkout" repository item-dir)]
          (if (not= 0 (:exit clone-res))
            (do (println "ERROR: Failed to clone repository:" repository)
                (System/exit 1))
            (let [checkout-res (sh "git" "-C" item-dir "checkout" commit)]
              (if (not= 0 (:exit checkout-res))
                (do (println "ERROR: Commit hash" commit "does not exist in" repository)
                    (System/exit 1))
                (let [cp-res (sh "cp" "-R" (str item-dir "/dist/.") out-dir)]
                  (if (not= 0 (:exit cp-res))
                    (do (println "ERROR: Missing dist folder in" item-name "at commit" commit)
                        (System/exit 1))
                    (process-manifest! out-dir category item-name commit data)))))))))))

(defn process-dir [dir-name]
  (let [dir (io/file dir-name)
        category (keyword dir-name)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (reduce (fn [acc file]
                     (let [item-name (keyword (str/replace (.getName file) #"\.edn$" ""))
                           data (edn/read-string (slurp file))]
                       (if (or (:local-path data)
                               (and (:repository data) (:commit data)))
                         (assoc acc item-name (extract-module! category item-name data))
                         (do
                           (println "ERROR: File" (.getName file) "must have :local-path OR both :repository and :commit")
                           (System/exit 1)))))
                   {}))
      {})))

(defn -main []
  (println "Starting Paradise Registry Aggregation...")
  (setup-dirs)
  (let [registry {:engines (process-dir "engines")
                  :plugins (process-dir "plugins")}]
    (spit (str public-dir "/registry.edn") (pr-str registry))
    (println "All modules aggregated successfully into public/")
    (println "Master public/registry.edn generated!")
    (System/exit 0)))

(-main)
