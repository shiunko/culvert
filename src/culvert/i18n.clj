(ns culvert.i18n
  "Centralized UI string management (Chinese locale).
   All human-facing strings are defined here so that views, handlers, and
   business logic can reference them instead of embedding raw text.

   Structure: nested maps organized by domain (app, dashboard, form, table,
   status, toast, confirm, validation, log, js).

   Dynamic strings that need interpolation are defined as fn entries
   (e.g., (fn [name] (str \"用户 \" name \" 已创建\"))).")

;; ============================================================
;; Complete String Catalog
;; ============================================================

(def strings
  {:app
   {:title "Culvert 控制面"
    :html-lang "zh-CN"}

   ;; -------------------------------------------------------- ;; :dashboard — Page sections & banners ;; --------------------------------------------------------
   :dashboard
   {:ip-mode-title "📡 IP 直连模式"
    :ip-mode-desc "— Caddy 反向代理已禁用，所有访问通过 IP:PORT 直连"
    :user-badge-admin "管理员"
    :user-badge-user "用户"
    :admin-badge "admin"
    :created-at-hyphen "—"

    :admin-panel "🛡️ 管理员面板"
    :admin-container-panel "🖥️ 主机容器分配"
    :admin-container-desc "查看主机上所有容器。分配给用户后，用户可在「容器管理」面板中操作。"
    :admin-container-summary #(str "共 " (:total %) " 个容器 · " (:assigned %) " 个已分配 · " (:unassigned %) " 个待分配")
    :port-forward "🔀 端口转发管理器"
    :caddy-title "🌐 Caddy 反向代理"
    :caddy-unavailable "⚠️ Caddy 未安装或不可用。"
    :caddy-install-hint "请安装 Caddy 后重启服务"
    :docker-title "🐳 容器管理 "
    :docker-unavailable #(str "⚠️ 容器运行时 " (:container-cli %) " 不可用。")
    :docker-install-hint "请安装并运行容器服务。"

    :footer-socat "socat"
    :footer-iptables "iptables"
    :footer-caddy "Caddy"
    :footer-socat-desc " = 用户态代理（隐藏客户端 IP） · "
    :footer-iptables-desc " = 内核级 DNAT（保留客户端 IP，需要作为网关部署）"
    :footer-caddy-desc " = HTTP/HTTPS 反向代理 · 基于子域名路由到不同后端服务"
    :footer-range-help "端口范围格式: 来源端口输入 "
    :footer-range-help-desc "，目标端口输入起始端口 "
    :footer-range-help-mapping "，将自动映射 8080→80, 8081→81, ..."

    :container-rm-mode "容器以 "
    :container-rm-mode-desc " 模式运行，删除即销毁。通过 "
    :container-rm-mode-desc2 " 保持运行，使用 ttyd 远程终端连接。"
    :container-logs-title "📄 容器日志"
    :container-logs-refresh "🔄 刷新"
    :container-logs-close "✖ 关闭"
    :container-logs-empty "暂无日志输出"
    :container-logs-error "获取日志失败"
    :container-unassigned "未分配"
    :s3-mount-label "⚙️ 挂载 S3 存储"
    :s3-mount-desc "☁️ S3 数据挂载 — 容器创建后将 S3 兼容存储挂载到 "
    :s3-endpoint-default "留空使用 AWS S3 默认"
    :s3-region-optional "可选"
    :s3-test-btn "🔍 测试"

    :smolvm-title "🖥️ MicroVM 管理"
    :smolvm-unavailable "⚠️ smolvm 未安装或不可用。"
    :smolvm-install-hint "请安装 smolvm 后重启服务。"
    :smolvm-mode-desc "VM 使用 machine create（持久化），通过 start/stop 管理，ttyd 远程终端连接。"
    :smolvm-port-change-warning "⚠️ 端口变更需要短暂停机（stop → update → start）"}

   ;; -------------------------------------------------------- ;; :form — Labels, placeholders ;; --------------------------------------------------------
   :form
   {:label-username "用户名"
    :placeholder-new-user "新用户名"
    :label-password "密码"
    :placeholder-password "至少6位"
    :label-role "角色"
    :option-user "用户"
    :option-admin "管理员"
    :label-target-ip "目标 IP"
    :placeholder-ip "例: 10.0.0.1"
    :label-source-port "来源端口"
    :placeholder-src-port "例: 8080 或 8080-8085"
    :label-target-port "目标端口"
    :placeholder-target-port "起始端口"
    :label-protocol "协议"
    :label-method "转发方式"
    :label-remark "备注"
    :placeholder-remark "可选备注"
    :label-domain "域名"
    :label-worker "Worker 名称"
    :placeholder-worker "例: worker"
    :label-target-addr "目标地址"
    :placeholder-target-addr "例: http://192.168.99.2:8080"

    :label-image "镜像"
    :placeholder-image "-- 选择镜像 --"
    :label-cpu-cores "CPU 核数"
    :label-mem-limit "内存限制"
    :placeholder-mem "如 512m, 2g"
    :label-gpu "GPU"
    :label-gpu-enable "启用 GPU (--gpus all)"

    :label-s3-endpoint "Endpoint"
    :placeholder-s3-endpoint "https://minio:9000"
    :label-s3-bucket "Bucket"
    :placeholder-s3-bucket "my-bucket"
    :label-s3-access-key "Access Key"
    :placeholder-s3-access-key "AKIA..."
    :label-s3-secret-key "Secret Key"
    :placeholder-s3-secret-key "secret"
    :label-s3-region "Region"
    :placeholder-s3-region "us-east-1"

    :label-new-password "新密码"
    :label-cancel "取消"
    :label-confirm-change "确认修改"

    :label-smolvm-image "镜像"
    :placeholder-smolvm-image "-- 选择 VM 镜像 --"
    :label-smolvm-cpu "vCPU 数"
    :label-smolvm-mem "内存 (MiB)"
    :label-net-enabled "启用网络 (--net)"
    :label-ssh-agent "SSH Agent 转发"
    :label-volumes "卷挂载"
    :label-allow-hosts "允许主机 (每行一个)"
    :label-allow-cidrs "允许 CIDR (每行一个)"
    :label-vm-host-path "宿主机路径"
    :label-vm-guest-path "VM 路径"
    :label-vm-readonly "只读"}

   ;; -------------------------------------------------------- ;; :button — Action button texts ;; --------------------------------------------------------
   :button
   {:create-user "➕ 创建用户"
    :add-forward "➕ 添加转发"
    :add-proxy "➕ 添加代理"
    :create-container "➕ 创建容器"
    :add-port "➕"
    :edit-remark "✏️"
    :toggle-enable "▶️"
    :toggle-disable "⏸️"
    :delete "🗑️"
    :change-password "🔑"
    :save-quota "💾"
    :copy "📋"
    :show-password "👁"
    :hide-password "👁️‍🗨️"
    :assign "📌 分配"
    :reassign "🔄 重分配"
    :start-container "▶️ 启动"
    :confirm-assign "确认"
    :create-machine "➕ 创建 VM"
    :pack-machine "📦 打包"
    :start-machine "▶️ 启动"
    :stop-machine "⏹️ 停止"
    :add-volume "➕ 添加挂载"
    :remove-volume "✖"}

   ;; -------------------------------------------------------- ;; :table — Table column headers ;; --------------------------------------------------------
   :table
   {:username "用户名"
    :role "角色"
    :ports "端口"
    :proxies "代理"
    :containers "容器"
    :smolvm "VM"
    :cpu "CPU"
    :memory "内存"
    :created "创建"
    :actions "操作"
    :source-port "来源端口"
    :protocol "协议"
    :target-ip "目标 IP"
    :target-port "目标端口"
    :method "方式"
    :remark "备注"
    :status "状态"
    :domain "域名"
    :worker "Worker"
    :full-domain "完整域名"
    :target-addr "目标地址"
    :index "#"
    :container-name "容器名称"
    :image "镜像"
    :resources "资源"
    :resource-usage "使用率"
    :web-shell "Web Shell"
    :credentials "凭据"
    :port-proxy "端口代理"
    :logs "📄 日志"
    :s3 "📦 S3"
    :user-col "用户"
    :assigned-user "分配用户"
    :host-status "主机状态"
    :container-id "容器ID"
    :filter-all "全部"
    :filter-running "运行中"
    :filter-stopped "已停止"
    :machine-name "机器名称"
    :smolvm-ports "VM 端口"}

   ;; -------------------------------------------------------- ;; :status — Status display texts ;; --------------------------------------------------------
   :status
   {:running "🟢 运行中"
    :running-text "● 运行中"
    :stopped "🔴 已停止"
    :disabled "● 已停用"
    :disabled-text "（已停用）"
    :dead "● 异常"
    :abnormal "🟡 异常"
    :unknown "—"
    :mounted "🟢 已挂载"
    :mount-error "🔴 失败"
    :title-manual-stop "已手动停用"
    :title-process-running "进程运行中"
    :title-partial-dead "部分进程未运行"
    :title-cant-detect "无法检测状态 (iptables)"
    :title-caddy-managed "Caddy 管理中"
    :port-range-count #(str "(共 " % " 个端口)")}

   ;; -------------------------------------------------------- ;; :quota — Quota display labels ;; --------------------------------------------------------
   :quota
   {:port-forward "🔀 端口: "
    :reverse-proxy "🌐 代理: "
    :container "🐳 容器: "
    :smolvm "🖥️ VM: "
    :cpu "⚡ CPU: "
    :memory "💾 内存: "
    :total-cpu "CPU 总量"
    :total-mem "内存总量"}

   ;; -------------------------------------------------------- ;; :empty — Empty state messages ;; --------------------------------------------------------
   :empty
   {:no-users "暂无用户。"
    :no-forwards "暂无转发配置。"
    :no-caddy "暂无 Caddy 代理配置。"
    :no-containers "暂无容器。"
    :no-host-containers "主机上暂无容器。确保容器运行时正在运行。"
    :no-machines "暂无 MicroVM。"}

   ;; -------------------------------------------------------- ;; :toast — Toast notification messages ;; --------------------------------------------------------
   :toast
   {:forward-added "端口转发已添加"
    :forward-removed "端口转发已删除"
    :forward-toggled "状态已更新"
    :remark-updated "备注已更新"
    :caddy-added "反向代理已添加"
    :caddy-removed "反向代理已删除"
    :caddy-toggled "状态已更新"
    :container-created #(str "容器已创建: " %)
    :container-created-s3-warn #(str "容器已创建，但 S3 挂载警告: " %)
    :container-deleted "容器已删除"
    :port-exposed #(str "端口 " % " 已暴露")
    :port-unexposed #(str "端口 " % " 已取消暴露")
    :port-toggled "端口状态已更新"
    :user-created #(str "用户 \"" % "\" 已创建")
    :user-deleted #(str "用户 \"" % "\" 已删除")
    :password-changed #(str "用户 \"" % "\" 密码已修改")
    :quota-updated #(str "用户 \"" % "\" 配额已更新")

    :csrf-failed "CSRF validation failed"
    :forbidden "Forbidden"
    :not-found "Not Found"
    :rate-limited "Too Many Requests — 请稍后再试"
    :operation-failed "操作失败，请稍后重试"
    :container-started #(str "容器已启动: " %)
    :container-assigned #(str "容器 \"" (:name %) "\" 已分配给用户 \"" (:user %) "\"")

    :machine-created #(str "VM 已创建: " %)
    :machine-deleted "VM 已删除"
    :machine-started #(str "VM 已启动: " %)
    :machine-stopped #(str "VM 已停止: " %)
    :machine-port-exposed #(str "VM 端口 " % " 已暴露")
    :machine-port-unexposed #(str "VM 端口 " % " 已取消暴露")
    :machine-port-toggled "VM 端口状态已更新"
    :machine-packed #(str "VM 已打包: " %)}

   ;; -------------------------------------------------------- ;; :confirm — Confirmation dialogs ;; --------------------------------------------------------
   :confirm
   {:delete-forward "确定要删除该转发规则吗？"
    :delete-caddy "确定要删除该反代规则吗？"
    :delete-container "确定要删除并销毁该容器吗？此操作不可逆！"
    :delete-user #(str "确定删除用户 " % " 吗？此操作不可撤销。")
    :delete-port "确定取消该端口暴露吗？"
    :delete-machine "确定要删除该 VM 吗？所有数据将丢失！"
    :stop-machine #(str "确定停止 VM " % " 吗？")}

   ;; -------------------------------------------------------- ;; :tooltip — Tooltip / title attributes ;; --------------------------------------------------------
   :tooltip
   {:edit-remark "编辑备注"
    :enable "启用"
    :disable "停用"
    :delete "删除"
    :delete-container "删除容器"
    :view-logs "查看日志"
    :refresh-logs "刷新日志"
    :unexpose "取消暴露"
    :copy-username "复制用户名"
    :show-password "显示密码"
    :hide-password "隐藏密码"
    :copy-password "复制密码"
    :password-mask "●●●●●●●●●●●●"
    :start-container "启动容器"
    :assign-container "分配容器给用户"
    :reassign-container "重新分配容器给其他用户"
    :confirm-assign "确认分配给选中用户"}

   ;; -------------------------------------------------------- ;; :modal — Modal dialog texts ;; --------------------------------------------------------
   :modal
   {:change-password-title "🔑 修改用户密码"}

   ;; -------------------------------------------------------- ;; :s3 — S3 related display texts ;; --------------------------------------------------------
   :s3
   {:unavailable "S3 挂载功能不可用"
    :fill-credentials "请填写 Bucket、Access Key 和 Secret Key"
    :conn-success #(str "连接成功：s3://" (:bucket %) (if (seq (:endpoint %)) (str " @ " (:endpoint %)) ""))
    :conn-timeout #(str "连接超时 (" (:timeout %) "s)：无法访问 s3://" (:bucket %) "，请检查 endpoint、凭据和网络")
    :conn-failure #(str "连接失败: " %)}

   ;; -------------------------------------------------------- ;; :validation — Error messages thrown from business logic ;; --------------------------------------------------------
   :validation
   {:username-length "用户名长度必须在 2-32 字符之间"
    :username-format "用户名只能包含小写字母、数字、下划线和连字符"
    :user-exists #(str "用户 \"" % "\" 已存在")
    :user-not-found #(str "用户 \"" % "\" 不存在")
    :password-too-short "密码长度不能少于 6 位"
    :role-invalid "角色必须是 admin 或 user"
    :cannot-delete-self "不能删除自己"
    :cannot-delete-admin "不能删除唯一的管理员"
    :quota-must-be-int #(str (name %) " 配额必须为非负整数")
    :cpu-quota-must-be-num "CPU 配额必须为非负数"
    :mem-quota-not-empty "内存配额不能为空"

    :port-must-be-int #(str % " 必须是 1 到 65535 之间的整数")
    :port-range-format-invalid "端口范围格式无效，请使用如 8080-8085 的格式"
    :port-must-be-in-range "端口号必须在 1 到 65535 之间"
    :port-start-must-be-less "起始端口必须小于结束端口"
    :field-cannot-be-empty #(str % " 不能为空")
    :field-format-invalid #(str % " 格式无效，请使用单个端口(如8080)或端口范围(如8080-8085)")
    :no-permission-forward "无权操作此转发规则"

    :port-already-forwarded "该端口已被转发"
    :port-not-forwarded "该端口未被转发"
    :port-range-outside #(str "来源端口必须在平台允许范围内 (" (:start %) "-" (:end %) ")")

    :domain-empty "域名不能为空"
    :domain-not-allowed #(str "域名 \"" % "\" 不在允许列表中")
    :worker-empty "Worker 名称不能为空"
    :worker-format "Worker 名称只能包含小写字母、数字和连字符，且不能以连字符开头或结尾"
    :target-invalid "目标地址必须以 http:// 或 https:// 开头"
    :proxy-exists #(str "代理 \"" (:worker %) "." (:domain %) "\" 已存在且已启用，请先停用或删除")
    :proxy-not-found #(str "代理 \"" (:worker %) "." (:domain %) "\" 不存在")
    :no-permission-proxy "无权操作此代理规则"

    :image-not-allowed #(str "镜像 \"" (:image %) "\" 不在允许列表中。可用镜像: " (:allowed %))
    :cpu-exceeded #(str "CPU 不能超过 " % " 核")
    :mem-exceeded #(str "内存不能超过 " %)
    :container-quota-exhausted #(str "容器配额已用尽 (" (:used %) "/" (:limit %) ")，请联系管理员")
    :cpu-quota-exceeded #(str "CPU 总量超出用户配额 (已用 " (:used %) "C + 新增 " (:added %) "C > 配额 " (:limit %) "C)")
    :mem-quota-exceeded #(str "内存总量超出用户配额 (已用 " (:used %) " + 新增 " (:added %) " > 配额 " (:limit %) ")")
    :container-not-found "容器不存在"
    :no-permission-container "无权操作此容器"
    :port-range-invalid "端口号必须在 1-65535 范围内"
    :port-already-exposed #(str "端口 " % " 已暴露")
    :port-not-exposed #(str "端口 " % " 未暴露")
    :gpu-unavailable "GPU 不可用：未检测到 NVIDIA GPU (nvidia-smi 未找到或无 GPU)"
    :container-not-running "容器未运行，无法获取日志"
    :container-already-assigned "该容器已分配给其他用户"
    :user-not-selected "请选择分配目标用户"
    :container-already-running "容器已在运行中"

    :smolvm-image-not-allowed #(str "VM 镜像 \"" (:image %) "\" 不在允许列表中。可用镜像: " (:allowed %))
    :smolvm-cpu-exceeded #(str "VM CPU 不能超过 " % " 核")
    :smolvm-mem-exceeded #(str "VM 内存不能超过 " % " MiB")
    :machine-quota-exhausted #(str "VM 配额已用尽 (" (:used %) "/" (:limit %) ")，请联系管理员")
    :machine-not-found "VM 不存在"
    :no-permission-machine "无权操作此 VM"
    :machine-port-change-requires-stop "端口变更需要先停止 VM"
    :machine-already-running "VM 已在运行中"
    :machine-already-stopped "VM 已处于停止状态"}

   ;; -------------------------------------------------------- ;; :js — Strings referenced in JavaScript (main.js) ;; --------------------------------------------------------
   :js
   {:copied "已复制 ✓"
    :copy-failed "复制失败，请手动复制"
    :network-error "网络错误"
    :cannot-get-pwd "无法获取密码"
    :show-password "显示密码"
    :hide-password "隐藏密码"}

   ;; -------------------------------------------------------- ;; :tabs — Tab 导航标题、说明与总览统计卡文案 ;; --------------------------------------------------------
   :tabs
   {:overview "总览"
    :forwards "端口转发"
    :caddy "反向代理"
    :docker "容器"
    :smolvm "虚拟机"
    :admin "管理员"
    :overview-desc "当前账户的配额用量与各资源概览"
    :forwards-desc "将本机端口映射到内网服务"
    :caddy-desc "基于子域名路由到不同后端服务"
    :docker-desc "创建、管理容器与远程终端"
    :smolvm-desc "创建、管理轻量级微型虚拟机"
    :admin-desc "用户、配额与主机容器分配"
    :stat-forwards "端口转发"
    :stat-caddy "反向代理"
    :stat-docker "容器"
    :stat-smolvm "虚拟机"
    :welcome #(str "👋 欢迎回来，" %)}})
