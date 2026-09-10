(ns culvert.web.ui.drawer
  "可复用的右侧滑入 Drawer 壳，与具体领域无关。行为由 public/tabs.js驱动（data-action=\"drawer.open|close\"），
   本命名空间只负责壳结构的 Hiccup 生成。")

(defn drawer
  "生成一个 Drawer 的透明遮盖层 + 滑入面板，返回二元组成的 list。
   id   — Drawer 根节点 id，同时作为 data-drawer 取值，遮盖层 id 为 (str id \"-backdrop\")；
   title — 面板头部标题文本；
   body  — 面板主体内容（通常是一个 [:form.pf-drawer-body ...]），由调用方提供，本函数不关心其内容。"
  [{:keys [id title body]}]
  (list
   [:div.pf-drawer-backdrop {:id (str id "-backdrop") :hidden true :data-action "drawer.close" :data-drawer id}]
   [:aside.pf-drawer {:id id :hidden true :role "dialog" :aria-modal "true" :aria-labelledby (str id "-title")}
    [:div.pf-drawer-header
     [:h3 {:id (str id "-title")} title]
     [:button.pf-drawer-close {:type "button" :data-action "drawer.close" :data-drawer id} "×"]]
    body]))
