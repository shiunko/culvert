(ns culvert.log
  "Structured logging with levels and timestamps.
   Usage:
     (require '[culvert.log :as log])
     (log/info \"Starting server on port\" port)
     (log/warn \"Retry attempt\" attempt)
     (log/error \"Connection failed\" (.getMessage e))")

;; ============================================================
;; Log Level & Configuration
;; ============================================================

(def ^:dynamic *log-level*
  "Current log level. One of :trace :debug :info :warn :error :fatal.
   Messages below this level are suppressed."
  :info)

(def ^:private level-priority
  {:trace 0 :debug 1 :info 2 :warn 3 :error 4 :fatal 5})

(def ^:private level-label
  {:trace "TRACE" :debug "DEBUG" :info "INFO " :warn "WARN " :error "ERROR" :fatal "FATAL"})

(def ^:private level-color
  {:trace "" :debug "" :info "\033[32m" :warn "\033[33m" :error "\033[31m" :fatal "\033[35m"})

(def ^:private color-reset "\033[0m")

;; ============================================================
;; Core Logging Function
;; ============================================================

(defn- format-timestamp []
  (java.time.LocalDateTime/now
   (java.time.ZoneId/of "Asia/Shanghai")))

(defn print-log! [level tag & msgs]
  (when (>= (get level-priority level) (get level-priority *log-level*))
    (let [ts (format-timestamp)
          prefix (str (get level-color level) "[" (get level-label level) "]"
                      color-reset " [" ts "] [" tag "] ")]
      (if (= 1 (count msgs))
        (println prefix (first msgs))
        (do
          (print prefix)
          (apply println msgs)))))
  (flush))

;; ============================================================
;; Public API Macros
;; ============================================================

(defmacro info [& args]
  `(print-log! :info ~(str *ns*) ~@args))

(defmacro warn [& args]
  `(print-log! :warn ~(str *ns*) ~@args))

(defmacro error [& args]
  `(print-log! :error ~(str *ns*) ~@args))

(defmacro debug [& args]
  `(print-log! :debug ~(str *ns*) ~@args))

(defmacro trace [& args]
  `(print-log! :trace ~(str *ns*) ~@args))

(defmacro fatal [& args]
  `(print-log! :fatal ~(str *ns*) ~@args))
