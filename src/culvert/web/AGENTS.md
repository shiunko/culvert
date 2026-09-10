# Web 子树知识库（src/culvert/web/）

传输与呈现边界：HTTP 路由、HTMX 局部刷新、WebSocket 推送和静态资源都在此收口。
本目录之外的 `culvert/web.clj` 仅为兼容门面（re-export 旧公共 API）；新代码直接依赖 `web.core`、`web.handlers`、`web.views`、`web.server`、`web.ws`。

## 分层与请求流

- `server.clj`：`start-server!`/`stop-server!`。中间件自外向内为 `request-context/wrap-request-context` → `core/wrap-security-headers` → `core/wrap-rate-limit` → 原始 handler。
- `/ws` 特例：URI 为 `/ws` 且带 `upgrade: websocket` 头时，在 raw-handler 内分流，用 query 的 `ticket` 调 `core/ws-ticket-consume`（一次性消费），成功后再用 `auth/get-user` 重新取角色/配额，通过则进 `ws/ws-handler`，不经过 `route-dispatcher` 与 `wrap-auth`；失败返回 401。`ticket` 由 `POST /ws/ticket`（需 `wrap-auth` + CSRF）发放，30 秒有效，不再依赖预写在页面里的长期 Bearer token。
- `core.clj`：CSRF（atom 存储，绑定 session-id/username/action，TTL 1 小时）、WebSocket 票据（`ws-ticket-store`，30 秒 TTL，与 CSRF 同模式）、IP 滑窗限流（60 秒 120 次，超限封 5 分钟，信任代理取 x-forwarded-for）、安全头与 CSP、query/form 解析、`html-response`/`json-response`/`htmx-response`、错误协议（`error-info`/`htmx-error-response`/`json-error-response`，见下面“错误协议”节）、静态文件与 `asset-url` 指纹、`wrap-auth`（Bearer token 优先，Basic 兜底，绑定 `*request-security-context*`）。
- `ui/drawer.clj`、`ui/tabs.clj`：无领域依赖的纯 UI 结构生成函数（Drawer 壳、Tab 按钮），只接受已取好值的参数，不读 `config`/`i18n`/领域状态。
- `handlers.clj`（约 1080 行）与 `views.clj`（约 1115 行）：本仓库两大复杂度热点，均超 1000 行，修改优先缩小职责，不继续堆分支。`ui/drawer.clj`/`ui/tabs.clj` 已抽走 Drawer 壳与 Tab 按钮的纯结构生成，但两个文件本身仍很小，未真正降低 `views.clj` 体积。
- `ws.clj`：连接注册表 `channels`、`broadcast!`、`broadcast-stats!` 与 5 秒一轮的 stats 推送守护线程；客户端发来的消息当前忽略。

## 路由与授权

- `handlers/route-dispatcher` 以 cond 逐条匹配 request-method + URI；业务路由一律包 `core/wrap-auth`，`/admin/*` 与 `/panel/users` 再包 `admin-only`（role=admin）；`/login`、`/logout`、`/health*` 免认证。
- mutation 统一节奏：`parse-form-body` → `csrf-validate`（`_csrf` 字段或 `X-CSRF-Token` 头）→ 调领域入口（端口暴露与 ttyd 日志经 `request-actor` 构造 `authorization/actor`）→ `ws/broadcast!` → `views/build-oob-response`。
- Handler 不是唯一授权边界，领域授权（`authorization.clj` 及各 manager）是最终校验点。
- 视图当前直接读领域 atom 与只读计数函数（如 `compute-usage`），这是存量交织而非完成态；方向是视图接收数据、只做渲染，不得在视图执行命令或发起 mutation。

## 面板刷新契约

面板容器同时具备 WS 触发与 120 秒轮询兜底：`hx-get /panel/*` 加 `hx-trigger "refresh-xxx from:body, every 120s"`。
`public/main.js` 的 `panelRefreshMap` 是 DOM ID 到触发事件的唯一映射，两侧必须同步改：

