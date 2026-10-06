(ns culvert.web.views
  "视图渲染层：Hiccup 模板、片段注册表、OOB 响应构建、配额计算和控制台。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [culvert.web.core :as core]
            [culvert.auth :as auth]
            [culvert.caddy :as caddy]
            [culvert.cli :as cli]
            [culvert.config :refer [config]]
            [culvert.docker :as docker]
            [culvert.forward :as forward]
            [culvert.i18n :as i18n]
            [culvert.observability.activity :as activity]
            [culvert.observability.host :as host]
            [culvert.probe :as probe]
            [culvert.s3 :as s3]
            [culvert.smolvm.cli :as smolvm-cli]
            [culvert.smolvm.manager :as smolvm]
            [culvert.web.ui.drawer :as ui.drawer]
            [culvert.web.ui.icon :as ui.icon]
            [culvert.web.ui.popover :as ui.popover]
            [culvert.web.ui.skeleton :as ui.skeleton]
            [culvert.web.ui.tabs :as ui.tabs]))

;; ============================================================
;; 配额计算与渲染
;; ============================================================

(defn compute-usage
  "计算用户当前的资源使用量。"
  [username]
  (reduce (fn [acc c]
            (if (= (:userId c) username)
              (-> acc
                  (update :containers inc)
                  (update :cpuUsage + (Double/parseDouble (or (:cpuLimit c) "0")))
                  (update :memUsage docker/add-memory (or (:memLimit c) "0")))
              acc))
          {:portForwards (forward/count-by-user username)
           :reverseProxies (caddy/count-by-user username)
           :containers 0
           :smolvmMachines (smolvm/count-by-user username)
           :cpuUsage 0.0
           :memUsage "0"}
          (vals (:containers @docker/state))))

(defn render-quota-bar-content
  "渲染配额栏内部内容（不含 OOB 包装），供控制台和 OOB 响应复用。"
  [username user]
  (let [usage (compute-usage username)
        q (:quota i18n/strings)]
    (list
     [:span.pf-badge.pf-badge-outline {:title "端口转发配额"}
      (:port-forward q) [:strong (str (:portForwards usage) "/" (get-in user [:quotas :portForwards]))]]
     (when-not (:ip-mode config)
       [:span.pf-badge.pf-badge-outline {:title "反向代理配额"}
        (:reverse-proxy q) [:strong (str (:reverseProxies usage) "/" (get-in user [:quotas :reverseProxies]))]])
     [:span.pf-badge.pf-badge-outline {:title "容器配额"}
      (:container q) [:strong (str (:containers usage) "/" (get-in user [:quotas :containers]))]]
     (when (or (:smolvm-available? config) (pos? (:smolvmMachines usage)))
       [:span.pf-badge.pf-badge-outline {:title "MicroVM 配额"}
        (:smolvm q) [:strong (str (:smolvmMachines usage) "/" (get-in user [:quotas :smolvmMachines]))]])
     (when (get-in user [:quotas :cpuLimit])
       [:span.pf-badge.pf-badge-outline {:title (:total-cpu q)}
        (:cpu q) [:strong (str (:cpuUsage usage) "/" (get-in user [:quotas :cpuLimit]) "C")]])
     (when (get-in user [:quotas :memLimit])
       [:span.pf-badge.pf-badge-outline {:title (:total-mem q)}
        (:memory q) [:strong (str (:memUsage usage) "/" (get-in user [:quotas :memLimit]))]]))))

;; ============================================================
;; 统一资源视图模型（跨 forward/caddy/docker/smolvm 的只读汇总）
;; 视图直接读领域 atom 是存量交织；这里只做「读 + 归一化」，不执行命令。
;; ============================================================

(def ^:private status-colors
  {:alive "#55C98A" :dead "#D95D5D" :unknown "#D9A441" :disabled "#64748B"})

(defn- forward-resource-key [e] (str "fwd-" (:port e) "-" (:protocol e) "-" (:method e)))
(defn- caddy-resource-key [e] (str "caddy-" (:fullDomain e)))
(defn- container-resource-key [c] (str "docker-" (:id c)))
(defn- machine-resource-key [m] (str "smolvm-" (:id m)))

(defn- forward-status [e]
  (cond
    (false? (:enabled e)) :disabled
    (true? (:alive e)) :alive
    (false? (:alive e)) :dead
    :else :unknown))

(defn- probe-status [host port]
  (when (and host port)
    (when-let [p (probe/cached host port)]
      (if (:ok? p) :alive :dead))))

(defn- resolve-status
  "领域状态无法判定（unknown）时，用探针可达性补充；探针也无数据则保持 unknown。"
  [base host port]
  (if (= base :unknown)
    (or (probe-status host port) base)
    base))

(defn- enabled-status [enabled?]
  (if (false? enabled?) :disabled :alive))

(defn- container-status [s]
  (cond
    (= s "running") :alive
    (= s "stopped") :dead
    :else :unknown))

(defn- format-duration [created-at]
  (try
    (let [created (java.time.Instant/parse (str created-at))
          secs (.toSeconds (java.time.Duration/between created (java.time.Instant/now)))]
      (cond
        (< secs 60) (str (max 0 secs) "s")
        (< secs 3600) (str (quot secs 60) "m")
        (< secs 86400) (str (quot secs 3600) "h")
        :else (str (quot secs 86400) "d")))
    (catch Exception _ nil)))

(defn- forward-resources [username is-admin?]
  (for [e (forward/get-entries username is-admin?)]
    (let [host (:ip e)
          port (when (:toPort e) (str (:toPort e)))]
      {:key (forward-resource-key e)
       :type :forward
       :type-label "端口转发"
       :name (if (str/blank? (:remark e))
               (str ":" (:port e) " " (str/upper-case (:protocol e)))
               (:remark e))
       :ingress (str (str/upper-case (:protocol e)) " :" (:port e))
       :engine (:method e)
       :target (str (:ip e) ":" (:toPortDisplay e))
       :protocol (str/upper-case (:protocol e))
       :status (resolve-status (forward-status e) host (when port (Integer/parseInt port)))
       :owner (:userId e)
       :tab "forwards"
       :host host
       :port port
       :created-at nil})))

(defn- caddy-resources [username is-admin?]
  (for [e (caddy/get-entries username is-admin?)]
    (let [[host port] (probe/parse-target (:target e))]
      {:key (caddy-resource-key e)
       :type :caddy
       :type-label "反向代理"
       :name (if (str/blank? (:remark e)) (:fullDomain e) (:remark e))
       :ingress (:fullDomain e)
       :engine "caddy"
       :target (:target e)
       :protocol "HTTPS"
       :status (enabled-status (:enabled e))
       :owner (:userId e)
       :tab "forwards"
       :subtab "forward-caddy"
       :host host
       :port port
       :created-at nil})))

