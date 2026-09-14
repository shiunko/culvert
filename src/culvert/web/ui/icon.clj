(ns culvert.web.ui.icon
  "内联 SVG 图标库（lucide 图标子集，ISC 许可），与具体领域无关。
   只返回 hiccup 结构，不读取 config/i18n/领域状态；用于替换旧版 emoji 功能图标，
   避免 emoji 在深色背景下不可见、且不引入任何前端构建依赖。
   图标名采用 lucide 的 kebab-case 关键字，具体清单见 `paths`。")

(def ^:private paths
  "图标名 -> SVG 子元素序列（不含外层 `<svg>`）。"
  {:layout-dashboard [[:rect {:width 7 :height 9 :x 3 :y 3 :rx 1}]
                      [:rect {:width 7 :height 5 :x 14 :y 3 :rx 1}]
                      [:rect {:width 7 :height 9 :x 14 :y 12 :rx 1}]
                      [:rect {:width 7 :height 5 :x 3 :y 16 :rx 1}]]
   :arrow-right-left [[:path {:d "m16 3 4 4-4 4"}]
                      [:path {:d "M20 7H4"}]
                      [:path {:d "m8 21-4-4 4-4"}]
                      [:path {:d "M4 17h16"}]]
   :box [[:path {:d "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"}]
         [:path {:d "m3.3 7 8.7 5 8.7-5"}]
         [:path {:d "M12 22V12"}]]
   :server [[:rect {:width 20 :height 8 :x 2 :y 2 :rx 2}]
            [:rect {:width 20 :height 8 :x 2 :y 14 :rx 2}]
            [:path {:d "M6 6h.01"}]
            [:path {:d "M6 18h.01"}]]
   :settings [[:path {:d "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"}]
              [:circle {:cx 12 :cy 12 :r 3}]]
   :search [[:circle {:cx 11 :cy 11 :r 8}]
            [:path {:d "m21 21-4.3-4.3"}]]
   :monitor [[:rect {:width 20 :height 14 :x 2 :y 3 :rx 2}]
             [:path {:d "M8 21h8"}]
             [:path {:d "M12 17v4"}]]
   :zap [[:path {:d "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"}]]
   :activity [[:path {:d "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"}]]
   :user [[:path {:d "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"}]
          [:circle {:cx 12 :cy 7 :r 4}]]
   :plus [[:path {:d "M5 12h14"}]
          [:path {:d "M12 5v14"}]]
   :x [[:path {:d "M18 6 6 18"}]
       [:path {:d "m6 6 12 12"}]]
   :alert-triangle [[:path {:d "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"}]
                    [:path {:d "M12 9v4"}]
                    [:path {:d "M12 17h.01"}]]
   :alert-circle [[:circle {:cx 12 :cy 12 :r 10}]
                  [:path {:d "M12 8v4"}]
                  [:path {:d "M12 16h.01"}]]
   :check-circle [[:path {:d "M21.801 10A10 10 0 1 1 17 3.335"}]
                  [:path {:d "m9 11 3 3L22 4"}]]
   :info [[:circle {:cx 12 :cy 12 :r 10}]
          [:path {:d "M12 16v-4"}]
          [:path {:d "M12 8h.01"}]]
   :refresh-cw [[:path {:d "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"}]
                [:path {:d "M21 3v5h-5"}]
                [:path {:d "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"}]
                [:path {:d "M8 16H3v5"}]]
   :external-link [[:path {:d "M15 3h6v6"}]
                   [:path {:d "M10 14 21 3"}]
                   [:path {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]]
   :terminal [[:path {:d "m4 17 6-6-6-6"}]
              [:path {:d "M12 19h8"}]]
   :clock [[:circle {:cx 12 :cy 12 :r 10}]
           [:path {:d "M12 6v6l4 2"}]]
   :layers [[:path {:d "m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z"}]
            [:path {:d "m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65"}]
            [:path {:d "m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"}]]
   :play [[:path {:d "M6 3v18l15-9z"}]]
   :pause [[:rect {:width 4 :height 16 :x 14 :y 4 :rx 1}]
           [:rect {:width 4 :height 16 :x 6 :y 4 :rx 1}]]
   :square [[:rect {:width 18 :height 18 :x 3 :y 3 :rx 2}]]
   :pencil [[:path {:d "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"}]
            [:path {:d "m15 5 4 4"}]]
   :trash-2 [[:path {:d "M3 6h18"}]
             [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
             [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
             [:path {:d "M10 11v6"}]
             [:path {:d "M14 11v6"}]]
   :file-text [[:path {:d "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"}]
               [:path {:d "M14 2v4a2 2 0 0 0 2 2h4"}]
               [:path {:d "M10 9H8"}]
               [:path {:d "M16 13H8"}]
               [:path {:d "M16 17H8"}]]
   :package [[:path {:d "m7.5 4.27 9 5.15"}]
             [:path {:d "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"}]
             [:path {:d "m3.3 7 8.7 5 8.7-5"}]
             [:path {:d "M12 22V12"}]]
   :eye [[:path {:d "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"}]
         [:circle {:cx 12 :cy 12 :r 3}]]
   :key [[:circle {:cx 7.5 :cy 15.5 :r 5.5}]
         [:path {:d "m21 2-9.6 9.6"}]
         [:path {:d "m15.5 7.5 3 3L22 7l-3-3"}]]
   :cpu [[:rect {:width 16 :height 16 :x 4 :y 4 :rx 2}]
         [:rect {:width 6 :height 6 :x 9 :y 9 :rx 1}]
         [:path {:d "M15 2v2"}]
         [:path {:d "M15 20v2"}]
         [:path {:d "M2 15h2"}]
         [:path {:d "M2 9h2"}]
         [:path {:d "M20 15h2"}]
         [:path {:d "M20 9h2"}]
         [:path {:d "M9 2v2"}]
         [:path {:d "M9 20v2"}]]
   :hard-drive [[:path {:d "M22 12H2"}]
                [:path {:d "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"}]
                [:path {:d "M6 16h.01"}]
                [:path {:d "M10 16h.01"}]]
   :shield [[:path {:d "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"}]]
   :link [[:path {:d "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"}]
          [:path {:d "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"}]]
   :copy [[:rect {:width 14 :height 14 :x 8 :y 8 :rx 2}]
          [:path {:d "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"}]]})

(defn icon
  "渲染一个内联 SVG 图标。`name` 为 `paths` 中的关键字；`opts` 支持
   `:size`（像素，默认 16）、`:class`（附加 class）、`:stroke-width`（默认 2）。
   未知名称回退为一个圆形占位，避免运行期抛错。"
  ([name] (icon name nil))
  ([name {:keys [size class stroke-width]
          :or {size 16 stroke-width 2}}]
   (into [:svg {:xmlns "http://www.w3.org/2000/svg"
                :width size
                :height size
                :viewBox "0 0 24 24"
                :fill "none"
                :stroke "currentColor"
                :stroke-width stroke-width
                :stroke-linecap "round"
                :stroke-linejoin "round"
                :aria-hidden "true"
                :class (if class (str "pf-icon " class) "pf-icon")}]
         (get paths name [[:circle {:cx 12 :cy 12 :r 9}]]))))