| DOM ID | 触发事件 | 轮询端点 |
|---|---|---|
| `forward-tbody` | `refresh-forward` | `/panel/forwards` |
| `caddy-tbody` | `refresh-caddy` | `/panel/caddy` |
| `docker-tbody` | `refresh-docker` | `/panel/docker` |
| `smolvm-tbody` | `refresh-smolvm` | `/panel/smolvm` |
| `quota-bar` | `refresh-quota` | `/panel/quota` |
| `admin-users-tbody` | `refresh-admin` | `/panel/users` |
| `admin-containers-tbody` | `refresh-admin-containers` | `/admin/containers` |
| `overview-stats` | `refresh-overview` | `/panel/overview` |

`smolvm-tbody` 已登记进 `panelRefreshMap`，handler 广播的 smolvm refresh 事件可以触发实时刷新，仍保留 120 秒轮询作为兜底。`overview-stats` 目前只注册了 `fragment-registry`（参与 mutation 同一响应内的 OOB）和 120 秒轮询，没有任何 `ws/broadcast!` 调用把它列入 `panels`，因此其他已连接客户端不会实时收到总览统计刷新，只能靠轮询或下次刷新页面。

## WebSocket 载荷

- 连接地址 `/ws?ticket=`，ticket 先经 `POST /ws/ticket` 换取（30 秒有效、一次性消费）；断线指数退避重连（2 秒起，上限 30 秒），重连时重新换票。
- `{"event":"refresh","panels":["forward-tbody","quota-bar",...]}`：panels 取值须与上表 DOM ID 一致。
- `{"event":"stats","stats":{<容器id> {:cpu-pct :mem-pct :mem-used :mem-limit}}}`：写入 `[data-container-id]` 卡片内 `.resource-usage-cell` 的 `.resource-bar-cpu`/`.resource-bar-mem` 与相邻 `.resource-bar-text`；docker 与 smolvm 合并推送；HTMX 换出 `docker-tbody` 后 main.js 再 fetch `/docker/stats` 兜底一次。

## Tab 导航与页面结构

