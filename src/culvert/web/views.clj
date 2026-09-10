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
            [culvert.s3 :as s3]
            [culvert.smolvm.cli :as smolvm-cli]
            [culvert.smolvm.manager :as smolvm]
            [culvert.web.ui.drawer :as ui.drawer]
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

(defn render-overview-stats-content
  "渲染总览 Tab 的资源数量摘要卡片，点击每张卡片激活对应 Tab。"
  [username]
  (let [usage (compute-usage username)
        t (:tabs i18n/strings)]
    (h/html
     [:div.pf-stat-grid
      [:button.pf-stat-card {:type "button" :data-action "tabs.activate" :data-tab "forwards"}
       [:span.pf-stat-label (:stat-forwards t)]
       [:span.pf-stat-value (:portForwards usage)]]
      (when-not (:ip-mode config)
        [:button.pf-stat-card {:type "button" :data-action "tabs.activate" :data-tab "caddy"}
         [:span.pf-stat-label (:stat-caddy t)]
         [:span.pf-stat-value (:reverseProxies usage)]])
      [:button.pf-stat-card {:type "button" :data-action "tabs.activate" :data-tab "docker"}
       [:span.pf-stat-label (:stat-docker t)]
       [:span.pf-stat-value (:containers usage)]]
      (when (or (:smolvm-available? config) (pos? (:smolvmMachines usage)))
        [:button.pf-stat-card {:type "button" :data-action "tabs.activate" :data-tab "smolvm"}
         [:span.pf-stat-label (:stat-smolvm t)]
         [:span.pf-stat-value (:smolvmMachines usage)]])])))

;; --- 模块化渲染函数 ---

(defn- mutation-vals [csrf-token params]
  (json/generate-string (assoc params :_csrf csrf-token)))