(defn- container-resources [username is-admin?]
  (for [c (docker/get-containers username is-admin?)
        :let [port (first (filter #(get-in c [:ports % :enabled]) (keys (:ports c))))]]
    {:key (container-resource-key c)
     :type :container
     :type-label "容器"
     :name (:name c)
     :ingress (if port (str ":" port) "—")
     :engine (:container-cli config)
     :target (:name c)
     :protocol "TCP"
     :status (container-status (:status c))
     :owner (:userId c)
     :tab "docker"
     :host (when port "127.0.0.1")
     :port port
     :created-at (:createdAt c)}))

(defn- machine-resources [username is-admin?]
  (for [m (smolvm/get-machines username is-admin?)
        :let [port (first (filter #(get-in m [:ports % :enabled]) (keys (:ports m))))]]
    {:key (machine-resource-key m)
     :type :vm
     :type-label "微虚机"
     :name (:name m)
     :ingress (if port (str ":" port) "—")
     :engine "smolvm"
     :target (:name m)
     :protocol "TCP"
     :status (container-status (:status m))
     :owner (:userId m)
     :tab "smolvm"
     :host (when port "127.0.0.1")
     :port port
     :created-at (:createdAt m)}))

(defn all-resources
  "汇总当前用户可见的全部资源（forward/caddy/docker/smolvm），供总览可视化使用。"
  [username is-admin?]
  (concat (forward-resources username is-admin?)
          (when-not (:ip-mode config) (caddy-resources username is-admin?))
          (container-resources username is-admin?)
          (machine-resources username is-admin?)))

(defn- status-label [status]
  (let [sts (:status i18n/strings)]
    (case status
      :alive (:running-text sts)
      :dead (:dead sts)
      :disabled (:disabled sts)
      (:unknown sts))))

(defn- status-badge-class [status]
  (case status
    :alive "pf-badge-success"
    :dead "pf-badge-error"
    :disabled "pf-badge-ghost"
    "pf-badge-warning"))

(defn- status-badge [status]
  [:span.pf-badge {:class (status-badge-class status)}
   [:span.pf-legend-dot {:style (str "background:" (get status-colors status "#D9A441"))}]
   (status-label status)])

(defn- latency-text [r]
  (if (and (:host r) (:port r))
    (if-let [p (probe/cached (:host r) (:port r))]
      (if (:ok? p) (str (:latency-ms p) " ms") "不可达")
      "—")
    "—"))

(defn- latency-cell [r]
  (list
   [:div (if (and (:host r) (some? (:port r)))
           (if-let [p (probe/cached (:host r) (:port r))]
             (if (:ok? p)
               [:span (str (:latency-ms p) " ms")]
               [:span.pf-text-error "不可达"])
             [:span.pf-text-muted "—"])
           [:span.pf-text-muted "—"])]
   (when-let [d (some-> (:created-at r) format-duration)]
     [:div.pf-text-muted {:style "font-size:0.68rem"} (str "运行 " d)])))

;; ============================================================
;; 总览：横幅 + 配额环 + KPI
;; ============================================================

(defn- percentage [used limit]
  (if (and limit (pos? limit))
    (min 100 (long (Math/round (* 100.0 (/ (double used) (double limit))))))
    0))

(defn render-overview-stats-content
  "渲染总览横幅：欢迎语 + 资源配额环 + KPI 卡。只读，不含任何 mutation。"
  [username user]
  (let [usage (compute-usage username)
        is-admin? (= (:role user) "admin")
        resources (all-resources username is-admin?)
        forwards (filter #(= :forward (:type %)) resources)
        active-forwards (count (filter #(= :alive (:status %)) forwards))
        containers (count (filter #(= :container (:type %)) resources))
        machines (count (filter #(= :vm (:type %)) resources))
        abnormal (count (filter #(#{:dead :unknown} (:status %)) resources))
        quotas (or (:quotas user) (auth/get-default-quotas))
        port-used (:portForwards usage)
        port-limit (:portForwards quotas)
        pct (percentage port-used port-limit)]
    (h/html
     [:section.pf-hero
      [:div.pf-hero-lead
       [:div.pf-hero-icon (ui.icon/icon :zap {:size 24})]
       [:div
        [:div.pf-hero-eyebrow "欢迎回来，" [:span (str username)]]
        [:h2.pf-hero-title "资源拓扑监控与运维控制台"]]]
      [:div.pf-kpi-row
       [:div.pf-quota-ring
        [:div.pf-quota-ring-visual
         [:svg {:viewBox "0 0 36 36"}
          [:path.pf-quota-ring-track {:stroke-width 3.5 :fill "none"
                                      :d "M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"}]
          [:path.pf-quota-ring-value {:stroke-width 3.5 :stroke-linecap "round" :fill "none"
                                      :stroke-dasharray (str pct ", 100")
                                      :d "M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"}]]
         [:span.pf-quota-ring-pct (str pct "%")]]
        [:div
         [:div.pf-kpi-label "资源配额"]
         [:div.pf-kpi-sub (str "端口已占 " port-used " / " (or port-limit "—"))]]]
       [:button.pf-kpi {:type "button" :data-action "tabs.activate" :data-tab "forwards"}
        [:span.pf-kpi-label "活跃转发"]
        [:span.pf-kpi-value.pf-kpi-value--accent (str active-forwards)]
        [:span.pf-kpi-sub (str "共 " (count forwards) " 条规则")]]
       [:button.pf-kpi {:type "button" :data-action "tabs.activate" :data-tab "docker"}
        [:span.pf-kpi-label "容器数量"]
        [:span.pf-kpi-value.pf-kpi-value--indigo (str containers)]
        [:span.pf-kpi-sub "资源统计"]]
       (when (or (:smolvm-available? config) (pos? machines))
         [:button.pf-kpi {:type "button" :data-action "tabs.activate" :data-tab "smolvm"}
          [:span.pf-kpi-label "微虚机"]
          [:span.pf-kpi-value.pf-kpi-value--indigo (str machines)]
          [:span.pf-kpi-sub "资源统计"]])
       [:button.pf-kpi.pf-kpi--danger {:type "button" :data-action "tabs.activate" :data-tab "forwards"}
        [:span.pf-kpi-label "异常告警"]
        [:span.pf-kpi-value.pf-kpi-value--danger (str abnormal)]
        [:span.pf-kpi-sub "需要关注"]]]])))

;; ============================================================
;; 总览：资源接线图
;; ============================================================

(defn- topo-connector [status]
  (let [stroke (get status-colors status "#D9A441")
        alive? (= status :alive)
        disabled? (= status :disabled)]
    [:div.pf-topo-connector
     [:svg {:viewBox "0 0 50 20" :preserveAspectRatio "none"}
      [:path (cond-> {:d "M 2 10 L 44 10" :stroke stroke :stroke-width 1.5 :fill "none"}
               alive? (assoc :class "pf-flow-dash")
               disabled? (assoc :stroke-dasharray "4 3" :opacity 0.4))]
      [:polygon {:points "44,5 50,10 44,15" :fill stroke
                 :opacity (if disabled? 0.4 0.9)}]]]))

(defn- topo-row [r]
  (let [status (:status r)
        stroke (get status-colors status "#D9A441")]
    [:div.pf-topo-row
     {:data-resource-key (:key r)
      :data-tab (:tab r)
      :data-focus-resource (:key r)
      :data-action "tabs.activate"
      :data-subtab-target (when (:subtab r) (:subtab r))
      :title "点击跳转至该资源列表并高亮定位"}
     [:div.pf-topo-ingress
      [:span.pf-topo-ingress-value (:ingress r)]
      [:span.pf-topo-ingress-name (:name r)]]
     (topo-connector status)
     [:div.pf-topo-engine
      [:div.pf-topo-engine-chip {:data-status (name status)}
       [:span.pf-topo-engine-main
        [:span.pf-topo-dot {:style (str "background:" stroke
                                        (when (= status :alive) (str ";box-shadow:0 0 8px " stroke)))}]
        [:span (:engine r)]]
       [:span.pf-topo-proto (:protocol r)]]]
     (topo-connector status)
     [:div.pf-topo-target
      [:div
       [:span.pf-topo-target-main (:target r)]
       [:span.pf-topo-target-lat (latency-text r)]]]]))

(defn- degraded-item [r]
  [:div.pf-degraded-item
   {:data-resource-key (:key r) :data-tab (:tab r) :data-focus-resource (:key r)
    :data-action "tabs.activate"
    :data-subtab-target (when (:subtab r) (:subtab r))}
   [:div.pf-degraded-top
    [:span (str (:ingress r)) " → " (:target r)]
    (status-badge (:status r))]
   [:div.pf-degraded-sub
    [:span (:engine r) " · " (:protocol r) " · " (:name r)]
    [:span "定位 →"]]])

(def ^:private topo-limit 5)

(defn render-topology-content
  "渲染资源接线图内部内容：桌面栅格接线 + 窄屏降级列表。"
  [username is-admin?]
  (let [resources (vec (all-resources username is-admin?))
        shown (vec (take topo-limit resources))
        total-forwards (count (filter #(= :forward (:type %)) resources))
        shown-forwards (count (filter #(= :forward (:type %)) shown))
        remaining (max 0 (- total-forwards shown-forwards))]
    (h/html
     [:div.pf-card-head
      [:h3.pf-card-head-title
       [:span.pf-live-dot]
       "资源接线图"
       [:span.pf-tag "Topology View"]]
      [:div.pf-legend
       [:span.pf-legend-item [:span.pf-legend-dot {:style "background:#55C98A"}] "运行中"]
       [:span.pf-legend-item [:span.pf-legend-dot {:style "background:#D95D5D"}] "异常"]
       [:span.pf-legend-item [:span.pf-legend-dot {:style "background:#D9A441"}] "未知"]
       [:span.pf-legend-item [:span.pf-legend-dot {:style "background:#64748B"}] "禁用"]]]
     (if (empty? shown)
       [:div.pf-topo-empty "暂无资源，先在转发或容器页面创建"]
       (list
        [:div.pf-topo-desktop
         [:div.pf-topo-head
          [:span.pf-topo-head-ingress "公网入口 (Ingress)"]
          [:span.pf-topo-head-center "连接"]
          [:span.pf-topo-head-engine "服务引擎 (Engine)"]
          [:span.pf-topo-head-center "连接"]
          [:span.pf-topo-head-target "内网目标 (Target)"]]
         [:div.pf-topo-rows (for [r shown] (topo-row r))]]
        [:div.pf-topo-narrow
         [:div.pf-degraded-rows (for [r shown] (degraded-item r))]]))
     [:div.pf-topo-foot
      [:span "每条资源独立一行，避免连线交叉。点击整条连接可定位并跳转。"]
      (when (pos? remaining)
        [:button.pf-topo-more {:type "button" :data-action "tabs.activate" :data-tab "forwards"}
         (str "还有 " remaining " 条转发，进入转发列表查看全部")
         (ui.icon/icon :arrow-right-left {:size 12})])])))

;; ============================================================
;; 总览：端口频谱
;; ============================================================

(defn- system-pools
  "获取系统配置的预留端口池定义（Shell / 端口转发 / SmolVM）。"
  []
  (let [pf (:port-forward-range config)]
    (cond-> []
      (and pf (:start pf) (:end pf))
      (conj {:key "pool-forward"
             :name "端口转发池"
             :short-name "转发池"
             :start (:start pf)
             :end (:end pf)
             :total (inc (- (:end pf) (:start pf)))
             :tab "forwards"})
      (and (:ttyd-port-start config) (:ttyd-port-end config))
      (conj {:key "pool-ttyd"
             :name "Shell 预留池"
             :short-name "Shell"
             :start (:ttyd-port-start config)
             :end (:ttyd-port-end config)
             :total (inc (- (:ttyd-port-end config) (:ttyd-port-start config)))
             :tab "forwards"})
      (and (:smolvm-port-start config) (:smolvm-port-end config))
      (conj {:key "pool-smolvm"
             :name "微虚机预留池"
             :short-name "微虚机"
             :start (:smolvm-port-start config)
             :end (:smolvm-port-end config)
             :total (inc (- (:smolvm-port-end config) (:smolvm-port-start config)))
             :tab "smolvm"}))))

(defn- pool-allocated-ports
  "统计指定系统端口池内已被真实占用的端口总数。"
  [pool segments]
  (reduce + 0
          (for [s segments
                :when (and (<= (:start pool) (:end s))
                           (>= (:end pool) (:start s)))]
            (or (:port-count s) 1))))

(defn port-segments
  "构造端口频谱段：
   1. 汇集 forward、docker、smolvm 的已分配端口与端口段；
   2. 检查系统预留池（Shell / 转发 / 微虚机），对未被占用的预留池生成预留段；
   3. 全量段严格按起始端口数值升序排序，保证频谱物理顺序正确。"
  [username is-admin?]
  (let [allocated
        (concat
         (for [e (forward/get-entries username is-admin?)
               :when (:enabled e)]
           (let [is-range? (boolean (:isRange e))
                 start (if is-range? (:fromPort e) (Integer/parseInt (str (:port e))))
                 end (if is-range? (:toRangePort e) start)
                 cnt (inc (- end start))
                 disp-port (if is-range?
                             (if (= (quot start 100) (quot end 100))
                               (str ":" start "–" (format "%02d" (rem end 100)))
                               (str ":" start "–" end))
                             (str ":" start))
                 lbl (if is-range?
                       (str ":" start "-" end " " (str/upper-case (:protocol e)) " (" cnt "端口)")
                       (str ":" start " " (str/upper-case (:protocol e))))]
             {:key (forward-resource-key e)
              :start start
              :end end
              :is-range? is-range?
              :port-count cnt
              :display-port disp-port
              :label lbl
              :service (str (:method e) " → " (:ip e) ":" (:toPortDisplay e))
              :owner (:userId e)
              :status (forward-status e)
              :protocol (str/upper-case (:protocol e))
              :resource-key (forward-resource-key e)
              :tab "forwards"}))
         (for [c (docker/get-containers username is-admin?)
               p (keys (:ports c))
               :when (get-in c [:ports p :enabled])]
           (let [p-int (Integer/parseInt (str p))]
             {:key (str (container-resource-key c) "-" p)
              :start p-int
              :end p-int
              :is-range? false
              :port-count 1
              :display-port (str ":" p)
              :label (str ":" p " TCP")
              :service (str "容器: " (:name c))
              :owner (:userId c)
              :status (container-status (:status c))
              :protocol "TCP"
              :resource-key (container-resource-key c)
              :tab "docker"}))
         (for [m (smolvm/get-machines username is-admin?)
               p (keys (:ports m))
               :when (get-in m [:ports p :enabled])]
           (let [p-int (Integer/parseInt (str p))]
             {:key (str (machine-resource-key m) "-" p)
              :start p-int
              :end p-int
              :is-range? false
              :port-count 1
              :display-port (str ":" p)
              :label (str ":" p " TCP")
              :service (str "微虚机: " (:name m))
              :owner (:userId m)
              :status (container-status (:status m))
              :protocol "TCP"
              :resource-key (machine-resource-key m)
              :tab "smolvm"})))
        pools (system-pools)
        ;; 对完全未被占用的预留池，在频谱上呈现独立预留色块
        idle-pool-segs
        (for [p pools
              :when (zero? (pool-allocated-ports p allocated))]
          {:key (:key p)
           :start (:start p)
           :end (:end p)
           :reserved? true
           :is-range? true
           :port-count (:total p)
           :display-port (str (:start p) "–" (:end p))
           :display-sub (str (:short-name p) "预留")
           :label (str (:name p) " " (:start p) "-" (:end p) " (" (:total p) "端口)")
           :service (str (:name p) " · 系统分配池")
           :owner "系统预留"
           :status :reserved
           :protocol "ALL"
           :tab (:tab p)})]
    (vec (sort-by (juxt :start :end) (concat allocated idle-pool-segs)))))

(defn- spectrum-segment [seg]
  (let [reserved? (:reserved? seg)
        range? (:is-range? seg)
        status (if reserved? :reserved (or (:status seg) :unknown))
        cls (cond-> "pf-seg"
              reserved? (str " pf-seg--reserved pf-striped-reserved")
              (and (not reserved?) range?) (str " pf-seg--range"))
        display-port (cond
                       reserved? (or (:display-port seg) "预留")
                       range? (or (:display-port seg) (str ":" (:start seg) "–" (:end seg)))
                       :else (or (:display-port seg) (str ":" (:start seg))))
        display-sub (cond
                      reserved? (or (:display-sub seg) "RANGE")
                      range? (str (or (:protocol seg) "TCP") " · " (:port-count seg 1) "P")
                      :else (or (:protocol seg) "TCP"))]
    [:button {:type "button"
              :class cls
              :data-status (if reserved? "reserved" (name status))
              :data-proto (or (:protocol seg) "ALL")
              :data-resource-key (:resource-key seg)
              :data-tab (:tab seg)
              :data-focus-resource (:resource-key seg)
              :data-seg-label (:label seg)
              :data-seg-service (:service seg)
              :data-seg-owner (or (:owner seg) "—")
              :data-seg-status (if reserved?
                                 "系统预留"
                                 (status-label (or (:status seg) :unknown)))
              :title (:label seg)}
     [:span.pf-seg-port display-port]
     [:span.pf-seg-proto display-sub]]))

(defn- band-items [band segments]
  (filterv (fn [s]
             (case band
               "low" (<= (:start s) 1024)
               "mid" (and (> (:start s) 1024) (<= (:start s) 8192))
               "high" (and (> (:start s) 8192) (<= (:start s) 65535))))
           segments))

(defn- band-empty-label [band]
  (case band
    "low" "0–1024 空闲"
    "mid" "1025–8k 空闲"
    "high" "8k–65k 空闲"))

(defn- band-style [items any-other-has-items?]
  (let [c (count items)]
    (if (zero? c)
      (if any-other-has-items?
        "flex: 0 0 110px; min-width: 95px;"
        "flex: 1 1 0; min-width: 100px;")
      (str "flex: " (max 2 (* c 1.5)) " 1 auto; min-width: " (max 180 (* c 54)) "px;"))))

(defn- spectrum-band [band items any-other-has-items?]
  [:div.pf-spectrum-band
   {:data-band band
    :class (if (empty? items) "pf-spectrum-band--empty" "pf-spectrum-band--active")
    :style (band-style items any-other-has-items?)}
   (if (empty? items)
     [:div.pf-spectrum-empty {:title (str (band-empty-label band) " (无端口占用)")}
      (band-empty-label band)]
     (for [s items] (spectrum-segment s)))])

(defn render-port-spectrum-content
  "渲染端口频谱内部内容：
   - 头部指标（已占用规则/端口数、系统预留池数）+ 协议筛选
   - 系统预留池状态条（Shell / 端口转发 / SmolVM 水位与空闲统计）
   - 三段视觉带标签（熟知端口 0-1024 / 常用服务 1025-8192 / 高端口与服务池 8193-65535）
   - 动态弹性比例轨道（按端口密度自适应，避免空闲区浪费空间且保护高密度区横向展开）
   - 刻度标尺、富交互详情栏与图例。"
  [username is-admin?]
  (let [segments (port-segments username is-admin?)
        pools (system-pools)
        low-items (band-items "low" segments)
        mid-items (band-items "mid" segments)
        high-items (band-items "high" segments)
        has-low (seq low-items)
        has-mid (seq mid-items)
        has-high (seq high-items)
        active-rules (filterv #(not (:reserved? %)) segments)
        total-rules (count active-rules)
        total-ports (reduce + 0 (map #(or (:port-count %) 1) active-rules))]
    (h/html
     [:div.pf-card-head
      [:h3.pf-card-head-title
       (ui.icon/icon :layers {:size 16})
       "端口频谱"
       [:span.pf-tag "Port Spectrum"]
       [:span.pf-badge.pf-badge-info (str total-rules " 规则已占用 · " total-ports " 端口")]
       [:span.pf-text-muted {:style "font-size:0.7rem"} "全域 0 – 65535"]]
      [:div.pf-proto-filter {:role "group" :aria-label "协议筛选"}
       [:button {:type "button" :data-action "spectrum.filter" :data-proto "ALL" :aria-pressed "true"} "全部协议"]
       [:button {:type "button" :data-action "spectrum.filter" :data-proto "TCP" :aria-pressed "false"} "TCP"]
       [:button {:type "button" :data-action "spectrum.filter" :data-proto "UDP" :aria-pressed "false"} "UDP"]]]
     (when (seq pools)
       [:div.pf-spectrum-pools
        [:span.pf-spectrum-pools-title
         (ui.icon/icon :layers {:size 13})
         "系统预留池:"]
        (for [p pools
              :let [alloc (pool-allocated-ports p segments)
                    active? (pos? alloc)]]
          [:span.pf-spectrum-pool-item {:key (:key p)}
           [:span.pf-pool-tag {:class (when active? "pf-pool-tag--active")} (:name p)]
           [:span.pf-pool-range (str (:start p) "–" (:end p))]
           [:span.pf-pool-stat
            (if active?
              (str "(" alloc "/" (:total p) " 端口已占用)")
              (str "(" (:total p) " 端口 · 空闲)"))]])])
     [:div.pf-spectrum-bandlabel
      [:div.pf-spectrum-bandlabel-item
       [:span.pf-legend-dot {:style "background:#22d3ee"}]
       [:span "熟知端口 (0 - 1024)"]
       [:span.pf-text-dim (if has-low (str "· " (count low-items) " 项") "· 空闲")]]
      [:div.pf-spectrum-bandlabel-item
       [:span.pf-legend-dot {:style "background:#818cf8"}]
       [:span "常用服务 (1025 - 8192)"]
       [:span.pf-text-dim (if has-mid (str "· " (count mid-items) " 项") "· 空闲")]]
      [:div.pf-spectrum-bandlabel-item
       [:span.pf-legend-dot {:style "background:#38bdf8"}]
       [:span "高端口与服务池 (8193 - 65535)"]
       [:span.pf-text-accent (if has-high (str "· " (count high-items) " 项") "· 空闲")]]]
     [:div.pf-spectrum-track {:id "port-spectrum-track"}
      (spectrum-band "low" low-items (or has-mid has-high))
      (spectrum-band "mid" mid-items (or has-low has-high))
      (spectrum-band "high" high-items (or has-low has-mid))]
     [:div.pf-spectrum-ticks
      [:span "0"] [:span "1024"] [:span "8k"] [:span "16k"] [:span "32k"] [:span "65535"]]
     [:div.pf-spectrum-detail {:id "port-spectrum-detail"}
      [:span.pf-spectrum-detail-hint
       (ui.icon/icon :info {:size 14})
       "悬停上方色块查看端口分配、关联资源与负责人，点击色块可跳转定位。"]]
     [:div.pf-card-head {:style "border-bottom:none;margin-bottom:0;padding-bottom:0;margin-top:0.5rem"}
      [:div.pf-legend
       [:span.pf-legend-item [:span.pf-legend-dot {:style "background:#55C98A;border-radius:0.2rem"}] "单端口正常"]
       [:span.pf-legend-item [:span.pf-legend-dot {:style "background:linear-gradient(135deg, #4ade80, #06b6d4);border-radius:0.2rem"}] "端口段 (多端口)"]
       [:span.pf-legend-item [:span.pf-legend-dot {:style "background:#D95D5D;border-radius:0.2rem"}] "异常冲突"]
       [:span.pf-legend-item [:span.pf-legend-dot {:style "background:#D9A441;border-radius:0.2rem"}] "高延迟/告警"]
       [:span.pf-legend-item [:span.pf-legend-dot.pf-striped-reserved {:style "background:#242f40;border-radius:0.2rem"}]
        "系统预留池（斜纹）"]]
      [:span.pf-spectrum-note "* 区间按端口密度动态自适应分配宽度；点击色块可直达关联资源并高亮定位。"]])))

;; --- 模块化渲染函数 ---

(defn- mutation-vals [csrf-token params]
  (json/generate-string (assoc params :_csrf csrf-token)))

(defn- quota-cell [used input-attrs]
  (list
   [:span.pf-text-success.pf-mono used]
   " / "
   [:input.pf-input.pf-input-inline (merge {:size 4} input-attrs)]))

;; ============================================================
;; 管理页：单机主机指标（真实数据，缺失项显示 —）
;; ============================================================

(defn- format-bytes [b]
  (when (number? b)
    (let [units ["B" "KB" "MB" "GB" "TB"]]
      (loop [value (double b)
             idx 0]
        (if (or (< value 1024) (= idx (dec (count units))))
          (format "%.1f %s" value (nth units idx))
          (recur (/ value 1024) (inc idx)))))))

(defn- format-seconds [secs]
  (when (number? secs)
    (cond
      (< secs 60) (str (max 0 secs) "s")
      (< secs 3600) (str (quot secs 60) "m")
      (< secs 86400) (str (quot secs 3600) "h")
      :else (str (quot secs 86400) "d"))))

(defn- forward-port-count [username is-admin?]
  (reduce + 0
          (for [e (forward/get-entries username is-admin?)]
            (if (:isRange e) (inc (- (:toRangePort e) (:fromPort e))) 1))))

(defn- published-port-count [username is-admin?]
  (+ (reduce + 0 (for [c (docker/get-containers username is-admin?)
                       [_ pi] (:ports c)
                       :when (:enabled pi)] 1))
     (reduce + 0 (for [m (smolvm/get-machines username is-admin?)
                       [_ pi] (:ports m)
                       :when (:enabled pi)] 1))))

(defn port-pool-usage
  "由 config 端口池区间与四域真实占用计算端口池使用量。纯读，不发命令。"
  [username is-admin?]
  (let [ranges (cond-> []
                 (:port-forward-range config) (conj (:port-forward-range config))
                 (and (:smolvm-port-start config) (:smolvm-port-end config))
                 (conj {:start (:smolvm-port-start config) :end (:smolvm-port-end config)})
                 (and (:ttyd-port-start config) (:ttyd-port-end config))
                 (conj {:start (:ttyd-port-start config) :end (:ttyd-port-end config)}))
        total (reduce + 0 (map #(inc (- (:end %) (:start %))) ranges))
        used (+ (forward-port-count username is-admin?) (published-port-count username is-admin?))]
    {:total total
     :used used
     :used-pct (when (pos? total)
                 (long (Math/round (* 100.0 (/ (double used) (double total))))))}))

(defn- meter-level [pct]
  (cond
    (nil? pct) nil
    (>= pct 80) "error"
    (>= pct 50) "warning"
    :else "normal"))

(defn- host-meter [label value pct note]
  [:div.pf-host-meter
   [:div.pf-host-meter-head
    [:span.pf-host-meter-label label]
    [:span.pf-host-meter-value (or value "—")]]
   [:div.pf-meter-track
    [:div.pf-meter-fill {:data-level (meter-level pct) :style (str "width:" (or pct 0) "%")}]]
   (when (seq note)
     [:div.pf-host-meter-note note])])

(defn render-host-metrics-content
  "渲染单机主机指标卡内部内容：CPU 负载 / 内存 / 磁盘 / 端口池，各一条进度条。
   非 Linux 平台或缺数据时显示 — 并给出说明，不伪造数字。"
  [username is-admin?]
  (let [h (host/snapshot)
        pool (port-pool-usage username is-admin?)
        running-containers (count (filter #(= (:status %) "running") (docker/get-containers username is-admin?)))
        running-machines (count (filter #(= (:status %) "running") (smolvm/get-machines username is-admin?)))
        cpu (:cpu h)
        memory (:memory h)
        disk (:disk h)
        hostname (or (System/getenv "HOSTNAME") "本机")
        cpu-value (when (and (:load-average cpu) (:cores cpu))
                    (format "%.2f / %d 核" (double (:load-average cpu)) (:cores cpu)))
        mem-value (when memory
                    (str (format-bytes (* 1024 (:used-kb memory)))
                         " / " (format-bytes (* 1024 (:total-kb memory)))))
        disk-value (when disk
                     (str (format-bytes (:used-bytes disk))
                          " / " (format-bytes (:total-bytes disk))))
        pool-value (str (:used pool) " / " (:total pool) " 个端口")]
    (h/html
     [:div.pf-card-head
      [:h3.pf-card-head-title
       (ui.icon/icon :server {:size 16})
       (str "本机节点 · " hostname)]
      [:span.pf-tag (if h (:platform h) "指标不可用")]]
     [:div.pf-host-grid
      (host-meter "CPU 负载（1 分钟均值）" cpu-value (:used-pct cpu)
                  (when-not (:load-average cpu) "当前平台无法读取系统负载"))
      (host-meter "内存" mem-value (:used-pct memory)
                  (when-not memory "非 Linux 平台暂不支持系统内存读取"))
      (host-meter "磁盘（数据目录）" disk-value (:used-pct disk)
                  (when-not disk "无法读取数据目录磁盘信息"))
      (host-meter "端口池" pool-value (:used-pct pool)
                  "端口转发 / 容器 / 微虚机已占用端口数")]
     [:div.pf-host-foot
      [:span (str "运行容器 " running-containers " · 微虚机 " running-machines)]
      [:span (str "控制面运行时长 " (or (some-> (:uptime-seconds h) format-seconds) "—"))]])))

(defn render-users-tbody [csrf-token]
  (let [all-users (auth/get-all-users)]
    (h/html
     (if (empty? all-users)
       [:tr [:td {:colspan 10 :class "pf-empty"} (:no-users (:empty i18n/strings))]]
       (for [u all-users]
         (let [u-usage (or (:quotas u) (auth/get-default-quotas))]
           [:tr {:data-username (:username u)}
            [:td [:code (:username u)]
             (when (= (:role u) "admin") [:span.pf-badge.pf-badge-error {:style "margin-left:0.25rem"} (:admin-badge (:dashboard i18n/strings))])]
            [:td (:role u)]
            [:td (quota-cell (get-in u [:quotaUsage :portForwards] 0)
                             {:type "number" :name "portForwards" :value (:portForwards u-usage)
                              :data-username (:username u) :data-field "portForwards" :min "0"})]
            [:td (quota-cell (get-in u [:quotaUsage :reverseProxies] 0)
                             {:type "number" :name "reverseProxies" :value (:reverseProxies u-usage)
                              :data-username (:username u) :data-field "reverseProxies" :min "0"})]
            [:td (quota-cell (get-in u [:quotaUsage :containers] 0)
                             {:type "number" :name "containers" :value (:containers u-usage)
                              :data-username (:username u) :data-field "containers" :min "0"})]
            [:td (quota-cell (get-in u [:quotaUsage :smolvmMachines] 0)
                             {:type "number" :name "smolvmMachines" :value (:smolvmMachines u-usage)
                              :data-username (:username u) :data-field "smolvmMachines" :min "0"})]
            [:td (quota-cell (str (get-in u [:quotaUsage :cpuUsage] 0.0) "C")
                             {:type "number" :step "0.5" :name "cpuLimit" :value (:cpuLimit u-usage)
                              :data-username (:username u) :data-field "cpuLimit" :min "0"})]
            [:td (quota-cell (get-in u [:quotaUsage :memUsage] "0")
                             {:type "text" :name "memLimit" :value (:memLimit u-usage)
                              :data-username (:username u) :data-field "memLimit"})]
            [:td [:small (if (:createdAt u) (.substring (:createdAt u) 0 10) (:created-at-hyphen (:dashboard i18n/strings)))]]
            [:td.pf-row-actions
             [:button.pf-btn.pf-btn-outline.pf-btn-info.pf-btn-xs
              {:hx-get (str "/admin/password-form?username=" (:username u))
               :hx-target "#password-modal-container"
               :hx-swap "innerHTML"}
              (:change-password (:button i18n/strings))]
             (when-not (= (:username u) "admin")
               [:button.pf-btn.pf-btn-outline.pf-btn-danger.pf-btn-xs
                {:hx-post "/admin/users/delete"
                 :hx-target "#admin-users-tbody"
                 :hx-swap "innerHTML"
                 :hx-confirm ((:delete-user (:confirm i18n/strings)) (:username u))
                 :hx-vals (mutation-vals csrf-token {:username (:username u)})} (:delete (:button i18n/strings))])
             [:button.pf-btn.pf-btn-outline.pf-btn-success.pf-btn-xs
              {:hx-post "/admin/users/quota"
               :hx-target "#admin-users-tbody"
               :hx-swap "innerHTML"
               :hx-include "closest tr"
               :hx-vals (mutation-vals csrf-token {:username (:username u)})} (:save-quota (:button i18n/strings))]]]))))))

(defn render-admin-containers-tbody [csrf-token]
  (let [host-containers (docker/get-all-host-containers)
        all-users (auth/get-all-users)
        dash (:dashboard i18n/strings)
        btn (:button i18n/strings)
        tip (:tooltip i18n/strings)
        tbl (:table i18n/strings)
        sorted (sort-by (juxt (complement :assignedUser) :name) host-containers)
        running-count (count (filter #(= (:state %) "running") host-containers))
        exited-count (count (filter #(= (:state %) "exited") host-containers))
        other-count (- (count host-containers) running-count exited-count)]
    (h/html
     [:tr
      [:td {:colspan 6 :class "pf-cell-plain"}
       [:div.pf-row-actions {:data-count-running running-count
                             :data-count-exited exited-count
                             :data-count-other other-count}
        [:button.filter-btn.pf-btn.pf-btn-sm {:data-filter "running"} (str (:filter-running tbl) " (" running-count ")")]
        [:button.filter-btn.pf-btn.pf-btn-sm {:data-filter "exited"} (str (:filter-stopped tbl) " (" exited-count ")")]
        [:button.filter-btn.pf-btn.pf-btn-sm {:data-filter "all"} (str (:filter-all tbl) " (" (count host-containers) ")")]]]]
     (if (empty? host-containers)
       [:tr
        [:td {:colspan 6}
         [:div.pf-empty
          (ui.icon/icon :box {:size 32})
          [:span (:no-host-containers (:empty i18n/strings))]]]]
       (for [hc sorted]
         [:tr {:style (when-not (:assignedUser hc) "border-left:3px solid var(--pf-color-warning)")
               :data-state (or (:state hc) "other")}
          [:td [:code (:name hc)]]
          [:td [:code.pf-text-muted
                (when-let [cid (:id hc)]
                  (subs cid 0 (min 12 (count cid))))]]
          [:td {:style "max-width:200px;overflow:hidden;text-overflow:ellipsis" :title (:image hc)}
           [:small (:image hc)]]
          [:td
           (cond
             (= (:state hc) "running")
             [:span.pf-badge.pf-badge-success (:running (:status i18n/strings))]
             (= (:state hc) "exited")
             [:span.pf-badge.pf-badge-error (:stopped (:status i18n/strings))]
             :else [:span.pf-text-muted (:state hc)])]
          [:td
           (if (:assignedUser hc)
             [:span.pf-badge.pf-badge-info (ui.icon/icon :user {:size 12}) (:assignedUser hc)]
             [:span.pf-badge.pf-badge-warning
              (ui.icon/icon :alert-triangle {:size 12}) (:container-unassigned dash)])]
          [:td
           [:div {:style "display:flex;flex-direction:column;gap:0.25rem"}
            [:div.pf-row-actions
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs
              {:title (:view-logs tip)
               :hx-get (str "/docker/logs?target=" (java.net.URLEncoder/encode (str (:id hc)) "UTF-8"))
               :hx-target "#log-panel-container"
               :hx-swap "innerHTML"}
              (ui.icon/icon :file-text {:size 14})]
             (let [ws (:webshell hc)
                   running? (= (:status ws) "running")
                   terminal-vals (mutation-vals csrf-token {:target (:name hc)})]
               (list
                (cond
                  ;; 终端已开启且存活：直接打开，不需要再起一次进程。
                  running?
                  [:a.pf-btn.pf-btn-ghost.pf-btn-xs.pf-text-success
                   {:href (:url ws) :target "_blank" :rel "noopener noreferrer"
                    :title (str (:open-terminal-window btn) "（" (:createdBy ws) "）")}
                   (ui.icon/icon :terminal {:size 14})]

                  ;; 容器未运行：ttyd 无法连接，按钮置灰。
                  (not= (:state hc) "running")
                  [:button.pf-btn.pf-btn-ghost.pf-btn-xs
                   {:type "button" :disabled true :title (:terminal-not-running (:validation i18n/strings))}
                   (ui.icon/icon :terminal {:size 14})]

                  :else
                  [:button.pf-btn.pf-btn-ghost.pf-btn-xs
                   {:type "button" :title (:open-terminal tip)
                    :hx-post "/admin/containers/terminal/open"
                    :hx-target "#terminal-modal-container"
                    :hx-swap "innerHTML"
                    :hx-vals terminal-vals}
                   (ui.icon/icon :terminal {:size 14})])
                (when (:status ws)
                  (list
                   [:button.pf-btn.pf-btn-ghost.pf-btn-xs
                    {:type "button" :title (:terminal-credentials tip)
                     :hx-post "/admin/containers/terminal/credentials"
                     :hx-target "#terminal-modal-container"
                     :hx-swap "innerHTML"
                     :hx-vals terminal-vals}
                    (ui.icon/icon :key {:size 14})]
                   [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-text-error
                    {:type "button" :title (:stop-terminal tip)
                     :hx-post "/admin/containers/terminal/close"
                     :hx-target "#admin-containers-tbody"
                     :hx-swap "innerHTML"
                     :hx-vals terminal-vals}
                    (ui.icon/icon :x {:size 14})]))))]
            (if (:assignedUser hc)
              (list
               [:select.pf-select
                {:style "width:100%" :name "user-id" :data-container-name (:name hc)}
                [:option {:value "" :disabled true :selected true} "-- 选择用户 --"]
                (for [u all-users
                      :when (not= (:username u) (:assignedUser hc))]
                  [:option {:value (:username u)} (:username u)])]
               [:button.pf-btn.pf-btn-outline.pf-btn-info.pf-btn-xs.pf-btn-block
                {:title (:confirm-assign tip)
                 :hx-post "/admin/containers/assign"
                 :hx-target "#admin-containers-tbody"
                 :hx-swap "innerHTML"
                 :hx-include "closest tr"
                 :hx-vals (mutation-vals csrf-token {:name (:name hc)})}
                (:confirm-assign btn)])
              (list
               [:select.pf-select
                {:style "width:100%" :name "user-id" :required true :data-container-name (:name hc)}
                [:option {:value "" :disabled true :selected true} "-- 选择用户 --"]
                (for [u all-users]
                  [:option {:value (:username u)} (:username u)])]
               [:button.pf-btn.pf-btn-outline.pf-btn-success.pf-btn-xs.pf-btn-block
                {:title (:assign-container tip)
                 :hx-post "/admin/containers/assign"
                 :hx-target "#admin-containers-tbody"
                 :hx-swap "innerHTML"
                 :hx-include "closest tr"
                 :hx-vals (mutation-vals csrf-token {:name (:name hc)})}
                (:confirm-assign btn)]))
            (when (and (:assignedUser hc) (= (:state hc) "exited"))
              [:button.pf-btn.pf-btn-outline.pf-btn-success.pf-btn-xs.pf-btn-block
               {:title (:start-container tip)
                :hx-post "/docker/start"
                :hx-target "#docker-tbody"
                :hx-swap "innerHTML"
                :hx-vals (mutation-vals csrf-token {:id (:appContainerId hc)})}
               (:start-container btn)])]]])))))

(defn render-forwards-tbody [username is-admin? csrf-token]
  (let [entries (forward/get-entries username is-admin?)]
    (h/html
     (if (empty? entries)
       [:tr [:td {:colspan 9 :class "pf-empty"} (:no-forwards (:empty i18n/strings))]]
       (for [e entries]
         (let [resource {:key (forward-resource-key e)
                         :host (:ip e)
                         :port (str (:toPort e))
                         :created-at nil
                         :status (resolve-status (forward-status e) (:ip e) (:toPort e))}]
           [:tr {:data-resource-key (:key resource)
                 :data-status (name (:status resource))
                 :data-search (str/lower-case
                               (str (:port e) " " (:protocol e) " " (:ip e) " " (:toPortDisplay e)
                                    " " (:method e) " " (:remark e) " " (:userId e)))}
            [:td.remark-cell
             [:div {:style "font-weight:600"}
              (if (str/blank? (:remark e))
                (str ":" (:port e) " " (str/upper-case (:protocol e)))
                (:remark e))]
             [:div.pf-text-muted {:style "font-size:0.68rem"} (:userId e)]]
            [:td
             [:code.pf-text-info (:port e)]
             (when (:isRange e)
               [:span.pf-text-muted {:style "font-size:0.72rem;display:block"}
                ((:port-range-count (:status i18n/strings)) (- (:toRangePort e) (:fromPort e) -1))])]
            [:td [:span.pf-badge.pf-badge-outline (:method e)]]
            [:td [:code (str (:ip e) ":" (:toPortDisplay e))]]
            [:td.pf-mono (str/upper-case (:protocol e))]
            [:td.latency-cell (latency-cell resource)]
            [:td.status-cell (status-badge (:status resource))]
            [:td.pf-mono (:userId e)]
            [:td.actions-col
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
              {:title (:edit-remark (:tooltip i18n/strings))
               :hx-get (str "/forward/remark-form?port=" (:port e)
                            "&protocol=" (:protocol e)
                            "&method=" (:method e)
                            "&remark=" (java.net.URLEncoder/encode (or (:remark e) "") "UTF-8"))
               :hx-target "closest .remark-cell"
               :hx-swap "innerHTML"}
              (ui.icon/icon :pencil {:size 14})]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
              {:title (if (false? (:enabled e)) (:enable (:tooltip i18n/strings)) (:disable (:tooltip i18n/strings)))
               :hx-post "/toggle"
               :hx-target "#forward-tbody"
               :hx-swap "innerHTML"
               :hx-vals (mutation-vals csrf-token {:port (:port e) :protocol (:protocol e) :method (:method e)})}
              (ui.icon/icon (if (false? (:enabled e)) :play :pause) {:size 14})]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square.pf-text-error
              {:title (:delete (:tooltip i18n/strings))
               :hx-post "/remove"
               :hx-target "#forward-tbody"
               :hx-swap "innerHTML"
               :hx-confirm (:delete-forward (:confirm i18n/strings))
               :hx-vals (mutation-vals csrf-token {:port (:port e) :protocol (:protocol e) :method (:method e)})}
              (ui.icon/icon :trash-2 {:size 14})]]]))))))

(defn render-caddy-tbody [username is-admin? csrf-token]
  (let [caddy-entries (caddy/get-entries username is-admin?)]
    (h/html
     (if (empty? caddy-entries)
       [:tr [:td {:colspan 9 :class "pf-empty"} (:no-caddy (:empty i18n/strings))]]
       (for [e caddy-entries]
         (let [[host port] (probe/parse-target (:target e))
               resource {:key (caddy-resource-key e)
                         :host host :port port :created-at nil
                         :status (enabled-status (:enabled e))}]
           [:tr {:data-resource-key (:key resource)
                 :data-status (name (:status resource))
                 :data-search (str/lower-case
                               (str (:fullDomain e) " " (:target e) " caddy https "
                                    (:remark e) " " (:userId e)))}
            [:td.remark-cell
             [:div {:style "font-weight:600"}
              (if (str/blank? (:remark e)) (:fullDomain e) (:remark e))]
             [:div.pf-text-muted {:style "font-size:0.68rem"} (:userId e)]]
            [:td [:code.pf-text-info (:fullDomain e)]]
            [:td [:span.pf-badge.pf-badge-outline "caddy"]]
            [:td [:code (:target e)]]
            [:td.pf-mono "HTTPS"]
            [:td.latency-cell (latency-cell resource)]
            [:td.status-cell (status-badge (:status resource))]
            [:td.pf-mono (:userId e)]
            [:td.actions-col
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
              {:title (:edit-remark (:tooltip i18n/strings))
               :hx-get (str "/caddy/remark-form?domain=" (:domain e)
                            "&worker=" (:worker e)
                            "&remark=" (java.net.URLEncoder/encode (or (:remark e) "") "UTF-8"))
               :hx-target "closest .remark-cell"
               :hx-swap "innerHTML"}
              (ui.icon/icon :pencil {:size 14})]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
              {:title (if (:enabled e) (:disable (:tooltip i18n/strings)) (:enable (:tooltip i18n/strings)))
               :hx-post "/caddy/toggle"
               :hx-target "#caddy-tbody"
               :hx-swap "innerHTML"
               :hx-vals (mutation-vals csrf-token {:domain (:domain e) :worker (:worker e)})}
              (ui.icon/icon (if (:enabled e) :pause :play) {:size 14})]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square.pf-text-error
              {:title (:delete (:tooltip i18n/strings))
               :hx-post "/caddy/remove"
               :hx-target "#caddy-tbody"
               :hx-swap "innerHTML"
               :hx-confirm (:delete-caddy (:confirm i18n/strings))
               :hx-vals (mutation-vals csrf-token {:domain (:domain e) :worker (:worker e)})}
              (ui.icon/icon :trash-2 {:size 14})]]]))))))

(defn- resource-status-badge
  "docker/smolvm 卡片头共享的三态彽章（running/stopped/其他），两处逻辑完全一致，只抽取一次避免重复。"
  [status sts]
  (cond
    (= status "running") [:span.pf-badge.pf-badge-success (:running sts)]
    (= status "stopped") [:span.pf-badge.pf-badge-error (:stopped sts)]
    :else [:span.pf-badge.pf-badge-error (:abnormal sts)]))

;; ============================================================
;; 容器日志面板（应用内同源，见 docs/10 ttyd 条目）
;; ============================================================

(def ^:private log-tail-options [200 1000 5000])

(defn- log-stream
  "日志正文的轮询容器：外层保留 hx-get/hx-trigger，内层 <pre> 随轮询被整体换掉，因此滚动状态由外层容器维持。"
  [{:keys [target tail text]}]
  (list
   [:div.pf-row {:style "justify-content:space-between"}
    [:div.pf-row
     [:label.pf-row {:style "font-size:0.78rem"}
      [:input.pf-checkbox {:type "checkbox" :id "log-follow" :checked true}]
      (:log-follow (:modal i18n/strings))]
     [:button.pf-btn.pf-btn-ghost.pf-btn-xs
      {:type "button"
       :title (:log-refresh (:tooltip i18n/strings))
       :hx-get (str "/docker/logs/content?target=" (java.net.URLEncoder/encode (str target) "UTF-8")
                    "&tail=" tail)
       :hx-target "#log-stream"
       :hx-swap "innerHTML"}
      (ui.icon/icon :refresh-cw {:size 13})]]
    [:select.pf-select
     {:style "width:auto" :name "tail"
      :hx-get "/docker/logs"
      :hx-target "#log-panel-container"
      :hx-swap "innerHTML"
      :hx-include "this"
      :hx-vals (json/generate-string {:target (str target)})}
     (for [n log-tail-options]
       [:option {:value (str n) :selected (= n tail)} (str n " 行")])]]
   [:div#log-stream.pf-log-view
    {:hx-get (str "/docker/logs/content?target=" (java.net.URLEncoder/encode (str target) "UTF-8")
                  "&tail=" tail)
     :hx-trigger "every 3s [document.getElementById('log-follow').checked]"
     :hx-target "this"
     :hx-swap "innerHTML"}
    [:pre {:style "margin:0"} (or text "")]]))

(defn render-log-panel
  "渲染容器日志面板片段（htmx 换入 #log-panel-container 的 <dialog>）。
   参数 {:target :name :state :source :text :error :tail}；:error 存在时只展示安全提示。"
  [{:keys [target name state error tail text]}]
  (let [tail (if (some #{tail} log-tail-options) tail 200)
        title (:log-title (:modal i18n/strings))]
    (h/html
     [:dialog.pf-modal {:id "logModal"}
      [:div.pf-modal-box {:style "width:min(1100px,92vw)"}
       [:div.pf-row {:style "justify-content:space-between;align-items:center"}
        [:h3.pf-modal-title {:style "margin:0"}
         title ": " [:code (or name target)]]
        [:div.pf-row
         (when state [:span.pf-badge.pf-badge-ghost state])
         [:button.pf-btn.pf-btn-ghost.pf-btn-xs
          {:type "button" :data-action "logs.close" :title (:close (:button i18n/strings))}
          (ui.icon/icon :x {:size 14})]]]
       [:div.pf-modal-body
        (if error
          [:div.pf-text-error error]
          (log-stream {:target target :tail tail :text text}))]]
      [:form.pf-modal-backdrop-close {:method "dialog"}
       [:button "close"]]])))

(defn render-log-content
  "仅渲染日志正文（轮询目标），外层 #log-stream 保留 hx-* 属性。"
  [{:keys [text error]}]
  (h/html [:pre {:style "margin:0"} (or error text "")]))

;; ============================================================
;; 管理员容器终端面板（凭据只在开启时按需展示）
;; ============================================================

(defn render-terminal-panel
  "渲染管理员终端凭据面板片段（htmx 换入 #terminal-modal-container 的 <dialog>）。
   参数 {:name :url :user :pass :error}；明文凭据只在此片段出现，不进状态 GET。"
  [{:keys [name url user pass error]}]
  (let [m (:modal i18n/strings)
        btn (:button i18n/strings)
        tip (:tooltip i18n/strings)]
    (h/html
     [:dialog.pf-modal {:id "terminalModal"}
      [:div.pf-modal-box {:style "width:min(680px,92vw)"}
       [:h3.pf-modal-title (str (:terminal-title m) ": " name)]
       [:div.pf-modal-body
        (if error
          [:div.pf-text-error error]
          (list
           [:div.pf-row
            [:span.pf-text-muted (str (:terminal-url m) ": ")]
            [:a.pf-link {:href url :target "_blank" :rel "noopener noreferrer"}
             (ui.icon/icon :external-link {:size 12}) " " [:code url]]]
           [:div.pf-row
            (ui.icon/icon :user {:size 14})
            [:code.pf-mono {:style "font-size:0.78rem"} user]
            [:button.pf-btn.pf-btn-ghost.pf-btn-xs
             {:type "button" :title (:copy-username tip) :data-copy user} (:copy btn)]]
           [:div.pf-row
            (ui.icon/icon :key {:size 14})
            [:code.pf-mono {:style "font-size:0.78rem"} pass]
            [:button.pf-btn.pf-btn-ghost.pf-btn-xs
             {:type "button" :title (:copy-password tip) :data-copy pass} (:copy btn)]]
           [:p.pf-text-muted {:style "font-size:0.75rem"} (:terminal-hint m)]))]
       [:div.pf-modal-actions
        [:button.pf-btn {:type "button" :data-action "terminal.close"} (:close btn)]]
       [:form.pf-modal-backdrop-close {:method "dialog"} [:button "close"]]]])))

(defn render-docker-tbody [username is-admin? csrf-token]
  (let [docker-entries (docker/get-containers username is-admin?)
        tip (:tooltip i18n/strings)
        btn (:button i18n/strings)
        fm (:form i18n/strings)
        sts (:status i18n/strings)
        cfm (:confirm i18n/strings)]
    (h/html
     (if (empty? docker-entries)
       [:div.pf-empty (:no-containers (:empty i18n/strings))]
       (for [[idx c] (map-indexed vector docker-entries)]
         [:div.card.pf-resource-card {:data-container-id (:id c)
                                      :data-resource-kind "docker"
                                      :data-resource-id (:id c)
                                      :data-resource-key (container-resource-key c)
                                      :data-tab "docker"
                                      :data-credential-endpoint "/docker/credentials"}
          ;; -- Card header: name, status, actions --
          [:div.pf-resource-card-header
           [:div.pf-row-wrap
            [:span.pf-badge.pf-badge-ghost (str "#" (inc idx))]
            [:code {:title (:id c) :style "font-weight:700"} (:name c)]
            (when is-admin? [:span.pf-badge.pf-badge-outline (:userId c)])
            (resource-status-badge (:status c) sts)]
           [:div.pf-row
            [:button.pf-btn.pf-btn-ghost.pf-btn-xs
             {:title (:view-logs tip)
              :hx-get (str "/docker/logs?target=" (java.net.URLEncoder/encode (str (:id c)) "UTF-8"))
              :hx-target "#log-panel-container"
              :hx-swap "innerHTML"}
             (ui.icon/icon :file-text {:size 14})]
            (when (= (:status c) "stopped")
              [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-text-success
               {:title (:start-container tip)
                :hx-post "/docker/start"
                :hx-target "#docker-tbody"
                :hx-swap "innerHTML"
                :hx-confirm (str "确定启动容器 " (:name c) " 吗？")
                :hx-vals (mutation-vals csrf-token {:id (:id c)})}
               (ui.icon/icon :play {:size 14})])
            [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-text-error
             {:title (:delete-container tip)
              :hx-post "/docker/kill"
              :hx-target "#docker-tbody"
              :hx-swap "innerHTML"
              :hx-confirm (:delete-container cfm)
              :hx-vals (mutation-vals csrf-token {:id (:id c)})}
             (:delete btn)]]]

          ;; -- Info row: image + resources --
          [:div.pf-resource-card-meta
           [:span [:span.pf-text-muted "镜像: "] [:code.pf-break-all (:image c)]]
           [:span [:span.pf-text-muted "资源: "] [:small (str (:cpuLimit c) "C / " (:memLimit c))
                                                (when (:gpu c) [:span.pf-text-success " " (ui.icon/icon :cpu {:size 12}) " GPU"])]]
           (when (:shellUrl c)
             [:span [:span.pf-text-muted "Shell: "] [:a.pf-link {:href (:shellUrl c) :target "_blank" :rel "noopener noreferrer"} (ui.icon/icon :external-link {:size 12}) " " [:code (:shellToken c)]]])
           (when (get-in c [:s3Mount :enabled])
             (let [sm (:s3Mount c)]
               [:span [:span.pf-text-muted "S3: "]
                (cond
                  (= (:status sm) "mounted") [:span.pf-text-success {:title (str (:endpoint sm "AWS S3") " / " (:bucket sm) " → " (:mountPoint sm))} (:mounted sts)]
                  (= (:status sm) "error") [:span.pf-text-error {:title (str (:endpoint sm "AWS S3") " / " (:bucket sm))} (:mount-error sts)]
                  :else [:span.pf-text-warning (:status sm)])]))]

          ;; -- Three-column grid: Usage | Credentials | Ports --
          [:div.pf-resource-card-grid
           ;; Usage column
           [:div.resource-usage-cell {:data-container-name (:name c)}
            (if (= (:status c) "running")
              [:div.pf-stack
               [:div.pf-row
                [:span {:style "font-size:0.7rem;font-weight:700;width:2rem"} "CPU"]
                [:progress.resource-bar-cpu {:value "0" :max "100"}]
                [:span.resource-bar-text {:style "font-size:0.7rem"} "—"]]
               [:div.pf-row
                [:span {:style "font-size:0.7rem;font-weight:700;width:2rem"} "MEM"]
                [:progress.resource-bar-mem {:value "0" :max "100"}]
                [:span.resource-bar-text {:style "font-size:0.7rem"} "—"]]]
              [:span.pf-text-muted (:unknown sts)])]

           ;; Credentials column
           [:div.credential-cell
            [:div.pf-row
             (ui.icon/icon :user {:size 14})
             [:code.pf-mono {:style "font-size:0.75rem"} (:shellUser c)]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs {:title (:copy-username tip) :data-copy (:shellUser c)} (:copy btn)]]
            [:div.pf-row
             (ui.icon/icon :key {:size 14})
             [:input.cred-pass-input.pf-input.pf-input-inline {:style "width:6rem" :type "password" :value (:shellPassMasked c) :readonly true :data-container-id (:id c)}]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs {:title (:show-password tip) :data-container-id (:id c)} (:show-password btn)]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs {:title (:copy-password tip) :data-copy-pass (:id c)} (:copy btn)]]]

           ;; Ports column
           [:div.ports-cell
            (let [ports-keys (keys (:ports c))]
              (list
               (if (empty? ports-keys)
                 [:span.pf-text-muted (:unknown sts)]
                 (for [p ports-keys]
                   (let [pi (get-in c [:ports p])]
                     [:div.pf-row
                      [:code (str ":" p)]
                      (when (seq (:url pi))
                        (list " → " [:a.pf-link {:href (:url pi) :target "_blank" :rel "noopener noreferrer" :style "font-size:0.75rem"} (:domain pi)]))
                      [:span.pf-row
                       [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
                        {:title (if (:enabled pi) (:disable tip) (:enable tip))
                         :hx-post "/docker/port/toggle"
                         :hx-target "#docker-tbody"
                         :hx-swap "innerHTML"
                         :hx-vals (mutation-vals csrf-token {:id (:id c) :port p})}
                        (ui.icon/icon (if (:enabled pi) :pause :play) {:size 13})]
                       [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square.pf-text-error
                        {:title (:unexpose tip)
                         :hx-post "/docker/port/remove"
                         :hx-target "#docker-tbody"
                         :hx-swap "innerHTML"
                         :hx-confirm (:delete-port cfm)
                         :hx-vals (mutation-vals csrf-token {:id (:id c) :port p})}
                        (:delete btn)]]])))
               [:div.pf-row {:style "margin-top:0.25rem"}
                [:input.pf-input.pf-input-inline {:style "width:4.5rem" :type "number" :name "port" :placeholder "端口" :min 1 :max 65535}]
                [:input.pf-input.pf-input-inline {:style "width:6rem" :type "text" :name "remark" :placeholder (:placeholder-remark fm)}]
                [:button.pf-btn.pf-btn-outline.pf-btn-success.pf-btn-xs
                 {:hx-post "/docker/port/add"
                  :hx-target "#docker-tbody"
                  :hx-swap "innerHTML"
                  :hx-include "closest .card"
                  :hx-vals (mutation-vals csrf-token {:id (:id c)})}
                 (:add-port btn)]]))]]])))))

;; ============================================================
;; SmolVM Table Rendering
;; ============================================================

(defn render-smolvm-tbody [username is-admin? csrf-token]
  (let [machines (smolvm/get-machines username is-admin?)
        tip (:tooltip i18n/strings)
        btn (:button i18n/strings)
        fm (:form i18n/strings)
        sts (:status i18n/strings)
        cfm (:confirm i18n/strings)]
    (h/html
     (if (empty? machines)
       [:div.pf-empty (:no-machines (:empty i18n/strings))]
       (for [[idx m] (map-indexed vector machines)]
         [:div.card.pf-resource-card {:data-container-id (:id m)
                                      :data-resource-kind "smolvm"
                                      :data-resource-id (:id m)
                                      :data-resource-key (machine-resource-key m)
                                      :data-tab "smolvm"
                                      :data-credential-endpoint "/smolvm/credentials"}
          ;; -- Card header --
          [:div.pf-resource-card-header
           [:div.pf-row-wrap
            [:span.pf-badge.pf-badge-ghost (str "#" (inc idx))]
            [:code {:title (:id m) :style "font-weight:700"} (:name m)]
            (when is-admin? [:span.pf-badge.pf-badge-outline (:userId m)])
            (resource-status-badge (:status m) sts)]
           [:div.pf-row
            (when (= (:status m) "stopped")
              [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-text-success
               {:title (:start-machine tip)
                :hx-post "/smolvm/start"
                :hx-target "#smolvm-tbody"
                :hx-swap "innerHTML"
                :hx-vals (mutation-vals csrf-token {:id (:id m)})}
               (ui.icon/icon :play {:size 14})])
            (when (= (:status m) "running")
              [:button.pf-btn.pf-btn-ghost.pf-btn-xs
               {:title (:stop-machine btn)
                :hx-post "/smolvm/stop"
                :hx-target "#smolvm-tbody"
                :hx-swap "innerHTML"
                :hx-confirm ((:stop-machine cfm) (:name m))
                :hx-vals (mutation-vals csrf-token {:id (:id m)})}
               (ui.icon/icon :square {:size 14})])
            [:button.pf-btn.pf-btn-ghost.pf-btn-xs
             {:title (:pack-machine btn)
              :hx-post "/smolvm/pack"
              :hx-target "#smolvm-tbody"
              :hx-swap "innerHTML"
              :hx-vals (mutation-vals csrf-token {:id (:id m)})}
             (ui.icon/icon :package {:size 14})]
            [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-text-error
             {:title (:delete-container tip)
              :hx-post "/smolvm/kill"
              :hx-target "#smolvm-tbody"
              :hx-swap "innerHTML"
              :hx-confirm (:delete-machine cfm)
              :hx-vals (mutation-vals csrf-token {:id (:id m)})}
             (:delete btn)]]]

          ;; -- Info row --
          [:div.pf-resource-card-meta
           [:span [:span.pf-text-muted "镜像: "] [:code.pf-break-all (:image m)]]
           [:span [:span.pf-text-muted "资源: "] [:small (str (:cpuLimit m) "C / " (:memLimit m) "MiB")
                                                (when (:gpu m) [:span.pf-text-success " " (ui.icon/icon :cpu {:size 12}) " GPU"])]]
           (when (:shellUrl m)
             [:span [:span.pf-text-muted "Shell: "] [:a.pf-link {:href (:shellUrl m) :target "_blank" :rel "noopener noreferrer"} (ui.icon/icon :external-link {:size 12}) " " [:code (:shellToken m)]]])]

          ;; -- Two-column grid: Credentials | Ports --
          [:div.pf-resource-card-grid
           ;; Credentials column
           [:div.credential-cell
            [:div.pf-row
             (ui.icon/icon :user {:size 14})
             [:code.pf-mono {:style "font-size:0.75rem"} (:shellUser m)]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs {:title (:copy-username tip) :data-copy (:shellUser m)} (:copy btn)]]
            [:div.pf-row
             (ui.icon/icon :key {:size 14})
             [:input.cred-pass-input.pf-input.pf-input-inline {:style "width:6rem" :type "password" :value (:shellPassMasked m) :readonly true :data-container-id (:id m)}]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs {:title (:show-password tip) :data-container-id (:id m)} (:show-password btn)]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs {:title (:copy-password tip) :data-copy-pass (:id m)} (:copy btn)]]]

           ;; Ports column
           [:div.ports-cell
            (let [ports-keys (keys (:ports m))]
              (list
               (if (empty? ports-keys)
                 [:span.pf-text-muted (:unknown sts)]
                 (for [p ports-keys]
                   (let [pi (get-in m [:ports p])]
                     [:div.pf-row
                      [:code (str ":" p)]
                      (when (seq (:url pi))
                        (list " → " [:a.pf-link {:href (:url pi) :target "_blank" :rel "noopener noreferrer" :style "font-size:0.75rem"} (:domain pi)]))
                      [:span.pf-row
                       [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
                        {:title (if (:enabled pi) (:disable tip) (:enable tip))
                         :hx-post "/smolvm/port/toggle"
                         :hx-target "#smolvm-tbody"
                         :hx-swap "innerHTML"
                         :hx-vals (mutation-vals csrf-token {:id (:id m) :port p})}
                        (ui.icon/icon (if (:enabled pi) :pause :play) {:size 13})]
                       [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square.pf-text-error
                        {:title (:unexpose tip)
                         :hx-post "/smolvm/port/remove"
                         :hx-target "#smolvm-tbody"
                         :hx-swap "innerHTML"
                         :hx-confirm (:delete-port cfm)
                         :hx-vals (mutation-vals csrf-token {:id (:id m) :port p})}
                        (:delete btn)]]])))
               [:div.pf-row {:style "margin-top:0.25rem"}
                [:input.pf-input.pf-input-inline {:style "width:4.5rem" :type "number" :name "port" :placeholder "端口" :min 1 :max 65535}]
                [:input.pf-input.pf-input-inline {:style "width:6rem" :type "text" :name "remark" :placeholder (:placeholder-remark fm)}]
                [:button.pf-btn.pf-btn-outline.pf-btn-success.pf-btn-xs
                 {:hx-post "/smolvm/port/add"
                  :hx-target "#smolvm-tbody"
                  :hx-swap "innerHTML"
                  :hx-include "closest .card"
                  :hx-vals (mutation-vals csrf-token {:id (:id m)})}
                 (:add-port btn)]]))]]])))))

;; ============================================================
;; 片段注册表、快照差异与 OOB 响应构建
;; ============================================================

(def all-atoms [forward/state caddy/state docker/state smolvm/state activity/store])

;; 活动记录渲染函数定义在文件靠后位置（与命令面板相邻），此处先声明以便片段注册表引用。
(declare render-activity-content)

(def ^:private fragment-registry
  "将片段 DOM id 映射到 `{:render fn, :atoms #{atom ...}}`。
   `:render` 函数接收 `{:username :is-admin :csrf :user}` 上下文。"
  {"forward-tbody" {:render (fn [ctx]
                              (list [:tbody {:id "forward-tbody"}
                                     (render-forwards-tbody (:username ctx) (:is-admin ctx) (:csrf ctx))]))
                    :atoms #{forward/state}}
   "caddy-tbody" {:render (fn [ctx]
                            (list [:tbody {:id "caddy-tbody"}
                                   (render-caddy-tbody (:username ctx) (:is-admin ctx) (:csrf ctx))]))
                  :atoms #{caddy/state}}
   "docker-tbody" {:render (fn [ctx]
                             (list [:div {:id "docker-tbody"}
                                    (render-docker-tbody (:username ctx) (:is-admin ctx) (:csrf ctx))]))
                   :atoms #{docker/state caddy/state}}
   "smolvm-tbody" {:render (fn [ctx]
                             (list [:div {:id "smolvm-tbody"}
                                    (render-smolvm-tbody (:username ctx) (:is-admin ctx) (:csrf ctx))]))
                   :atoms #{smolvm/state caddy/state}}
   "quota-bar" {:render (fn [ctx]
                          (list [:div {:id "quota-bar"}
                                 (render-quota-bar-content (:username ctx) (:user ctx))]))
                :atoms #{forward/state caddy/state docker/state smolvm/state}}
   "admin-containers-tbody" {:render (fn [ctx]
                                       (list [:tbody {:id "admin-containers-tbody"}
                                              (render-admin-containers-tbody (:csrf ctx))]))
                             :atoms #{}}
   "overview-stats" {:render (fn [ctx]
                               (list [:div {:id "overview-stats"}
                                      (render-overview-stats-content (:username ctx) (:user ctx))]))
                     :atoms #{forward/state caddy/state docker/state smolvm/state}}
   "topology-view" {:render (fn [ctx]
                              (list [:div {:id "topology-view"}
                                     (render-topology-content (:username ctx) (:is-admin ctx))]))
                    :atoms #{forward/state caddy/state docker/state smolvm/state}}
   "port-spectrum" {:render (fn [ctx]
                              (list [:div {:id "port-spectrum"}
                                     (render-port-spectrum-content (:username ctx) (:is-admin ctx))]))
                    :atoms #{forward/state caddy/state docker/state smolvm/state}}
   "activity-feed" {:render (fn [_ctx]
                              (list [:div {:id "activity-feed"}
                                     (render-activity-content)]))
                    :atoms #{activity/store}}
   "host-metrics" {:render (fn [ctx]
                             (list [:div {:id "host-metrics"}
                                    (render-host-metrics-content (:username ctx) (:is-admin ctx))]))
                   :atoms #{}}})

(defn snapshot-atoms
  "取得所有受跟踪 atom 的当前值快照。"
  [atoms]
  (zipmap atoms (map deref atoms)))

(defn- changed-atoms
  "比较变更前后的快照，返回值发生变化的 atom 集合。"
  [before-snap after-snap]
  (set (filter (fn [a] (not= (get before-snap a) (get after-snap a)))
               (keys before-snap))))

(defn- add-oob-attr
  "为顶层 hiccup 元素加上 `hx-swap-oob=\"innerHTML\"`。
   用 innerHTML 而不是 true（outerHTML），是为了保留片段容器自身的 `hx-get`/`hx-trigger`/`hx-swap`
   等属性，否则第一次 OOB 替换后轮询与 WS 刷新会失效（总览可视化面板尤其依赖此行为）。"
  [hiccup-el]
  (let [tag (first hiccup-el)
        second-el (second hiccup-el)]
    (if (map? second-el)
      (vec (cons tag (cons (assoc second-el :hx-swap-oob "innerHTML") (nnext hiccup-el))))
      (vec (list tag {:hx-swap-oob "innerHTML"} second-el)))))

(defn build-oob-response
  "构建 HTMX 响应，并为所有状态变化附加 OOB 片段。
   `primary-id` 是主目标片段的 DOM id，`ctx` 包含用户与 CSRF 上下文，
   `before-snap` 是 mutation 执行前的 atom 快照。默认 HTTP 状态码为 200，
   失败时请用 [[build-oob-error-response]] 或显式传入 `status`。"
  ([primary-id ctx before-snap toast-msg toast-type]
   (build-oob-response primary-id ctx before-snap toast-msg toast-type 200))
  ([primary-id ctx before-snap toast-msg toast-type status]
   (let [after-snap (snapshot-atoms all-atoms)
         changed (changed-atoms before-snap after-snap)
         primary (get fragment-registry primary-id)
         primary-html ((:render primary) ctx)
         oob-fragments (for [[fid entry] fragment-registry
                             :when (and (not= fid primary-id)
                                        (some changed (:atoms entry)))]
                         entry)
         oob-html (map (fn [entry]
                         (let [rendered ((:render entry) ctx)]
                           (add-oob-attr (first rendered))))
                       oob-fragments)]
     {:status status
      :headers {"Content-Type" "text/html; charset=utf-8"
                "HX-Trigger" (json/generate-string
                              {:show-toast {:message toast-msg :type toast-type}}
                              {:escape-non-ascii true})}
      :body (str (h/html (cons primary-html oob-html)))})))

(defn build-oob-error-response
  "错误分支专用：从异常 `e` 推导安全消息与正确的 HTTP 失败状态码（见 `core/error-info`），
   不将 `(.getMessage e)` 直接透传给客户端。"
  [primary-id ctx before-snap e]
  (let [{:keys [message status]} (core/error-info e)]
    (build-oob-response primary-id ctx before-snap message "error" status)))

;; ============================================================
;; 活动记录（飞行记录仪）
;; 展示 observability.activity 的进程内环形缓冲；只读，不触发任何修复。
;; ============================================================

(def ^:private activity-limit 30)

(defn- activity-severity-class [sev]
  (case sev
    :error "pf-activity-item--error"
    :warning "pf-activity-item--warning"
    "pf-activity-item--info"))

(defn- activity-severity-label [sev]
  (case sev
    :error "错误"
    :warning "警告/自愈"
    "信息"))

(defn- format-activity-time [ts]
  (try
    (let [local (.atZone (java.time.Instant/parse (str ts)) (java.time.ZoneId/systemDefault))]
      (format "%02d:%02d:%02d" (.getHour local) (.getMinute local) (.getSecond local)))
    (catch Exception _ "—")))

(defn- activity-resource-text [resource]
  (when resource
    (str (or (:kind resource) "")
         (when (:id resource) (str " " (:id resource))))))

(defn- activity-tab [resource]
  (case (:kind resource)
    "forward" "forwards"
    "caddy" "forwards"
    "container" "docker"
    "smolvm" "smolvm"
    nil))

(defn render-activity-items
  "渲染活动事件条目（总览活动卡与大屏共用）。`events` 为 `activity/recent` 的结果。"
  [events]
  (if (empty? events)
    [:div.pf-activity-empty "暂无活动记录"]
    (for [e events]
      (let [resource (:resource e)
            key (:key resource)
            sev (:severity e)
            tab (activity-tab resource)]
        [:div.pf-activity-item {:class (activity-severity-class sev)
                                :data-severity (name (or sev :info))
                                :data-resource-key key
                                :data-tab tab}
         [:div.pf-activity-head
          [:span.pf-activity-dot]
          [:span.pf-activity-title (or (:summary e) (name (:action e)))]
          [:span.pf-activity-sev (activity-severity-label sev)]
          [:span.pf-activity-time (format-activity-time (:timestamp e))]]
         [:div.pf-activity-meta
          [:span.pf-activity-actor (or (:actor e) "系统")]
          " · "
          (if (and key tab)
            [:a.pf-activity-link {:href "#" :data-resource-key key :data-tab tab
                                  :data-subtab-target (when (= tab "forwards") "forward-port")}
             (activity-resource-text resource)]
            [:span (activity-resource-text resource)])
          " · "
          [:span.pf-text-muted (if (= (:outcome e) :failed) "失败" "成功")]]]))))

(defn render-activity-content
  "渲染总览活动记录卡内部内容（含级别客户端筛选）。"
  []
  (let [events (activity/recent activity-limit)
        errors (count (filter #(= :error (:severity %)) events))
        warnings (count (filter #(= :warning (:severity %)) events))]
    (h/html
     [:div.pf-card-head
      [:h3.pf-card-head-title
       (ui.icon/icon :activity {:size 16})
       "活动记录"
       [:span.pf-tag "飞行记录仪"]]
      [:div.pf-status-filter {:role "group" :aria-label "级别筛选"}
       [:button {:type "button" :data-action "activity.filter" :data-filter "all" :aria-pressed "true"} "全部"]
       [:button {:type "button" :data-action "activity.filter" :data-filter "info" :aria-pressed "false"} "信息"]
       [:button {:type "button" :data-action "activity.filter" :data-filter "warning" :aria-pressed "false"} (str "警告 " warnings)]
       [:button {:type "button" :data-action "activity.filter" :data-filter "error" :aria-pressed "false"} (str "错误 " errors)]]]
     [:div.pf-activity-list {:id "activity-list"} (render-activity-items events)]
     [:div.pf-activity-foot (str "记录包含时间、操作者、资源、描述与严重程度；缓冲上限 " activity/capacity " 条。")])))

;; ============================================================
;; 命令面板（⌘K）
;; 服务端只按角色/能力输出命令注册表；面板列表与交互由 public/console.js 从注册表构建。
;; 面板本身不执行 mutation，命令只做导航或打开已有 Drawer/表单。
;; ============================================================

(defn- command-items [is-admin?]
  (cond-> []
    true
    (conj {:id "expose-port" :category "常用命令" :label "暴露端口"
           :description "打开转发创建抽屉，配置公网入口映射"
           :keywords "port forward expose add 端口 转发 暴露 添加"
           :icon :plus
           :attrs {:data-action "drawer.open" :data-drawer "forward-drawer"}})
    (cli/available?)
    (conj {:id "create-container" :category "常用命令" :label "创建容器"
           :description "打开容器创建抽屉"
           :keywords "container docker podman 容器 创建"
           :icon :box
           :attrs {:data-action "drawer.open" :data-drawer "docker-drawer"}})
    (:smolvm-available? config)
    (conj {:id "create-machine" :category "常用命令" :label "创建虚拟机"
           :description "打开 SmolVM 创建抽屉"
           :keywords "vm smolvm 虚拟机 微虚机 创建"
           :icon :server
           :attrs {:data-action "drawer.open" :data-drawer "smolvm-drawer"}})
    is-admin?
    (conj {:id "manage-hosts" :category "常用命令" :label "主机容器分配"
           :description "进入管理页的主机容器分配子页"
           :keywords "admin manage host container 管理 主机 分配"
           :icon :shield
           :attrs {:data-action "tabs.activate" :data-tab "admin"}
           :then "[data-subtab=\"admin-hosts\"]"})
    true
    (conj {:id "nav-overview" :category "页面跳转" :label "返回总览"
           :description "查看首屏接线图、端口频谱与运行指标"
           :keywords "overview dashboard home 总览 首页 概览"
           :icon :layout-dashboard
           :attrs {:data-action "tabs.activate" :data-tab "overview"}})
    true
    (conj {:id "nav-forwards" :category "页面跳转" :label "端口转发列表"
           :description "查看并筛选所有端口转发规则"
           :keywords "forward port 转发 端口 列表"
           :icon :arrow-right-left
           :attrs {:data-action "tabs.activate" :data-tab "forwards"}})
    (not (:ip-mode config))
    (conj {:id "nav-caddy" :category "页面跳转" :label "域名反向代理"
           :description "进入转发页的域名反代子页"
           :keywords "caddy domain proxy 反代 域名 代理"
           :icon :layers
           :attrs {:data-action "tabs.activate" :data-tab "forwards"}
           :then "[data-subtab=\"forward-caddy\"]"})
    true
    (conj {:id "nav-containers" :category "页面跳转" :label "容器实例管理"
           :description "查看容器引擎生命周期与端口绑定"
           :keywords "container docker 容器 实例"
           :icon :box
           :attrs {:data-action "tabs.activate" :data-tab "docker"}})
    (:smolvm-available? config)
    (conj {:id "nav-machines" :category "页面跳转" :label "微虚机 SmolVM"
           :description "查看轻量隔离微虚机状态与端口"
           :keywords "vm smolvm 虚拟机 微虚机"
           :icon :server
           :attrs {:data-action "tabs.activate" :data-tab "smolvm"}})
    is-admin?
    (conj {:id "nav-admin" :category "页面跳转" :label "管理与配额"
           :description "查看用户、配额与主机容器分配"
           :keywords "admin manage quota user 管理 配额 用户"
           :icon :settings
           :attrs {:data-action "tabs.activate" :data-tab "admin"}})
    true
    (conj {:id "filter-anomalies" :category "运维诊断" :label "筛选异常资源"
           :description "跳转转发列表并筛选异常规则"
           :keywords "anomaly dead warning error 异常 告警 故障 不可达"
           :icon :alert-triangle
           :attrs {:data-action "tabs.activate" :data-tab "forwards"}
           :then "[data-list-filter=\"forward-tbody\"][data-filter=\"dead\"]"})))

(defn render-command-registry
  "渲染隐藏的命令注册表。每个命令是一个承载真实行为属性的按钮，
   `public/console.js` 读取它构建 ⌘K 面板列表，执行时直接 `click()` 该按钮。"
  [is-admin?]
  (h/html
   [:div {:id "command-registry" :hidden true :aria-hidden "true"}
    (for [c (command-items is-admin?)]
      (let [attrs (merge {:type "button"
                          :class "pf-command-source"
                          :data-command (:id c)
                          :data-command-label (:label c)
                          :data-command-description (:description c)
                          :data-command-category (:category c)
                          :data-command-keywords (:keywords c)
                          :data-command-then (:then c)}
                         (:attrs c))]
        [:button attrs
         (ui.icon/icon (:icon c) {:size 16})
         [:span (:label c)]]))]))

(defn render-command-palette
  "渲染命令面板覆盖层骨架（默认隐藏）。列表与筛选由 `public/console.js` 从
   `#command-registry` 构建，无 JS 时整块隐藏、不影响既有功能。"
  []
  (h/html
   [:div.pf-palette {:id "command-palette" :hidden true :role "dialog" :aria-modal "true" :aria-label "命令面板"}
    [:button.pf-palette-backdrop {:type "button" :tabindex "-1" :aria-label "关闭命令面板" :data-action "palette.close"}]
    [:div.pf-palette-modal
     [:div.pf-palette-head
      (ui.icon/icon :search {:size 18})
      [:input.pf-palette-input {:id "command-palette-input" :type "text" :autocomplete "off" :spellcheck "false"
                                :placeholder "搜索命令，例如：暴露端口、创建容器、Kiosk…"
                                :role "combobox" :aria-expanded "true" :aria-controls "command-palette-list"}]
      [:button.pf-palette-clear {:type "button" :data-action "palette.clear"} "清除"]
      [:button.pf-palette-esc {:type "button" :data-action "palette.close"} "Esc"]]
     [:div.pf-palette-list {:id "command-palette-list" :role "listbox" :aria-label "可用命令"}]
     [:div.pf-palette-empty {:id "command-palette-empty" :hidden true}
      (ui.icon/icon :search {:size 28})
      [:p "无匹配命令"]
      [:p "请尝试输入 暴露、容器、大屏 或 转发"]]
     [:div.pf-palette-foot
      [:span "↑↓ 切换选择 · Enter 执行 · Esc 关闭"]
      [:span.pf-palette-count {:id "command-palette-count"} "0 个命令"]]]]))

;; ============================================================
;; 控制台页面
;; ============================================================

(defn render-dashboard [req]
  (let [user (:user req)
        username (:username user)
        is-admin? (= (:role user) "admin")
        csrf-token (core/csrf-generate)
        dash (:dashboard i18n/strings)
        fm (:form i18n/strings)
        btn (:button i18n/strings)
        tbl (:table i18n/strings)
        t (:tabs i18n/strings)
        show-caddy? (not (:ip-mode config))
        show-smolvm? (:smolvm-available? config)]

    (core/html-response
     [:html {:lang (:html-lang (:app i18n/strings)) :data-theme "dark"}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:meta {:name "csrf-token" :content csrf-token}]
       [:title (:title (:app i18n/strings))]
       [:link {:href (core/asset-url "app.css") :rel "stylesheet"}]
       [:script {:src "https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js"}]]
      [:body {:hx-headers (json/generate-string {"X-CSRF-Token" csrf-token})}
       [:div.pf-app

        [:header.pf-topbar
         [:div.pf-topbar-brand
          [:span.pf-logo "CL"]
          [:span.pf-topbar-title
           [:span "运维控制台"]
           [:span.pf-topbar-subtitle "Culvert"]]]
         [:div.pf-topbar-meta
          [:span.pf-ws-indicator {:id "ws-indicator" :title "WebSocket"}]
          [:div.pf-quota-chips {:id "quota-bar" :hx-get "/panel/quota" :hx-trigger "refresh-quota from:body, every 120s" :hx-swap "innerHTML"}
           (render-quota-bar-content username user)]
          [:button.pf-cmdk {:type "button" :id "command-palette-trigger" :data-action "palette.open"
                            :aria-haspopup "dialog" :title "按 ⌘K 或 Ctrl+K 打开命令面板"}
           (ui.icon/icon :search {:size 14})
           [:span.pf-cmdk-label "快捷命令"]
           [:kbd "⌘K"]]
          [:div.pf-user-chip
           (ui.icon/icon :user {:size 14})
           [:span.pf-user-name username]
           (if is-admin?
             [:span.pf-badge.pf-badge-neutral (:user-badge-admin dash)]
             [:span.pf-badge.pf-badge-neutral (:user-badge-user dash)])]]]

        ;; IP Mode Banner
        (when (:ip-mode config)
          [:div.pf-alert.pf-alert--info.pf-banner {:role "alert"}
           (ui.icon/icon :info {:size 18 :class "pf-alert-icon"})
           [:span [:strong (:ip-mode-title dash)] (:ip-mode-desc dash) " " [:code (:public-ip config)] " IP:PORT 直连"]])

        [:nav.pf-tabs {:role "tablist" :aria-label "主导航" :id "app-tabs"}
         (ui.tabs/tab-btn "overview" (ui.icon/icon :layout-dashboard) (:overview t) true)
         (ui.tabs/tab-btn "forwards" (ui.icon/icon :arrow-right-left) (:forwards t) false)
         (ui.tabs/tab-btn "docker" (ui.icon/icon :box) (:docker t) false)
         (when show-smolvm? (ui.tabs/tab-btn "smolvm" (ui.icon/icon :server) (:smolvm t) false))
         (when is-admin? (ui.tabs/tab-btn "admin" (ui.icon/icon :shield) (:admin t) false))
         [:span.pf-tabs-indicator {:id "tabs-indicator" :aria-hidden "true"}]]

        [:main.pf-content {:id "app-tab-content"}

         [:section.pf-panel {:id "tab-overview" :data-tab-panel "overview" :role "tabpanel" :aria-labelledby "tabbtn-overview"}
          [:div {:id "overview-stats" :hx-get "/panel/overview" :hx-trigger "refresh-overview from:body, every 120s" :hx-swap "innerHTML"}
           (render-overview-stats-content username user)]
          [:div.pf-overview-body
           [:div.pf-overview-main
            [:div {:id "topology-view" :hx-get "/panel/topology" :hx-trigger "refresh-topology from:body, every 30s" :hx-swap "innerHTML"}
             (render-topology-content username is-admin?)]
            [:div {:id "port-spectrum" :hx-get "/panel/spectrum" :hx-trigger "refresh-spectrum from:body, every 60s" :hx-swap "innerHTML"}
             (render-port-spectrum-content username is-admin?)]]
           [:div {:id "activity-feed" :hx-get "/panel/activity" :hx-trigger "refresh-activity from:body, every 120s" :hx-swap "innerHTML"}
            (render-activity-content)]]]

         [:section.pf-panel {:id "tab-admin" :data-tab-panel "admin" :role "tabpanel" :aria-labelledby "tabbtn-admin" :hidden true}
          (when is-admin?
            [:div.pf-host {:id "host-metrics" :hx-get "/panel/host"
                           :hx-trigger "refresh-host from:body, every 60s" :hx-swap "innerHTML"}
             (render-host-metrics-content username is-admin?)])
          (when is-admin?
            [:div.pf-segmented {:role "tablist" :aria-label "管理员子导航" :id "admin-subtabs"}
             [:button.pf-segmented-btn {:type "button" :role "tab" :id "subtabbtn-admin-users" :data-subtab "admin-users" :aria-selected "true" :aria-controls "subtab-admin-users" :tabindex "0"} (:admin-panel dash)]
             [:button.pf-segmented-btn {:type "button" :role "tab" :id "subtabbtn-admin-hosts" :data-subtab "admin-hosts" :aria-selected "false" :aria-controls "subtab-admin-hosts" :tabindex "-1"} (:admin-container-panel dash)]])
        ;; Admin Panel
          (when is-admin?
            [:div {:data-tab-panel-sub "admin-users" :id "subtab-admin-users"}
             [:div.pf-card.pf-card--accent-error {:id "admin-panel"}
              [:div.pf-card-body
               [:div.pf-card-title.pf-card-title--error (:admin-panel dash)]
            ;; Create User Form
               [:form.pf-form-row
                {:id "admin-create-user"
                 :hx-post "/admin/users/create"
                 :hx-target "#admin-users-tbody"
                 :hx-swap "innerHTML"
                 :data-reset-on-success "true"}
                [:input {:type "hidden" :name "_csrf" :value csrf-token}]
                [:div.pf-field
                 [:label {:for "admin-new-username"}
                  [:span.pf-label-text (:label-username fm)]]
                 [:input.pf-input {:name "username" :id "admin-new-username" :placeholder (:placeholder-new-user fm) :required true}]]
                [:div.pf-field
                 [:label {:for "admin-new-password"}
                  [:span.pf-label-text (:label-password fm)]]
                 [:input.pf-input {:name "password" :id "admin-new-password" :type "password" :placeholder (:placeholder-password fm) :required true}]]
                [:div.pf-field
                 [:label {:for "admin-new-role"}
                  [:span.pf-label-text (:label-role fm)]]
                 [:select.pf-select {:name "role" :id "admin-new-role"}
                  [:option {:value "user"} (:option-user fm)]
                  [:option {:value "admin"} (:option-admin fm)]]]
                [:div.pf-form-actions
                 [:button.pf-btn.pf-btn-success.pf-btn-block {:type "submit" :id "btn-admin-create"} (:create-user btn)]]]

            ;; Users Table
               [:div.pf-table-wrap
                [:table.pf-table.pf-table--zebra {:id "admin-users-table"}
                 [:thead
                  [:tr
                   [:th (:username tbl)]
                   [:th (:role tbl)]
                   [:th (:ports tbl)]
                   [:th (:proxies tbl)]
                   [:th (:containers tbl)]
                   [:th (:smolvm tbl)]
                   [:th (:cpu tbl)]
                   [:th (:memory tbl)]
                   [:th (:created tbl)]
                   [:th (:actions tbl)]]]
                 [:tbody {:id "admin-users-tbody" :hx-get "/panel/users" :hx-trigger "refresh-admin from:body, every 120s" :hx-swap "innerHTML" :data-loaded "false"}
                  (ui.skeleton/table-rows 10 3)]]]]]])

        ;; Host Container Assignment Card
          (when is-admin?
            [:div {:data-tab-panel-sub "admin-hosts" :id "subtab-admin-hosts" :hidden true}
             [:div.pf-card.pf-card--accent-info {:id "host-containers-panel"}
              [:div.pf-card-body
               [:div.pf-card-title.pf-card-title--info (:admin-container-panel dash)]
               [:p.pf-text-muted (:admin-container-desc dash)]
               (let [hcs (docker/get-all-host-containers)
                     total (count hcs)
                     assigned (count (filter :assignedUser hcs))]
                 [:p.pf-callout-info {:id "admin-container-summary"}
                  ((:admin-container-summary dash) {:total total :assigned assigned :unassigned (- total assigned)})])
               [:div.pf-table-wrap
                [:table.pf-table.pf-table--zebra {:id "admin-containers-table"}
                 [:thead
                  [:tr
                   [:th (:container-name tbl)]
                   [:th (:container-id tbl)]
                   [:th (:image tbl)]
                   [:th (:host-status tbl)]
                   [:th (:assigned-user tbl)]
                   [:th (:actions tbl)]]]
                 [:tbody {:id "admin-containers-tbody" :hx-get "/admin/containers" :hx-trigger "refresh-admin-containers from:body, every 120s" :hx-swap "innerHTML" :data-loaded "false"}
                  (ui.skeleton/table-rows 6 3)]]]]]])]

         [:section.pf-panel {:id "tab-forwards" :data-tab-panel "forwards" :role "tabpanel" :aria-labelledby "tabbtn-forwards" :hidden true}
          [:div.pf-panel-header
           [:div
            [:h2.pf-panel-title (:forwards t)]
            [:p.pf-panel-desc (:forwards-desc t)]]]

          [:div.pf-segmented {:role "tablist" :aria-label "转发子导航" :id "forward-subtabs"}
           [:button.pf-segmented-btn {:type "button" :role "tab" :id "subtabbtn-forward-port" :data-subtab "forward-port" :aria-selected "true" :aria-controls "subtab-forward-port" :tabindex "0"} "端口转发"]
           (when show-caddy?
             [:button.pf-segmented-btn {:type "button" :role "tab" :id "subtabbtn-forward-caddy" :data-subtab "forward-caddy" :aria-selected "false" :aria-controls "subtab-forward-caddy" :tabindex "-1"} "域名反代"])]

          ;; --- 子面板：端口转发 ---
          [:div {:data-tab-panel-sub "forward-port" :id "subtab-forward-port"}
           [:div.pf-list-toolbar
            [:h3.pf-list-toolbar-title "端口转发规则"]
            [:div.pf-list-toolbar-tools
             [:label.pf-search
              (ui.icon/icon :search {:size 14 :class "pf-search-icon"})
              [:input.pf-input {:type "text" :placeholder "搜索端口、目标、备注…" :data-list-search "forward-tbody"}]]
             [:div.pf-status-filter {:role "group" :aria-label "状态筛选"}
              [:button {:type "button" :data-list-filter "forward-tbody" :data-filter "all" :aria-pressed "true"} "全部"]
              [:button {:type "button" :data-list-filter "forward-tbody" :data-filter "alive" :aria-pressed "false"} "运行中"]
              [:button {:type "button" :data-list-filter "forward-tbody" :data-filter "dead" :aria-pressed "false"} "异常"]]
             [:button.pf-btn.pf-btn-primary {:type "button" :id "btn-open-forward-drawer" :data-action "drawer.open" :data-drawer "forward-drawer"}
              (ui.icon/icon :plus {:size 14}) (:add-forward btn)]]]

           (ui.drawer/drawer
            {:id "forward-drawer"
             :title (:add-forward btn)
             :body [:form.pf-drawer-body
                    {:id "forward-add-form"
                     :hx-post "/add"
                     :hx-target "#forward-tbody"
                     :hx-swap "innerHTML"
                     :data-reset-on-success "true"
                     :data-close-drawer-on-success "forward-drawer"}
                    [:input {:type "hidden" :name "_csrf" :value csrf-token}]
                    [:div.pf-field
                     [:label {:for "forward-ip"} [:span.pf-label-text (:label-target-ip fm)]]
                     [:input.pf-input {:name "ip" :id "forward-ip" :placeholder (:placeholder-ip fm) :required true}]]
                    [:div.pf-field
                     [:label {:for "forward-port"} [:span.pf-label-text (:label-source-port fm)]]
                     [:input.pf-input {:name "port" :id "forward-port" :placeholder (:placeholder-src-port fm) :required true}]
                     (when (:port-forward-range config)
                       [:span.pf-field-hint (str "范围: " (get-in config [:port-forward-range :start]) "-" (get-in config [:port-forward-range :end]))])]
                    [:div.pf-field
                     [:label {:for "forward-toPort"} [:span.pf-label-text (:label-target-port fm)]]
                     [:input.pf-input {:name "toPort" :id "forward-toPort" :type "number" :min 1 :max 65535 :placeholder (:placeholder-target-port fm) :required true}]]
                    [:div.pf-field
                     [:label {:for "forward-protocol"} [:span.pf-label-text (:label-protocol fm)]]
                     [:select.pf-select {:name "protocol" :id "forward-protocol" :required true}
                      [:option {:value "tcp"} "TCP"]
                      [:option {:value "udp"} "UDP"]]]
                    [:div.pf-field
                     [:label {:for "forward-method"} [:span.pf-label-text (:label-method fm)]]
                     [:select.pf-select {:name "method" :id "forward-method"}
                      [:option {:value "socat" :selected true} "socat"]
                      [:option {:value "iptables"} "iptables"]]]
                    [:div.pf-field
                     [:label {:for "forward-remark"} [:span.pf-label-text (:label-remark fm)]]
                     [:input.pf-input {:name "remark" :id "forward-remark" :placeholder (:placeholder-remark fm)}]]
                    [:button.pf-btn.pf-btn-primary.pf-btn-block {:type "submit" :id "btn-add-forward"} (:add-forward btn)]]})

           [:div.pf-table-wrap
            [:table.pf-table.pf-table--zebra
             [:thead
              [:tr
               [:th (:resource-name tbl)]
               [:th (:ingress tbl)]
               [:th (:engine tbl)]
               [:th (:internal-target tbl)]
               [:th (:protocol tbl)]
               [:th (:latency-runtime tbl)]
               [:th (:status tbl)]
               [:th (:owner tbl)]
               [:th (:actions tbl)]]]
             [:tbody {:id "forward-tbody" :hx-get "/panel/forwards" :hx-trigger "refresh-forward from:body, every 120s" :hx-swap "innerHTML" :data-loaded "false"}
              (ui.skeleton/table-rows 9 3)]]]
           [:p.pf-footer-note
            [:b (:footer-socat dash)] "/" [:b (:footer-iptables dash)]
            (ui.popover/popover
             {:id "forward-help"
              :content (list
                        [:p [:b (:footer-socat dash)] (:footer-socat-desc dash)]
                        [:p [:b (:footer-iptables dash)] (:footer-iptables-desc dash)]
                        [:p (:footer-range-help dash) [:code "8080-8085"] (:footer-range-help-desc dash) [:code "80"] (:footer-range-help-mapping dash)])})]]

          ;; --- 子面板：域名反代（合并进转发 Tab） ---
          (when show-caddy?
            [:div {:data-tab-panel-sub "forward-caddy" :id "subtab-forward-caddy" :hidden true}
             [:div.pf-list-toolbar
              [:h3.pf-list-toolbar-title "域名反向代理"]
              [:div.pf-list-toolbar-tools
               [:label.pf-search
                (ui.icon/icon :search {:size 14 :class "pf-search-icon"})
                [:input.pf-input {:type "text" :placeholder "搜索域名、目标、备注…" :data-list-search "caddy-tbody"}]]
               [:div.pf-status-filter {:role "group" :aria-label "状态筛选"}
                [:button {:type "button" :data-list-filter "caddy-tbody" :data-filter "all" :aria-pressed "true"} "全部"]
                [:button {:type "button" :data-list-filter "caddy-tbody" :data-filter "alive" :aria-pressed "false"} "运行中"]
                [:button {:type "button" :data-list-filter "caddy-tbody" :data-filter "disabled" :aria-pressed "false"} "已停用"]]
               [:button.pf-btn.pf-btn-primary {:type "button" :id "btn-open-caddy-drawer" :data-action "drawer.open" :data-drawer "caddy-drawer"}
                (ui.icon/icon :plus {:size 14}) (:add-proxy btn)]]]

             (ui.drawer/drawer
              {:id "caddy-drawer"
               :title (:add-proxy btn)
               :body [:form.pf-drawer-body
                      {:id "caddy-add-form"
                       :hx-post "/caddy/add"
                       :hx-target "#caddy-tbody"
                       :hx-swap "innerHTML"
                       :data-reset-on-success "true"
                       :data-close-drawer-on-success "caddy-drawer"}
                      [:input {:type "hidden" :name "_csrf" :value csrf-token}]
                      [:div.pf-field
                       [:label {:for "caddy-domain"} [:span.pf-label-text (:label-domain fm)]]
                       [:select.pf-select {:name "domain" :id "caddy-domain" :required true}
                        (for [d (:caddy-domains config)]
                          [:option {:value d :selected (= d (:caddy-domain config))} d])]]
                      [:div.pf-field
                       [:label {:for "caddy-worker"} [:span.pf-label-text (:label-worker fm)]]
                       [:input.pf-input {:name "worker" :id "caddy-worker" :placeholder (:placeholder-worker fm) :required true}]]
                      [:div.pf-field
                       [:label {:for "caddy-target"} [:span.pf-label-text (:label-target-addr fm)]]
                       [:input.pf-input {:name "target" :id "caddy-target" :placeholder (:placeholder-target-addr fm) :required true}]]
                      [:div.pf-field
                       [:label {:for "caddy-remark"} [:span.pf-label-text (:label-remark fm)]]
                       [:input.pf-input {:name "remark" :id "caddy-remark" :placeholder (:placeholder-remark fm)}]]
                      [:button.pf-btn.pf-btn-primary.pf-btn-block {:type "submit" :id "btn-add-caddy"} (:add-proxy btn)]]})

             (when-not (caddy/available?)
               [:div.pf-alert {:role "alert"}
                (ui.icon/icon :alert-triangle {:size 18 :class "pf-alert-icon"})
                [:span [:strong (:caddy-unavailable dash)] (:caddy-install-hint dash)]])

             [:div.pf-table-wrap
              [:table.pf-table.pf-table--zebra
               [:thead
                [:tr
                 [:th (:resource-name tbl)]
                 [:th (:ingress tbl)]
                 [:th (:engine tbl)]
                 [:th (:internal-target tbl)]
                 [:th (:protocol tbl)]
                 [:th (:latency-runtime tbl)]
                 [:th (:status tbl)]
                 [:th (:owner tbl)]
                 [:th (:actions tbl)]]]
               [:tbody {:id "caddy-tbody" :hx-get "/panel/caddy" :hx-trigger "refresh-caddy from:body, every 120s" :hx-swap "innerHTML" :data-loaded "false"}
                (ui.skeleton/table-rows 9 3)]]]
             [:p.pf-footer-note
              [:b (:footer-caddy dash)]
              (ui.popover/popover {:id "caddy-help" :content [:p [:b (:footer-caddy dash)] (:footer-caddy-desc dash)]})]])]

         [:section.pf-panel {:id "tab-docker" :data-tab-panel "docker" :role "tabpanel" :aria-labelledby "tabbtn-docker" :hidden true}
          [:div.pf-panel-header
           [:div [:h2.pf-panel-title (:docker t)] [:p.pf-panel-desc (:docker-desc t)]]
           (when (cli/available?)
             [:div.pf-panel-actions
              [:button.pf-btn.pf-btn-primary {:type "button" :id "btn-open-docker-drawer" :data-action "drawer.open" :data-drawer "docker-drawer"} "+ " (:create-container btn)]])]

          (when (cli/available?)
            (ui.drawer/drawer
             {:id "docker-drawer"
              :title (:create-container btn)
              :body [:form.pf-drawer-body
                     {:id "docker-create-form"
                      :hx-post "/docker/create"
                      :hx-target "#docker-tbody"
                      :hx-swap "innerHTML"
                      :data-reset-on-success "true"
                      :data-close-drawer-on-success "docker-drawer"}
                     [:input {:type "hidden" :name "_csrf" :value csrf-token}]
                     [:div.pf-field
                      [:label {:for "docker-image"} [:span.pf-label-text (:label-image fm)]]
                      [:select.pf-select {:name "image" :id "docker-image" :required true}
                       [:option {:value ""} (:placeholder-image fm)]
                       (for [img (:images-config config)]
                         [:option {:value (:name img)} (str (:name img) " — " (:description img))])]]
                     [:div.pf-field
                      [:label {:for "docker-cpu"} [:span.pf-label-text (:label-cpu-cores fm)]]
                      [:input.pf-input {:name "cpuLimit" :id "docker-cpu" :type "text" :value (:default-cpu-limit config) :placeholder "CPU"}]]
                     [:div.pf-field
                      [:label {:for "docker-mem"} [:span.pf-label-text (:label-mem-limit fm)]]
                      [:input.pf-input {:name "memLimit" :id "docker-mem" :type "text" :value (:default-mem-limit config) :placeholder (:placeholder-mem fm)}]]
                     (when (cli/gpu-available?)
                       [:div.pf-field
                        [:label.pf-checkbox-label
                         [:input.pf-checkbox {:type "checkbox" :name "gpu" :id "docker-gpu" :value "on"}]
                         [:span (:label-gpu fm)]]])
                     [:p.pf-field-hint
                      (:container-rm-mode dash) [:code "--rm"] (:container-rm-mode-desc dash) [:code "sleep infinity"] (:container-rm-mode-desc2 dash)]

                     ;; S3 Configuration section
                     (when (s3/available?)
                       [:details
                        [:summary.pf-text-muted (:s3-mount-label dash)]
                        [:div.pf-inset-panel
                         [:small.pf-text-muted {:style "font-weight:600"} (:s3-mount-desc dash) [:code "/mnt/data"]]
                         [:div.pf-field
                          [:label {:for "docker-s3-endpoint"} [:span.pf-label-text (:label-s3-endpoint fm)]]
                          [:input.pf-input {:name "s3Endpoint" :id "docker-s3-endpoint" :type "text" :placeholder (:placeholder-s3-endpoint fm)}]
                          [:span.pf-field-hint (:s3-endpoint-default dash)]]
                         [:div.pf-field
                          [:label {:for "docker-s3-bucket"} [:span.pf-label-text (:label-s3-bucket fm)]]
                          [:input.pf-input {:name "s3Bucket" :id "docker-s3-bucket" :type "text" :placeholder (:placeholder-s3-bucket fm)}]]
                         [:div.pf-field
                          [:label {:for "docker-s3-access-key"} [:span.pf-label-text (:label-s3-access-key fm)]]
                          [:input.pf-input {:name "s3AccessKey" :id "docker-s3-access-key" :type "text" :placeholder (:placeholder-s3-access-key fm)}]]
                         [:div.pf-field
                          [:label {:for "docker-s3-secret-key"} [:span.pf-label-text (:label-s3-secret-key fm)]]
                          [:input.pf-input {:name "s3SecretKey" :id "docker-s3-secret-key" :type "password" :placeholder (:placeholder-s3-secret-key fm)}]]
                         [:div.pf-field
                          [:label {:for "docker-s3-region"} [:span.pf-label-text (:label-s3-region fm)]]
                          [:input.pf-input {:name "s3Region" :id "docker-s3-region" :type "text" :placeholder (:placeholder-s3-region fm)}]
                          [:span.pf-field-hint (:s3-region-optional dash)]]
                         [:div.pf-row
                          [:button.pf-btn {:type "button"
                                           :id "btn-s3-test"
                                           :hx-post "/docker/s3-test"
                                           :hx-target "#s3-test-result"
                                           :hx-swap "innerHTML"
                                           :hx-include "closest details"} (:s3-test-btn dash)]
                          [:span {:id "s3-test-result"}]]]])

                     [:button.pf-btn.pf-btn-primary.pf-btn-block {:type "submit" :id "btn-create-container"} (:create-container btn)]]}))

          [:div.pf-card
           [:div.pf-card-body
            [:div.pf-card-title (:docker-title dash) [:small.pf-text-muted (str "(" (:container-cli config) ")")]]

            (when-not (cli/available?)
              [:div.pf-alert {:role "alert"}
               [:svg.pf-alert-icon {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"}]]
               [:span [:strong ((:docker-unavailable dash) config)] (:docker-install-hint dash)]])

            (when (cli/available?)
              [:div.pf-resource-grid {:id "docker-tbody" :hx-get "/panel/docker" :hx-trigger "refresh-docker from:body, every 120s" :hx-swap "innerHTML" :data-loaded "false"}
               (ui.skeleton/resource-cards 3)])]]]

         [:section.pf-panel {:id "tab-smolvm" :data-tab-panel "smolvm" :role "tabpanel" :aria-labelledby "tabbtn-smolvm" :hidden true}
          [:div.pf-panel-header
           [:div [:h2.pf-panel-title (:smolvm t)] [:p.pf-panel-desc (:smolvm-desc t)]]
           (when (:smolvm-available? config)
             [:div.pf-panel-actions
              [:button.pf-btn.pf-btn-primary {:type "button" :id "btn-open-smolvm-drawer" :data-action "drawer.open" :data-drawer "smolvm-drawer"} "+ " (:create-machine btn)]])]

          (when (:smolvm-available? config)
            (ui.drawer/drawer
             {:id "smolvm-drawer"
              :title (:create-machine btn)
              :body [:form.pf-drawer-body
                     {:id "smolvm-create-form"
                      :hx-post "/smolvm/create"
                      :hx-target "#smolvm-tbody"
                      :hx-swap "innerHTML"
                      :data-reset-on-success "true"
                      :data-close-drawer-on-success "smolvm-drawer"}
                     [:input {:type "hidden" :name "_csrf" :value csrf-token}]
                     [:div.pf-field
                      [:label {:for "smolvm-image"} [:span.pf-label-text (:label-smolvm-image fm)]]
                      [:select.pf-select {:name "image" :id "smolvm-image" :required true}
                       [:option {:value ""} (:placeholder-smolvm-image fm)]
                       (for [img (:smolvm-images-config config)]
                         [:option {:value (:name img)} (str (:name img) " — " (:description img))])]]
                     [:div.pf-field
                      [:label {:for "smolvm-cpu"} [:span.pf-label-text (:label-smolvm-cpu fm)]]
                      [:input.pf-input {:name "cpuLimit" :id "smolvm-cpu" :type "text" :value (:smolvm-default-cpu config) :placeholder "vCPU"}]]
                     [:div.pf-field
                      [:label {:for "smolvm-mem"} [:span.pf-label-text (:label-smolvm-mem fm)]]
                      [:input.pf-input {:name "memLimit" :id "smolvm-mem" :type "text" :value (:smolvm-default-mem config) :placeholder "MiB"}]]
                     [:p.pf-field-hint (:smolvm-mode-desc dash)]

                     ;; Advanced options
                     [:details
                      [:summary.pf-text-muted "高级选项"]
                      [:div.pf-inset-panel
                       [:label.pf-checkbox-label
                        [:input.pf-checkbox {:type "checkbox" :name "netEnabled" :id "smolvm-net" :value "on"}]
                        [:span (:label-net-enabled fm)]]
                       [:label.pf-checkbox-label
                        [:input.pf-checkbox {:type "checkbox" :name "sshAgent" :id "smolvm-ssh" :value "on"}]
                        [:span (:label-ssh-agent fm)]]
                       [:div.pf-field
                        [:label {:for "smolvm-allow-hosts"} [:span.pf-label-text (:label-allow-hosts fm)]]
                        [:textarea.pf-textarea {:name "allowHosts" :rows 2 :placeholder "api.stripe.com\ngithub.com"}]]
                       [:div.pf-field
                        [:label {:for "smolvm-allow-cidrs"} [:span.pf-label-text (:label-allow-cidrs fm)]]
                        [:textarea.pf-textarea {:name "allowCidrs" :rows 2 :placeholder "10.0.0.0/8"}]]]]

                     [:button.pf-btn.pf-btn-primary.pf-btn-block {:type "submit" :id "btn-create-smolvm"} (:create-machine btn)]]}))

          (when (:smolvm-available? config)
            [:div.pf-card
             [:div.pf-card-body
              [:div.pf-card-title (:smolvm-title dash) [:small.pf-text-muted "(smolvm)"]]

              (when-not (smolvm-cli/available?)
                [:div.pf-alert {:role "alert"}
                 [:svg.pf-alert-icon {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                  [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"}]]
                 [:span [:strong (:smolvm-unavailable dash)] (:smolvm-install-hint dash)]])

              [:div.pf-resource-grid {:id "smolvm-tbody" :hx-get "/panel/smolvm" :hx-trigger "refresh-smolvm from:body, every 120s" :hx-swap "innerHTML" :data-loaded "false"}
               (ui.skeleton/resource-cards 3)]
              [:p.pf-footer-note
               (:smolvm-port-change-warning dash)]]])]]

        ;; Password Modal Container
        [:div {:id "password-modal-container"}]

        ;; Container Log Modal Container（应用内同源日志面板）
        [:div {:id "log-panel-container"}]

        ;; Admin Terminal Modal Container（管理员容器终端凭据）
        [:div {:id "terminal-modal-container"}]

        ;; 命令面板注册表与覆盖层（默认隐藏，纯增强）
        (render-command-registry is-admin?)
        (render-command-palette)]

        ;; Log viewer opens as popup window — no modal needed

       [:script {:src (core/asset-url "main.js")}]
       [:script {:src (core/asset-url "tabs.js")}]
       [:script {:src (core/asset-url "console.js")}]]])))

;; ============================================================
;; Kiosk 大屏（GET /wall）
;; 只读监看页：无一级导航/Drawer/命令面板/编辑入口；复用 P1 的接线 lane 与状态色。
;; ============================================================

(defn- wall-kpi [label value unit sub variant]
  [:div.pf-kiosk-kpi {:class variant}
   [:div.pf-kiosk-kpi-label label]
   [:div.pf-kiosk-kpi-value (str value) [:span.pf-kiosk-kpi-unit unit]]
   [:div.pf-kiosk-kpi-sub sub]])

(defn render-wall-kpis-content
  "大屏 KPI 卡内部内容。全部为真实指标，不造吞吐/事件数等无数据来源的数字。"
  [username is-admin?]
  (let [resources (vec (all-resources username is-admin?))
        alive (count (filter #(= :alive (:status %)) resources))
        abnormal (count (filter #(#{:dead :unknown} (:status %)) resources))
        forwards (filter #(= :forward (:type %)) resources)
        containers (count (filter #(= :container (:type %)) resources))
        machines (count (filter #(= :vm (:type %)) resources))]
    (h/html
     [:div.pf-kiosk-kpis
      (wall-kpi "在线资源" alive "个实例" (str "共 " (count resources) " 个资源") "pf-kiosk-kpi--ok")
      (wall-kpi "异常资源" abnormal "处告警" (if (pos? abnormal) "需要人工关注" "核心链路健康") "pf-kiosk-kpi--danger")
      (wall-kpi "端口转发" (count forwards) "条规则"
                (str "运行中 " (count (filter #(= :alive (:status %)) forwards)) " 条") "")
      (wall-kpi "容器 / 微虚机" (+ containers machines) "个实例"
                (str "容器 " containers " · 微虚机 " machines) "")])))

(defn- wall-topo-row [r]
  (let [status (:status r)
        stroke (get status-colors status "#D9A441")]
    [:div.pf-kiosk-topo-row
     {:data-resource-key (:key r)}
     [:div.pf-kiosk-topo-ingress
      [:span.pf-kiosk-topo-ingress-value (:ingress r)]
      [:span.pf-kiosk-topo-name (:name r)]]
     (topo-connector status)
     [:div.pf-kiosk-topo-engine
      [:div.pf-kiosk-topo-engine-chip {:data-status (name status)}
       [:span.pf-topo-dot {:style (str "background:" stroke)}]
       [:span (:engine r)]
       [:span.pf-topo-proto (:protocol r)]]]
     (topo-connector status)
     [:div.pf-kiosk-topo-target
      [:span.pf-kiosk-topo-target-main (:target r)]
      [:span.pf-kiosk-topo-target-lat (latency-text r)]]]))

(defn render-wall-topology-content
  "大屏系统接线概览内部内容：取前 4 条资源，复用 P1 的 lane 渲染，只读不可点。"
  [username is-admin?]
  (let [shown (vec (take 4 (all-resources username is-admin?)))]
    (h/html
     [:div.pf-kiosk-panel-head
      [:h2.pf-kiosk-panel-title
       (ui.icon/icon :activity {:size 18})
       "系统接线概览"]
      [:span.pf-kiosk-panel-note "远距离展示 · 实时连通性探测"]]
     (if (empty? shown)
       [:div.pf-kiosk-empty "暂无资源"]
       [:div.pf-kiosk-topo-rows (for [r shown] (wall-topo-row r))]))))

(defn- wall-alert-severity [r]
  (if (= :dead (:status r)) :error :warning))

(defn- wall-alert [r]
  (let [sev (wall-alert-severity r)]
    [:div.pf-kiosk-alert {:class (if (= sev :error) "pf-kiosk-alert--error" "pf-kiosk-alert--warning")
                          :data-resource-key (:key r)}
     [:div.pf-kiosk-alert-head
      [:span.pf-kiosk-alert-title (str (:type-label r) "异常")]
      [:span.pf-kiosk-alert-sev (if (= sev :error) "异常" "未知")]]
     [:div.pf-kiosk-alert-line (str (:ingress r) " → " (:target r))]
     [:div.pf-kiosk-alert-desc (if (= sev :error)
                                 "连接不可达或进程未运行"
                                 "无法判定状态，等待探针或人工确认")]
     [:div.pf-kiosk-alert-foot
      [:span (str "负责人 " (:owner r))]
      [:span (str "引擎 " (:engine r))]]]))

(defn render-wall-alerts-content
  "大屏异常告警内部内容：由四域状态计算 dead/unknown，dead 优先；
   并复用活动记录渲染展示最近的失败/自愈事件（飞行记录仪）。"
  [username is-admin?]
  (let [alerts (->> (all-resources username is-admin?)
                    (filter #(#{:dead :unknown} (:status %)))
                    (sort-by (comp {:dead 0 :unknown 1} :status))
                    vec)
        activity-events (->> (activity/recent 60)
                             (filter #(#{:warning :error} (:severity %)))
                             (take 5)
                             vec)]
    (h/html
     [:div.pf-kiosk-panel-head
      [:h2.pf-kiosk-panel-title.pf-kiosk-panel-title--danger
       (ui.icon/icon :alert-triangle {:size 18})
       "异常告警"]
      [:span.pf-kiosk-badge-danger (str (count alerts) " 处")]]
     (if (empty? alerts)
       [:div.pf-kiosk-empty "当前无异常，核心链路健康"]
       [:div.pf-kiosk-alerts (for [r alerts] (wall-alert r))])
     (when (seq activity-events)
       (list
        [:div.pf-kiosk-activity-head "自愈与失败事件"]
        [:div.pf-activity-list (render-activity-items activity-events)])))))

(defn render-wall [req]
  (let [user (:user req)
        username (:username user)
        is-admin? (= (:role user) "admin")
        csrf-token (core/csrf-generate)]
    (core/html-response
     [:html {:lang (:html-lang (:app i18n/strings)) :data-theme "dark"}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:meta {:name "csrf-token" :content csrf-token}]
       [:title "Culvert 监控大屏"]
       [:link {:href (core/asset-url "app.css") :rel "stylesheet"}]
       [:script {:src "https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js"}]]
      [:body {:hx-headers (json/generate-string {"X-CSRF-Token" csrf-token})}
       [:div.pf-kiosk
        [:header.pf-kiosk-head
         [:div.pf-kiosk-brand
          [:span.pf-live-dot]
          [:div
           [:h1.pf-kiosk-title "CULVERT MONITOR"]
           [:p.pf-kiosk-subtitle "NETWORK TOPOLOGY & STATUS OPS CENTER"]]]
         [:div.pf-kiosk-status
          [:span.pf-ws-indicator {:id "wall-ws-indicator" :data-ws-indicator true :data-state "connecting" :title "实时数据流"}]
          [:span.pf-kiosk-status-text "实时数据流"]
          [:div.pf-kiosk-clock
           [:div.pf-kiosk-time {:id "wall-clock"} "--:--:--"]
           [:div.pf-kiosk-date {:id "wall-date"} ""]]
          [:a.pf-kiosk-exit {:href "/"}
           (ui.icon/icon :arrow-right-left {:size 14})
           "返回控制台"]]]
        [:main.pf-kiosk-main
         [:div {:id "wall-kpis" :hx-get "/panel/wall-kpis"
                :hx-trigger "refresh-wall-kpis from:body, every 30s" :hx-swap "innerHTML"}
          (render-wall-kpis-content username is-admin?)]
         [:div.pf-kiosk-grid
          [:div.pf-kiosk-panel {:id "wall-topology" :hx-get "/panel/wall-topology"
                                :hx-trigger "refresh-wall-topology from:body, every 30s" :hx-swap "innerHTML"}
           (render-wall-topology-content username is-admin?)]
          [:div.pf-kiosk-panel {:id "wall-alerts" :hx-get "/panel/wall-alerts"
                                :hx-trigger "refresh-wall-alerts from:body, every 15s" :hx-swap "innerHTML"}
           (render-wall-alerts-content username is-admin?)]]]]
       [:script {:src (core/asset-url "main.js")}]
       [:script {:src (core/asset-url "console.js")}]]])))
