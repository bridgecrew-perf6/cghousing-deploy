;; Script to backup the cghousing postgress db to .sql files under
;;  ~/cghousing-deploy/data/db/backups/
;; This shoudld be run via crontab at 9am UTC (1am PSD) via:
;; * 9 * * * bb /home/rancher/cghousing-backups/bk.clj
(ns bk.core
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]))

(def backups-path "/home/rancher/cghousing-deploy/data/db/backups/")
(def backups-log-path "/home/rancher/cghousing-deploy/data/db/backups/backups.edn")
(def num-backups-to-keep 10)

(defn get-today-str []
  (.format (java.time.ZonedDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")))

(defn get-now-str []
  (.format (java.time.ZonedDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSZ")))

(defn get-backup-name [] (format "cg-db-%s.sql" (get-today-str)))
(defn get-backup-path [] (str backups-path "/" (get-backup-name)))

(defn current-backup-exists? []
  (-> (shell/sh "ls" (get-backup-path))
      :exit
      zero?))

(defn log [data]
  (spit backups-log-path (prn-str (merge data {:t (get-now-str)}))
        :append true))

(defn backup* []
  (if (current-backup-exists?)
    {:event :backup-skipped :location (get-backup-name) :cause :already-present}
    (do
      (shell/sh "docker" "exec" "cg-db" "sh" "-c"
        (format "pg_dump -U cgprod -F p cgprod > /data/backups/%s" (get-backup-name)))
      (if (current-backup-exists?)
        (do
          (shell/sh "sudo" "chown" "rancher:rancher" (get-backup-path))
          {:event :backup :location (get-backup-name)})
        {:event :backup-fail}))))

(defn is-bk-file? [fn] (re-find #"cg-db-\d{4}-\d{2}-\d{2}\.sql" fn))

(defn get-existing-backups []
  (let [{:keys [out exit]} (shell/sh "ls" backups-path)]
    (if (zero? exit)
      (->> out
           str/split-lines
           (map str/trim)
           (filter is-bk-file?))
      ())))

(defn get-bk-file-creation-date [fn]
  (->> fn
       (re-find #"cg-db-(\d{4})-(\d{2})-(\d{2})\.sql")
       next
       (mapv #(Long/parseLong %))))

(defn categorize-backups [backups]
  (let [sorted-backups
        (->> backups
             (map (juxt get-bk-file-creation-date identity))
             (sort-by first)
             reverse
             (map second))]
    {:keep (take num-backups-to-keep sorted-backups)
     :destroy (drop num-backups-to-keep sorted-backups)}))

(defn destroy-backup [fn]
  (if (-> (shell/sh "rm" (str backups-path "/" fn)) :exit zero?)
    (log {:event :destroy :patient fn})
    (log {:event :destroy-fail :patient fn})))

(defn destroy-old-backups [{:keys [destroy] :as ctx}]
  (doseq [fn destroy]
    (destroy-backup fn)))

(defn backup []
  (let [{:keys [event] :as assertion} (backup*)]
    (log assertion)
    (when (not= event :backup-fail)
      (-> (get-existing-backups)
          categorize-backups
          destroy-old-backups))))

(backup)
