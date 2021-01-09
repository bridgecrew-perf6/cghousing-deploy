(ns manage
  "Manage the docker containers for cghousing in prod"
  (:require [clojure.java.shell :as shell]))

(defn stop-nginx [] (shell/sh "docker" "stop" "opd-nginx"))

(defn start-nginx [] (shell/sh "docker" "start" "opd-nginx"))

(defn run-nginx []
  (shell/sh
   "docker"
   "run"
   "-d"
   "--network" "opd"
   "-p" "80:80"
   "-p" "443:443"
   "--name" "opd-nginx"
   "-v" "/home/rancher/old-pyramid-deployment/src/nginx/var/www/html:/var/www/html"
   "-v" "/home/rancher/nginx/etc/letsencrypt:/etc/letsencrypt"
   "-v" "/home/rancher/old-pyramid-deployment/src/nginx/etc/nginx.conf:/etc/nginx/nginx.conf"
   "-v" "/home/rancher/old-pyramid-deployment/src/nginx/etc/old:/etc/nginx/sites-enabled/old"
   "-v" "/home/rancher/cghousing-deploy/src/nginx/etc/cg:/etc/nginx/sites-enabled/cg"
   "-v" "/home/rancher/cghousing-deploy/data/static:/cghousing-static"
   "opd-nginx:1.0"))

(defn help []
  (println (str "help\n"
                "run-nginx\n"
                "start-nginx\n"
                "stop-nginx\n")))

(def registry
  {:stop-nginx stop-nginx
   :start-nginx start-nginx
   :run-nginx run-nginx
   :help help})

(defn main []
  (let [f (or (-> *command-line-args* first keyword registry) help)]
    (f)))

(main)
