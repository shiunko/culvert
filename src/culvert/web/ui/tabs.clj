(ns culvert.web.ui.tabs
  "顶层 Tab 导航按钮的 Hiccup 生成，与具体领域无关。切换/URL 同步/键盘导航行为由 public/tabs.js 驱动。")

(defn tab-btn
  "生成一个顶层 `role=\"tab\"` 按钮。`id` 同时用于拼接 `tabbtn-<id>`/`tab-<id>` 两个 DOM id，
   与 `render-dashboard` 里对应的 `[:section {:id (str \"tab-\" id) ...}]` 保持命名一致。"
  [id icon label active?]
  [:button.pf-tab {:type "button"
                   :role "tab"
                   :id (str "tabbtn-" id)
                   :data-tab id
                   :aria-selected (if active? "true" "false")
                   :aria-controls (str "tab-" id)
                   :tabindex (if active? "0" "-1")}
   [:span {:aria-hidden "true"} icon]
   [:span.pf-tab-label label]])
