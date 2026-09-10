(ns culvert.web.ui.skeleton
  "骨架屏占位组件，与具体领域无关。供 Tab 首次惰加载（见 docs/12 §14.3）的容器在首屏作为初始内容使用，
   真实数据到达后由 htmx 换掉。")

(defn table-rows
  "生成 n 行表格骨架占位，每行一个跨 colspan 列的闪光条。"
  [colspan n]
  (for [_ (range n)]
    [:tr [:td {:colspan colspan :class "pf-cell-plain"} [:div.pf-skeleton.pf-skeleton-row]]]))

(defn resource-cards
  "生成 n 张卡片骨架占位，尺寸与真实 resource-card 同量级。"
  [n]
  (for [_ (range n)]
    [:div.pf-resource-card [:div.pf-skeleton.pf-skeleton-card]]))
