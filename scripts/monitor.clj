;; Monitor the cghousing app to make sure it is running. Write the outcome to
;; the log file passed as first argument. Send emails to the email address
;; passed as second argument. Note: requires a modern Babashka executable with
;; access to pods, e.g., 0.2.6.
;;
;; Usage::
;;
;;     $ set -x CG_MONITOR_EMAIL_USER SOME_USERNAME
;;     $ set -x CG_MONITOR_EMAIL_PASSWORD SOME_PASSWORD
;;     $ bb scripts/monitor.clj \
;;         /path/to/cghousing-deploy/private/monitor-log.edn \
;;         youremail@server.com
;;
;; Under the above invocation, data will be written to the .edn file passed as
;; first argument and any emails will be sent from GMail user
;; CG_MONITOR_EMAIL_USER to youremail@server.com.
;; Typically, the command above should be scheduled to run at regular
;; intervals using cron.

(ns monitor
  (:require [babashka.curl :as curl]
            [babashka.pods :as pods]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(pods/load-pod 'tzzh/mail "0.0.2")
(require '[pod.tzzh.mail :as m])

(def *debug* false) ;; set to true to prevent email sending
(def default-log-path "~/cghousing-monitor.edn")

(defn parse-datetime-str [t]
  (java.time.ZonedDateTime/parse (->> t (take 26) (apply str))))

(defn get-now-str []
  (.format (java.time.ZonedDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSZ")))

(defn log [log-path data]
  (spit log-path (prn-str (merge data {:t (get-now-str)}))
        :append true))

(def urls-that-should-work
  ["http://www.cghousing.org/"
   "https://www.cghousing.org/"
   "https://cghousing.org/"
   "http://cghousing.org/"
   "https://cghousing.org/page/contact-us/"])

(def urls-that-should-NOT-work
  ["http://www.zghousing.org/"
   "https://www.zghousing.org/"
   "https://cghousing.org/abc"
   "http://cghousing.org/abc"
   "https://cghousing.org/page/zontact-us/"])

(defn status-kw [d] (some->> d :status (str "status-") keyword))

(defn get-status [url]
  (try (-> url curl/get status-kw)
       (catch clojure.lang.ExceptionInfo e
         (or (status-kw (ex-data e)) :status-unknown))
       (catch Exception _ :status-unknown)))

(defn log-failure [log-path bad-requests]
  (let [bad-requests (->> bad-requests
                          vals
                          (apply concat)
                          sort)]
    (log log-path {:event :not-running :request-count (count bad-requests)})
    (doseq [[url status] bad-requests]
      (log log-path {:event :no-response-for :theme url :manner status}))))

(defn log-outcome [log-path {:keys [status-200] :as outcome}]
  (let [bad-requests (dissoc outcome :status-200)]
    (if (seq bad-requests)
      (log-failure log-path bad-requests)
      (log log-path {:event :running :request-count (count status-200)})))
  outcome)

(defn explain-outcome [outcome]
  (let [bad-requests (dissoc outcome :status-200)]
    (if (seq bad-requests)
      (str
       (->> bad-requests
            vals
            (apply concat)
            (map (fn [[url status]] (format "- GET request to '%s' returned status '%s'."
                                            url (-> status name (str/replace #"status-" "")))))
            (str/join "\n"))
       "\n\n"))))

(defn send-email
  "Send an email to ``to`` with eponymous subject and text"
  [to subject text]
  (let [f (if *debug* (comp println :text) m/send-mail)]
    (f {:host "smtp.gmail.com"
        :port 587
        :username (System/getenv "CG_MONITOR_EMAIL_USER")
        :password (System/getenv "CG_MONITOR_EMAIL_PASSWORD")
        :subject subject
        :from (System/getenv "CG_MONITOR_EMAIL_USER")
        :to [to]
        :text text})))

(defn extract-transition-
  [events]
  (->> events
       (reduce (fn [{:keys [current-state previous-state] :as agg}
                    {:keys [event] :as e}]
                 (if (and current-state previous-state)
                   (reduced agg)
                   (if (and (not current-state)
                            (some #{event} [:running :not-running]))
                     (assoc agg :current-state e)
                     (if (and (not previous-state)
                              (some #{event} [:running :not-running]))
                       (assoc agg :previous-state e)
                       (if (= event :emailing)
                         (assoc agg :emailing e)
                         e))))))))

(defn extract-transition*
  [events]
  (reduce (fn [{:keys [current-state previous-state] :as agg}
               {:keys [event] :as e}]
            (cond
              (and current-state previous-state)
              (reduced agg)
              (and (not current-state)
                   (some #{event} [:running :not-running]))
              (assoc agg :current-state e)
              (and (not previous-state)
                   (not= (:event current-state) event)
                   (some #{event} [:running :not-running]))
              (assoc agg :previous-state e)
              (= event :emailing) (update agg :emailings conj e)
              :else agg))
          {}
          events))

(defn process-reader [reader]
  (->> reader
       line-seq
       reverse
       (map read-string)))

(defn extract-transition [log-path]
  (with-open [rdr (clojure.java.io/reader log-path)]
    ((comp extract-transition* process-reader) rdr)))

(defn measure-transition [{:keys [current-state previous-state] :as transition}]
  (assoc
   transition
   :hours
   (let [current-time (parse-datetime-str (:t current-state))
         transition-time (parse-datetime-str (:t previous-state))]
     (.toHours (java.time.Duration/between transition-time current-time)))))

(def sla-violation-1-threshold 72)
(def escalation-threshold 24)
(defn sla-violation-1-email? [{:keys [status]}] (= status ::SLA-violation-1))
(defn escalation-email? [{:keys [status]}] (= status ::escalation))
(defn alert-email? [{:keys [status]}] (= status ::alert))
(defn recovery-email? [{:keys [status]}] (= status ::recovered))

(defn get-failure-status [log-path]
  (let [{{current-state :event} :current-state
         emailings :emailings
         hours :hours} (measure-transition (extract-transition log-path))]
    (when (= current-state :not-running)
      (cond
        (and (> hours sla-violation-1-threshold)
             (not (some sla-violation-1-email? emailings)))
        ::SLA-violation-1
        (and (> hours escalation-threshold)
             (not (some escalation-email? emailings)))
        ::escalation
        (not (some alert-email? emailings))
        ::alert))))

(defmulti generate-failure-email-content (fn [failure-status _] failure-status))

(defmethod generate-failure-email-content ::alert
  [_ outcome]
  ["Alert! CGHousing.org has gone Down!"
   (str "Alert! CGHousing.org is Down!\n\n"
        "Be advised that cghousing is experiencing an outage.\n\n"
        (explain-outcome outcome)
        "Good heavens!\n\n"
        "-CGHousingMonitorBot")])

(defmethod generate-failure-email-content ::escalation
  [_ outcome]
  ["Escalation! CGHousing.org has been down for over a day!"
   (str "Escalation! CGHousing.org has been down for over a day!\n\n"
        "Be advised that the cghousing outage has lasted more than 24 hours!\n\n"
        (explain-outcome outcome)
        "The sky is truly falling on our heads!!\n\n"
        "-CGHousingMonitorBot")])

(defmethod generate-failure-email-content ::SLA-violation-1
  [_ outcome]
  ["SLA Violation! CGHousing.org has been down for over three days!"
   (str "SLA Violation! CGHousing.org has been down for over three days!\n\n"
        "Be advised that the cghousing outage has exceeded 72 hours!\n\n"
        (explain-outcome outcome)
        "You should be ashamed of yourself!!! With love,\n\n"
        "CGHousingMonitorBot")])

(defmulti generate-recovery-email-content (fn [recovery-status _] recovery-status))

(defmethod generate-recovery-email-content ::recovered
  [& _]
  ["CGHousing.org is up and running again---phew! :)"
   (str "CGHousing.org is up and running again---phew! :)\n\n"
        "Felicitously, cghousing has recovered from the recent outage.\n\n"
        "Good work operator!\n\n"
        "-CGHousingMonitorBot")])

(defn get-recovery-status [log-path]
  (let [{{current-state :event} :current-state
         emailings :emailings} (extract-transition log-path)]
    (when (and (= current-state :running)
               (not (some recovery-email? emailings)))
      ::recovered)))

(defn generate-email-content
  [log-path outcome]
  (let [bad-requests (dissoc outcome :status-200)
        failure-status (and (seq bad-requests) (get-failure-status log-path))
        recovery-status (and (not (seq bad-requests)) (get-recovery-status log-path))]
    (cond
      failure-status {:content (generate-failure-email-content failure-status outcome)
                      :status failure-status}
      recovery-status {:content (generate-recovery-email-content recovery-status outcome)
                       :status recovery-status}
      :else nil)))

(defn email-outcome
  "Send an email only if we need to. Consult our state at log-path to determine if so."
  [log-path email-address outcome]
  (when-let [email (generate-email-content log-path outcome)]
    (let [{:keys [content status]} email]
      (apply send-email email-address content)
      (log log-path {:event :emailing :status status}))))

(defn init [log-path]
  (when-not (->> log-path (shell/sh "ls") :exit zero?)
    (log log-path {:event :running})))

(defn monitor! []
  (let [log-path (or (first *command-line-args*) default-log-path)
        email-address (second *command-line-args*)]
    (init log-path)
    (->> urls-that-should-work
         (pmap (juxt identity get-status))
         (group-by second)
         (log-outcome log-path)
         (email-outcome log-path email-address))))

(defn run-tests []
  (let [base
        [{:event :not-running :t "2021-01-03T23:42:02.952-0800"}
         {:event :running :t "2021-01-03T23:42:02.962-0800"}]
        cases
        [[::alert
          [{:event :not-running :t "2021-01-03T23:42:56.268-0800"}]]
         [nil
          [{:event :emailing :status ::alert :t "2021-01-03T23:42:56.268-0800"}
           {:event :not-running :t "2021-01-03T23:42:56.268-0800"}]]
         [::escalation
          [{:event :emailing :status ::alert :t "2021-01-05T23:42:56.268-0800"}
           {:event :not-running :t "2021-01-05T23:42:56.268-0800"}]]
         [nil
          [{:event :emailing :status ::alert :t "2021-01-03T23:42:56.268-0800"}
           {:event :emailing :status ::escalation :t "2021-01-05T23:42:56.268-0800"}
           {:event :not-running :t "2021-01-05T23:42:56.268-0800"}]]
         [::SLA-violation-1
          [{:event :emailing :status ::alert :t "2021-01-05T23:42:56.268-0800"}
           {:event :emailing :status ::escalation :t "2021-01-05T23:42:56.268-0800"}
           {:event :not-running :t "2021-01-07T23:42:56.268-0800"}]]
         [nil
          [{:event :emailing :status ::alert :t "2021-01-05T23:42:56.268-0800"}
           {:event :emailing :status ::escalation :t "2021-01-05T23:42:56.268-0800"}
           {:event :emailing :status ::SLA-violation-1 :t "2021-01-05T23:42:56.268-0800"}
           {:event :not-running :t "2021-01-07T23:42:56.268-0800"}]]]]
    (every?
     true?
     (map (fn [[expectation events]]
            (with-redefs [process-reader
                          (constantly (-> base (concat events) reverse))]
              (= expectation
                 (:status
                  (generate-email-content
                   (or (first *command-line-args*) default-log-path)
                   {:status-400 [["http://cghousing.org/abc" :status-400]]})))))
          cases))))

(monitor!)
#_(run-tests)