(defn- quota-cell [used input-attrs]
  (list
   [:span.pf-text-success.pf-mono used]
   " / "
   [:input.pf-input.pf-input-inline (merge {:size 4} input-attrs)]))

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
          [:div {:style "font-size:2rem;opacity:0.4"} "🐳"]
          [:span (:no-host-containers (:empty i18n/strings))]]]]
       (for [hc sorted]
         [:tr {:style (when-not (:assignedUser hc) (str "border-left:3px solid var(--pf-color-warning)"))
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
             [:span.pf-badge.pf-badge-info "👤 " (:assignedUser hc)]
             [:span.pf-badge.pf-badge-warning
              "⚠️ " (:container-unassigned dash)])]
          [:td
           [:div {:style "display:flex;flex-direction:column;gap:0.25rem"}
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
       [:tr [:td {:colspan (if is-admin? 9 8) :class "pf-empty"} (:no-forwards (:empty i18n/strings))]]
       (for [e entries]
         [:tr
          (when is-admin? [:td [:code (:userId e)]])
          [:td [:code (:port e)]
           (when (:isRange e)
             [:span.pf-text-muted {:style "font-size:0.75rem"} ((:port-range-count (:status i18n/strings)) (- (:toRangePort e) (:fromPort e) -1))])]
          [:td (str/upper-case (:protocol e))]
          [:td [:code (:ip e)]]
          [:td [:code (:toPortDisplay e)]
           (when (:isRange e)
             (list [:br]
                   [:span.pf-text-muted {:style "font-size:0.75rem"} (format "(%d→%s, ..., %d→%s)"
                                                                             (:fromPort e) (:toPort e)
                                                                             (:toRangePort e)
                                                                             (last (str/split (:toPortDisplay e) #"-")))]))]
          [:td (:method e)]
          [:td.remark-cell (:remark e)]
          [:td.status-cell
           (cond
             (false? (:enabled e)) [:span.pf-badge.pf-badge-ghost {:title (:title-manual-stop (:status i18n/strings))} (:disabled (:status i18n/strings))]
             (true? (:alive e)) [:span.pf-badge.pf-badge-success {:title (:title-process-running (:status i18n/strings))} (:running-text (:status i18n/strings))]
             (false? (:alive e)) [:span.pf-badge.pf-badge-error {:title (:title-partial-dead (:status i18n/strings))} (:dead (:status i18n/strings))]
             :else [:span.pf-text-muted {:title (:title-cant-detect (:status i18n/strings))} (:unknown (:status i18n/strings))])]
          [:td.actions-col
           [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
            {:title (:edit-remark (:tooltip i18n/strings))
             :hx-get (str "/forward/remark-form?port=" (:port e)
                          "&protocol=" (:protocol e)
                          "&method=" (:method e)
                          "&remark=" (java.net.URLEncoder/encode (or (:remark e) "") "UTF-8"))
             :hx-target "closest .remark-cell"
             :hx-swap "innerHTML"}
            "✏️"]
           [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
            {:title (if (false? (:enabled e)) (:enable (:tooltip i18n/strings)) (:disable (:tooltip i18n/strings)))
             :hx-post "/toggle"
             :hx-target "#forward-tbody"
             :hx-swap "innerHTML"
             :hx-vals (mutation-vals csrf-token {:port (:port e) :protocol (:protocol e) :method (:method e)})}
            (if (false? (:enabled e)) "▶️" "⏸️")]
           [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square.pf-text-error
            {:title (:delete (:tooltip i18n/strings))
             :hx-post "/remove"
             :hx-target "#forward-tbody"
             :hx-swap "innerHTML"
             :hx-confirm (:delete-forward (:confirm i18n/strings))
             :hx-vals (mutation-vals csrf-token {:port (:port e) :protocol (:protocol e) :method (:method e)})}
            (:delete (:button i18n/strings))]]])))))

(defn render-caddy-tbody [username is-admin? csrf-token]
  (let [caddy-entries (caddy/get-entries username is-admin?)]
    (h/html
     (if (empty? caddy-entries)
       [:tr [:td {:colspan (if is-admin? 8 7) :class "pf-empty"} (:no-caddy (:empty i18n/strings))]]
       (for [e caddy-entries]
         [:tr
          (when is-admin? [:td [:code (:userId e)]])
          [:td [:code (:domain e)]]
          [:td [:code (:worker e)]]
          [:td [:code (:fullDomain e)]
           (when-not (:enabled e) [:span.pf-text-muted {:style "margin-left:0.25rem"} (:disabled-text (:status i18n/strings))])]
          [:td [:code (:target e)]]
          [:td.remark-cell (:remark e)]
          [:td.status-cell
           (if (false? (:enabled e))
             [:span.pf-badge.pf-badge-ghost {:title (:title-manual-stop (:status i18n/strings))} (:disabled (:status i18n/strings))]
             [:span.pf-badge.pf-badge-success {:title (:title-caddy-managed (:status i18n/strings))} (:running-text (:status i18n/strings))])]
          [:td.actions-col
           [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
            {:title (:edit-remark (:tooltip i18n/strings))
             :hx-get (str "/caddy/remark-form?domain=" (:domain e)
                          "&worker=" (:worker e)
                          "&remark=" (java.net.URLEncoder/encode (or (:remark e) "") "UTF-8"))
             :hx-target "closest .remark-cell"
             :hx-swap "innerHTML"}
            "✏️"]
           [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square
            {:title (if (:enabled e) (:disable (:tooltip i18n/strings)) (:enable (:tooltip i18n/strings)))
             :hx-post "/caddy/toggle"
             :hx-target "#caddy-tbody"
             :hx-swap "innerHTML"
             :hx-vals (mutation-vals csrf-token {:domain (:domain e) :worker (:worker e)})}
            (if (:enabled e) "⏸️" "▶️")]
           [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-btn-square.pf-text-error
            {:title (:delete (:tooltip i18n/strings))
             :hx-post "/caddy/remove"
             :hx-target "#caddy-tbody"
             :hx-swap "innerHTML"
             :hx-confirm (:delete-caddy (:confirm i18n/strings))
             :hx-vals (mutation-vals csrf-token {:domain (:domain e) :worker (:worker e)})}
            (:delete (:button i18n/strings))]]])))))

(defn- resource-status-badge
  "docker/smolvm 卡片头共享的三态彽章（running/stopped/其他），两处逻辑完全一致，只抽取一次避免重复。"
  [status sts]
  (cond
    (= status "running") [:span.pf-badge.pf-badge-success (:running sts)]
    (= status "stopped") [:span.pf-badge.pf-badge-error (:stopped sts)]
    :else [:span.pf-badge.pf-badge-error (:abnormal sts)]))

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
              :data-log-container (:id c)}
             "📄"]
            (when (= (:status c) "stopped")
              [:button.pf-btn.pf-btn-ghost.pf-btn-xs.pf-text-success
               {:title (:start-container tip)
                :hx-post "/docker/start"
                :hx-target "#docker-tbody"
                :hx-swap "innerHTML"
                :hx-confirm (str "确定启动容器 " (:name c) " 吗？")
                :hx-vals (mutation-vals csrf-token {:id (:id c)})}
               "▶️"])
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
                                                (when (:gpu c) [:span.pf-text-success " 🖥️ GPU"])]]
           (when (:shellUrl c)
             [:span [:span.pf-text-muted "Shell: "] [:a.pf-link {:href (:shellUrl c) :target "_blank" :rel "noopener noreferrer"} "🔗 " [:code (:shellToken c)]]])
           (when (get-in c [:s3Mount :enabled])
             (let [sm (:s3Mount c)]
               [:span [:span.pf-text-muted "S3: "]
                (cond
                  (= (:status sm) "mounted") [:span.pf-text-success {:title (str (:endpoint sm "AWS S3") " / " (:bucket sm) " → " (:mountPoint sm))} (:mounted sts)]
                  (= (:status sm) "error") [:span.pf-text-error {:title (str (:endpoint sm "AWS S3") " / " (:bucket sm))} (:mount-error sts)]
                  :else [:span.pf-text-warning (str "🟡 " (:status sm))])]))]

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
             [:span "👤"]
             [:code.pf-mono {:style "font-size:0.75rem"} (:shellUser c)]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs {:title (:copy-username tip) :data-copy (:shellUser c)} (:copy btn)]]
            [:div.pf-row
             [:span "🔑"]
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
                        (if (:enabled pi) "⏸️" "▶️")]
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
               "▶️"])
            (when (= (:status m) "running")
              [:button.pf-btn.pf-btn-ghost.pf-btn-xs
               {:title (:stop-machine btn)
                :hx-post "/smolvm/stop"
                :hx-target "#smolvm-tbody"
                :hx-swap "innerHTML"
                :hx-confirm ((:stop-machine cfm) (:name m))
                :hx-vals (mutation-vals csrf-token {:id (:id m)})}
               "⏹️"])
            [:button.pf-btn.pf-btn-ghost.pf-btn-xs
             {:title (:pack-machine btn)
              :hx-post "/smolvm/pack"
              :hx-target "#smolvm-tbody"
              :hx-swap "innerHTML"
              :hx-vals (mutation-vals csrf-token {:id (:id m)})}
             "📦"]
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
                                                (when (:gpu m) [:span.pf-text-success " 🖥️ GPU"])]]
           (when (:shellUrl m)
             [:span [:span.pf-text-muted "Shell: "] [:a.pf-link {:href (:shellUrl m) :target "_blank" :rel "noopener noreferrer"} "🔗 " [:code (:shellToken m)]]])]

          ;; -- Two-column grid: Credentials | Ports --
          [:div.pf-resource-card-grid
           ;; Credentials column
           [:div.credential-cell
            [:div.pf-row
             [:span "👤"]
             [:code.pf-mono {:style "font-size:0.75rem"} (:shellUser m)]
             [:button.pf-btn.pf-btn-ghost.pf-btn-xs {:title (:copy-username tip) :data-copy (:shellUser m)} (:copy btn)]]
            [:div.pf-row
             [:span "🔑"]
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
                        (if (:enabled pi) "⏸️" "▶️")]
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

(def all-atoms [forward/state caddy/state docker/state smolvm/state])

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
                                      (render-overview-stats-content (:username ctx))]))
                     :atoms #{forward/state caddy/state docker/state smolvm/state}}})

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
  "Adds hx-swap-oob=\"true\" to the top-level hiccup element."
  [hiccup-el]
  (let [tag (first hiccup-el)
        second-el (second hiccup-el)]
    (if (map? second-el)
      (vec (cons tag (cons (assoc second-el :hx-swap-oob "true") (nnext hiccup-el))))
      (vec (list tag {:hx-swap-oob "true"} second-el)))))

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
          [:span.pf-topbar-logo "🔀"] [:span (:title (:app i18n/strings))]]
         [:div.pf-topbar-meta
          [:span.pf-ws-indicator {:id "ws-indicator" :title "WebSocket"}]
          [:div.pf-quota-chips {:id "quota-bar" :hx-get "/panel/quota" :hx-trigger "refresh-quota from:body, every 120s" :hx-swap "innerHTML"}
           (render-quota-bar-content username user)]
          [:div.pf-user-chip
           [:span.pf-user-name (str "👤 " username)]
           (if is-admin?
             [:span.pf-badge.pf-badge-error (:user-badge-admin dash)]
             [:span.pf-badge.pf-badge-neutral (:user-badge-user dash)])]]]

        ;; IP Mode Banner
        (when (:ip-mode config)
          [:div.pf-alert.pf-alert--info.pf-banner {:role "alert"}
           [:svg.pf-alert-icon {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
            [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"}]]
           [:span [:strong (:ip-mode-title dash)] (:ip-mode-desc dash) " " [:code (:public-ip config)] " IP:PORT 直连"]])

        [:nav.pf-tabs {:role "tablist" :aria-label "主导航" :id "app-tabs"}
         (ui.tabs/tab-btn "overview" "🏠" (:overview t) true)
         (ui.tabs/tab-btn "forwards" "🔀" (:forwards t) false)
         (when show-caddy? (ui.tabs/tab-btn "caddy" "🌐" (:caddy t) false))
         (ui.tabs/tab-btn "docker" "🐳" (:docker t) false)
         (when show-smolvm? (ui.tabs/tab-btn "smolvm" "🖥️" (:smolvm t) false))
         (when is-admin? (ui.tabs/tab-btn "admin" "🛡️" (:admin t) false))
         [:span.pf-tabs-indicator {:id "tabs-indicator" :aria-hidden "true"}]]

        [:main.pf-content {:id "app-tab-content"}

         [:section.pf-panel {:id "tab-overview" :data-tab-panel "overview" :role "tabpanel" :aria-labelledby "tabbtn-overview"}
          [:div.pf-panel-header
           [:div
            [:h2.pf-panel-title ((:welcome t) username)]
            [:p.pf-panel-desc (:overview-desc t)]]]
          [:div {:id "overview-stats" :hx-get "/panel/overview" :hx-trigger "refresh-overview from:body, every 120s"}
           (render-overview-stats-content username)]]

         [:section.pf-panel {:id "tab-admin" :data-tab-panel "admin" :role "tabpanel" :aria-labelledby "tabbtn-admin" :hidden true}
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
            [:p.pf-panel-desc (:forwards-desc t)]]
           [:div.pf-panel-actions
            [:button.pf-btn.pf-btn-primary {:type "button" :id "btn-open-forward-drawer" :data-action "drawer.open" :data-drawer "forward-drawer"} "+ " (:add-forward btn)]]]

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

          ;; Forwards Table
          [:div.pf-table-wrap
           [:table.pf-table.pf-table--zebra
            [:thead
             [:tr
              (when is-admin? [:th (:user-col tbl)])
              [:th (:source-port tbl)]
              [:th (:protocol tbl)]
              [:th (:target-ip tbl)]
              [:th (:target-port tbl)]
              [:th (:method tbl)]
              [:th (:remark tbl)]
              [:th (:status tbl)]
              [:th (:actions tbl)]]]
            [:tbody {:id "forward-tbody" :hx-get "/panel/forwards" :hx-trigger "refresh-forward from:body, every 120s" :hx-swap "innerHTML" :data-loaded "false"}
             (ui.skeleton/table-rows (if is-admin? 9 8) 3)]]]
          [:p.pf-footer-note
           [:b (:footer-socat dash)] "/" [:b (:footer-iptables dash)]
           (ui.popover/popover
            {:id "forward-help"
             :content (list
                       [:p [:b (:footer-socat dash)] (:footer-socat-desc dash)]
                       [:p [:b (:footer-iptables dash)] (:footer-iptables-desc dash)]
                       [:p (:footer-range-help dash) [:code "8080-8085"] (:footer-range-help-desc dash) [:code "80"] (:footer-range-help-mapping dash)])})]]

         [:section.pf-panel {:id "tab-caddy" :data-tab-panel "caddy" :role "tabpanel" :aria-labelledby "tabbtn-caddy" :hidden true}
          [:div.pf-panel-header
           [:div [:h2.pf-panel-title (:caddy t)] [:p.pf-panel-desc (:caddy-desc t)]]
           (when-not (:ip-mode config)
             [:div.pf-panel-actions
              [:button.pf-btn.pf-btn-primary {:type "button" :id "btn-open-caddy-drawer" :data-action "drawer.open" :data-drawer "caddy-drawer"} "+ " (:add-proxy btn)]])]

          (when-not (:ip-mode config)
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
                     [:button.pf-btn.pf-btn-primary.pf-btn-block {:type "submit" :id "btn-add-caddy"} (:add-proxy btn)]]}))

          ;; Caddy reverse proxy Card (hidden in IP mode)
          (when-not (:ip-mode config)
            [:div.pf-card
             [:div.pf-card-body
              [:div.pf-card-title (:caddy-title dash)]

              (when-not (caddy/available?)
                [:div.pf-alert {:role "alert"}
                 [:svg.pf-alert-icon {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                  [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"}]]
                 [:span [:strong (:caddy-unavailable dash)] (:caddy-install-hint dash)]])

            ;; Caddy Table
              [:div.pf-table-wrap
               [:table.pf-table.pf-table--zebra
                [:thead
                 [:tr
                  (when is-admin? [:th (:user-col tbl)])
                  [:th (:domain tbl)]
                  [:th (:worker tbl)]
                  [:th (:full-domain tbl)]
                  [:th (:target-addr tbl)]
                  [:th (:remark tbl)]
                  [:th (:status tbl)]
                  [:th (:actions tbl)]]]
                [:tbody {:id "caddy-tbody" :hx-get "/panel/caddy" :hx-trigger "refresh-caddy from:body, every 120s" :hx-swap "innerHTML" :data-loaded "false"}
                 (ui.skeleton/table-rows (if is-admin? 8 7) 3)]]]
              [:p.pf-footer-note
               [:b (:footer-caddy dash)]
               (ui.popover/popover {:id "caddy-help" :content [:p [:b (:footer-caddy dash)] (:footer-caddy-desc dash)]})]]])]

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
                      [:summary.pf-text-muted "⚙️ 高级选项"]
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
        [:div {:id "password-modal-container"}]]

        ;; Log viewer opens as popup window — no modal needed

       [:script {:src (core/asset-url "main.js")}]
       [:script {:src (core/asset-url "tabs.js")}]]])))