- `render-dashboard` 不再输出单页长滚动内容，而是 Topbar + `<nav id="app-tabs" role="tablist">` + `<main id="app-tab-content">` 下的 6 个 `<section data-tab-panel="...">`（`overview`/`forwards`/`caddy`/`docker`/`smolvm`/`admin`）；除 `overview` 外均带 `hidden` 属性。
- **默认 Tab（`overview`）首屏真实渲染，其余五个 Tab 里的 6 个列表/卡片容器实现了首次惰加载**（`forward-tbody`/`caddy-tbody`/`docker-tbody`/`smolvm-tbody`/`admin-users-tbody`/`admin-containers-tbody`）：这些容器首屏只输出 `src/culvert/web/ui/skeleton.clj` 的 `table-rows`/`resource-cards` 骨架占位，并带 `data-loaded="false"`；`hx-get`/`hx-trigger`（120 秒轮询 + WS 自定义事件）保持不变。`public/tabs.js` 的 `activateTab` 在面板首次变为可见时，对其内 `[data-loaded="false"]` 容器调 `htmx.ajax` 拉取真实内容，成功后打上 `data-loaded="true"`；`public/main.js` 的 WS 刷新处理对 `data-loaded="false"` 的容器跳过 `htmx.trigger`（避免对从未访问过的 Tab 做后台拉取），用户实际切换过去时会直接拉取最新数据，不需要额外的“待刷新”标记。**阶段性限制**：120 秒轮询仍会对未访问过的 Tab 在后台定时轮询（与现有行为一致，未变差），本轮只处理了首屏的延迟加载，未完全消除后续后台轮询。
- Tab 按钮由 `src/culvert/web/ui/tabs.clj` 的 `tab-btn`（无领域依赖）生成，`id="tabbtn-X"` 与对应 `<section id="tab-X">` 通过 `aria-controls`/`aria-labelledby` 关联；`caddy`/`smolvm`/`admin` 标题按现有能力探测（`(not (:ip-mode config))`、`(:smolvm-available? config)`、`is-admin?`）条件渲染，与对应 `<section>` 内部的现有 `when`/`when-not` 守卫保持一致，修改其中一处时必须同步另一处。
- 端口转发/反向代理/容器/虚拟机 四个 Tab 的创建表单均已改为右侧滑入 Drawer（`#forward-drawer`/`#caddy-drawer`/`#docker-drawer`/`#smolvm-drawer`），壳结构由 `src/culvert/web/ui/drawer.clj` 的 `drawer`（无领域依赖，只接受 `{:id :title :body}`）生成，四处调用方只传入自己的表单 `body`，不重复写壳。交互由 `data-action="drawer.open"`/`"drawer.close"` 和对应 `data-drawer="..."` 驱动，逻辑在 `public/tabs.js`；表单提交成功后通过 `data-close-drawer-on-success` 属性自动关闭。Docker 的 S3 高级选项仍用 `<details>` 嵌在 Drawer 内，`hx-include "closest details"` 行为未变；SmolVM 高级选项同理。四个表单的字段名、`hx-*`、CSRF 与后端完全一致，仅外层包装结构和样式改变。
- `render-docker-tbody`/`render-smolvm-tbody` 共享 `views.clj` 里的私有辅助函数 `resource-status-badge`（running/stopped/其他 三态彽章），因两处逻辑完全一致才抽取；端口转发/Caddy 的状态判断因分支数与字段不同，故意保留为各自独立的 `cond`，不强行归并。
- `docker-tbody`/`smolvm-tbody` 容器 div 带 `.pf-resource-grid`（CSS Grid 自适应列数）；`render-docker-tbody`/`render-smolvm-tbody` 本身只返回裸 `.pf-resource-card` 序列，不自带网格包装，因此网格类必须打在持久容器上（hx-swap="innerHTML" 不会把它换掉），不能改打在 render 函数返回值里。
- `src/culvert/web/ui/` 只放无领域依赖的纯 UI 结构生成函数（不读取 `config`/`i18n`/领域 状态）；调用方（`views.clj`）负责传入已取好值的参数。新增组件若真实存在两处以上完全一致的结构才掘到这里，不要为了抽取而抽取。
- `#tab-admin` 内嵌一个作用域限定的二级 Segmented Control（`#admin-subtabs`，两项：用户与配额 / 主机容器分配），与一级 `#app-tabs` 完全独立不共用 `activateTab`（不写 URL，避免与一级 `?tab=` 冲突）：`data-subtab`/`data-tab-panel-sub` 属性与一级的 `data-tab`/`data-tab-panel` 完全分开命名。`tabs.js` 新增一段独立的 `activateSubtab`。若后续需要第三个作用域限定导航，优先考虑抽一个共用函数，当前仅 2 处不强行统一。
- `ui/popover.clj` 的 `popover` 生成“？”触发按钮 + 默认隐藏的说明块，`tabs.js` 的 `data-action="popover.toggle"` 驱动开关（点击外部/Escape 均会关闭）。目前只用于端口转发 Tab 的 socat/iptables 说明和 Caddy Tab 的 `footer-caddy-desc`；SmolVM 的 `smolvm-port-change-warning`（警告性质，不应隐藏）和 `smolvm-mode-desc`（Drawer 表单内的字段提示，非页面级附注）故意未转换，不要假设所有 dash 文案都已改成 popover。
- `总览` Tab 由 `render-overview-stats-content` 驱动，内容是可点击的资源数量卡片（`data-action="tabs.activate" data-tab="..."`），不包含任何 mutation，不要在这里添加创建/删除入口。
- `public/app.css` 是构建产物，由 `mise run css`（Tailwind CLI v4 独立二进制，经 mise 以 `github:tailwindlabs/tailwindcss` 工具引入，不涉及 npm/node_modules）把源文件 `styles/app.css` 编译而成，不要直接手改 `public/app.css`——新增样式改 `styles/app.css` 后重新编译。`daisyui.css`/`tailwindcss.js`（浏览器运行时 Tailwind JIT）已随 `docs/12` §14.1/§14.1-b 完全移除，不要再引用。当前 `styles/app.css` 只 import theme+utilities 两层（不含 preflight），且未配置任何 `@source`，内容仍是全部手写的 `pf-` 组件类，详见 `docs/12` §14.2。

## 令牌与前端耦合点

