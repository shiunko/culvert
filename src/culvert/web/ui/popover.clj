(ns culvert.web.ui.popover
  "轻量“？”图标按钮 + 点击展开的说明气泡，与具体领域无关。交互由 public/tabs.js 驱动（data-action=\"popover.toggle\"），
   默认隐藏，点击切换（不用 hover，保证移动端可用）。")

(defn popover
  "生成一个“？”触发按钮及其关联的说明内容块（默认隐藏）。
   id 用于关联 aria-describedby 与内容块 DOM id；content 是气泡内的 hiccup 内容，由调用方提供，本函数不关心其内容。"
  [{:keys [id content]}]
  [:span.pf-popover-wrap
   [:button.pf-popover-trigger {:type "button"
                                :aria-describedby (str id "-content")
                                :aria-expanded "false"
                                :data-action "popover.toggle"
                                :data-popover id
                                :title "说明"}
    "?"]
   [:div.pf-popover-content {:id (str id "-content") :role "tooltip" :hidden true}
    content]])
