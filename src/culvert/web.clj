(ns culvert.web
  "Compatibility namespace: re-exports all public API from the split sub-namespaces.
   New code should require culvert.web.core / .views / .handlers / .server directly."
  (:require [culvert.web.core :as core]
            [culvert.web.handlers :as handlers]
            [culvert.web.server :as server]
            [culvert.web.views :as views]))

;; ============================================================
;; Re-exports from culvert.web.core
;; ============================================================

;; CSRF
(def csrf-generate core/csrf-generate)
(def csrf-validate core/csrf-validate)
(def get-csrf-token core/get-csrf-token)

;; Rate limiting
(def rate-limit-store core/rate-limit-store)
(def rate-limit-max-requests core/rate-limit-max-requests)
(def rate-limit-allowed? core/rate-limit-allowed?)
(def wrap-rate-limit core/wrap-rate-limit)

;; Security headers
(def wrap-security-headers core/wrap-security-headers)

;; Request parsing
(def parse-query-string core/parse-query-string)
(def parse-form-body core/parse-form-body)

;; Response helpers
(def json-response core/json-response)

;; Static files
(def serve-static-file core/serve-static-file)

;; Auth
(def parse-basic-auth core/parse-basic-auth)
(def wrap-auth core/wrap-auth)

;; ============================================================
;; Re-exports from culvert.web.views
;; ============================================================

(def compute-usage views/compute-usage)
(def render-quota-bar-content views/render-quota-bar-content)
(def render-users-tbody views/render-users-tbody)
(def render-forwards-tbody views/render-forwards-tbody)
(def render-caddy-tbody views/render-caddy-tbody)
(def render-docker-tbody views/render-docker-tbody)

;; ============================================================
;; Re-exports from culvert.web.handlers
;; ============================================================

(def route-dispatcher handlers/route-dispatcher)

;; ============================================================
;; Re-exports from culvert.web.server
;; ============================================================

(def start-server! server/start-server!)
(def stop-server! server/stop-server!)