- `render-dashboard` 只注入 `meta[name=csrf-token]`，不再预写任何长期 token；body 的 `hx-headers` 与 main.js 的 `htmx:configRequest` 双路携带 `X-CSRF-Token`。main.js 的 `connectWebSocket` 先 `POST /ws/ticket` 换票再开 WebSocket，重连时重新换票。
- admin 改密是一次性 CSRF：表单按 `:action :admin/change-password` 生成，校验时 consume，成功后经 `HX-Trigger closePasswordModal` 关闭弹窗并整页 reload。
- 密码弹窗：`hx-get /admin/password-form` 目标 `#password-modal-container`，`htmx:afterSwap` 后对 `#passwordModal` 调 `showModal()`。
- 凭据显隐与复制依赖卡片上的 `data-resource-kind`、`data-resource-id`、`data-credential-endpoint`，以及 `data-copy`、`data-copy-pass`、`.cred-pass-input`；明文只通过带 CSRF 的 POST `/docker/credentials` 或 `/smolvm/credentials` 按需返回，不进入状态 GET、URL 或普通 DTO。
- 日志弹窗：`data-log-container` 触发 POST `/docker/logs/start` 返回 `{url}` 开 ttyd 弹窗；轮询窗口关闭后 POST `/docker/logs/stop`，页面卸载用 `sendBeacon` 兜底。
- 行内备注编辑：GET `/forward/remark-form`、`/forward/remark-cancel`、`/caddy/remark-form`、`/caddy/remark-cancel`，目标 `closest .remark-cell`。
- admin 容器过滤：`.filter-btn[data-filter]` 加行上 `data-state`，HTMX 重换 tbody 后由 main.js 重放当前过滤。

## OOB 与 fragment-registry

`views/all-atoms` 跟踪 forward/caddy/docker/smolvm 四个领域 atom；handler 变更前 `snapshot-atoms`，`build-oob-response` 对比快照，把发生变化的 fragment 以 `hx-swap-oob="true"` 追加在主响应后，并经 `HX-Trigger show-toast` 弹提示。
新增可刷新面板必须同时在 `fragment-registry` 登记 render 函数与依赖 atom，并接入上表映射，否则 OOB 与 WS 刷新都会漏掉它。

## 静态资源

路由表固定三条：GET `/main.js`、`/app.css`、`/tabs.js`，经 `core/serve-static-file` 从 classpath `public/` 或开发目录读取；页面引用统一走 `asset-url` 加 `?v=HASH` 指纹。
`build.clj` 的 uber 任务把 `public/` 复制到 `target/classes/public`；新增静态资源要同步路由条目与页面引用，CSP 脚本源仅放行 self 与 cdn.jsdelivr.net（htmx 从 CDN 加载，htmx-ext-ws/sse 未被使用已删除）。
`public/app.css` 是上述三条中唯一的构建产物，源文件是仓库根目录下的 `styles/app.css`，由 `mise run css`（Tailwind CLI）编译生成，`mise run css-check` 会在 `mise run test` 中验证两者一致。

## 错误协议

- `handlers.clj` 里的 `(catch Exception e ...)` 不得直接将 `(.getMessage e)` 传入响应，必须经 `core/htmx-error-response`（OOB 外的 HTMX 分支）、`views/build-oob-error-response`（OOB 分支）或 `core/json-error-response`（JSON 接口分支）之一，三者均基于 `core/error-info` 推导安全消息与正确的 HTTP 状态码（400），并自动记一条服务端日志。
- `error-info` 不区分 `ex-info` 与普通 `Exception`，二者在领域层（`auth`/`caddy`/`docker`/`forward`/`smolvm.manager`）都是主动抛出的安全中文提示，直接透传；新领域异常只需把消息写成用户可见的中文提示，不需要匹配异常类型。
- 新增 mutation handler 时直接复用三个错误分支函数，不要回到旧式固定 200 + toast 文本的写法。

## 修改守则

- 一个交互通常牵动四处：`route-dispatcher`、视图 `hx-*` 属性、`fragment-registry` 与 `panelRefreshMap`、必要时 main.js 委托逻辑；漏改一处表现为静默失效，不是报错。
- 呈现层不新增业务规则、外部命令调用或跨领域编排；授权与所有权校验留在领域入口。
- 改协议字段（panels 取值、stats 键名、meta 名称）等于同时改 main.js，先核对两侧再动手。
