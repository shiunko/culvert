# Web 前端页面完全重构计划

## 1. 文档目的

本文是 `src/culvert/web/` 及其静态资源的定制重构计划。目标不是把当前页面直接改成 React/Vue SPA，而是在保持 Babashka 与 JVM 共用业务代码、保持现有 HTTP 框架和逐步迁移能力的前提下，建立可测试、可演进、可安全运行的控制面前端。

本文中的“前端”包括：

- `src/culvert/web/` 下的 HTTP、视图和 WebSocket 代码；
- `public/main.js`、CSS 和第三方前端资源；
- 与 HTMX、CSRF、WebSocket、凭据、日志弹窗相关的 HTTP 协议；
- 页面和片段的自动化测试。

当前 Web 层的主要问题已经在 `docs/10-实施状态与生产切换清单.md` 中列为未完成项：查询层仍与领域状态交织，稳定 DTO 尚未覆盖全部页面和 WebSocket 路径，Application Service 和领域事件也尚未完全接线。

## 2. 目标与非目标

### 2.1 目标

1. 保留 SSR + HTMX + 原生 WebSocket 的总体技术路线。
2. 首屏只渲染页面外壳、用户上下文、能力摘要和轻量概览。
3. Forward、Caddy、Docker、SmolVM、Admin/User 按功能垂直切片独立迁移。
4. 视图只接收稳定 DTO，不读取领域 atom、不执行命令、不调用 mutation。
5. 通过领域事件驱动片段刷新，不再通过比较全部全局 atom 推断变化。
6. 统一认证、角色、CSRF、错误、toast、加载状态和操作反馈。
7. 凭据、日志和 WebSocket 会话满足最小暴露原则。
8. 页面在移动端、键盘操作和基础屏幕阅读器场景下可用。
9. 所有关键交互都有服务端契约测试和浏览器级回归测试。

### 2.2 非目标

- 不在本计划中切换 HTTP 服务器或引入第二套构建体系。
- 不把 Docker 和 SmolVM 强行合并为一个丢失领域语义的万能 runtime。
- 不长期维护 JSON API 和 HTML API 两套重复业务实现。
- 不在没有真实需求前引入全局客户端状态库。
- 不把页面重构与 SQLite 默认切换、所有外部 adapter 生产接线绑定在同一个大版本中。
- 不同时进行大规模视觉改版、前端框架迁移和业务领域迁移。

## 3. 目标架构

### 3.1 请求流

```text
请求
  → request-id / 安全头 / 限流 / 认证 / CSRF
  → 声明式路由与参数校验
  → 功能 Application Service 或 Query
  → 稳定 DTO / 领域事件
  → HTML fragment、JSON 或 WebSocket adapter
  → HTMX 或前端模块更新页面
```

### 3.2 目标目录

```text
src/culvert/web/
├── http.clj                 ; 请求解析、响应、错误和静态资源
├── middleware.clj           ; 认证、CSRF、限流、安全头、request-id
├── routes.clj               ; 路由声明和统一路由元数据
├── query.clj                ; Web 查询与 DTO
├── events.clj               ; 领域事件到 Web 事件的映射
├── ws.clj                   ; WebSocket 连接、授权和推送
├── pages/
│   ├── layout.clj
│   ├── overview.clj
│   └── admin.clj
├── components/
│   ├── form.clj
│   ├── table.clj
│   ├── status.clj
│   ├── modal.clj
│   └── toast.clj
└── features/
    ├── forwards.clj
    ├── caddy.clj
    ├── docker.clj
    ├── smolvm.clj
    └── users.clj

public/
├── app/
│   ├── boot.js
│   ├── http.js
│   ├── toast.js
│   ├── websocket.js
│   ├── panels.js
│   ├── credentials.js
│   ├── logs.js
│   ├── modal.js
│   └── filters.js
├── app.css
└── vendor/
    └── htmx.min.js
```

以上是职责边界，不要求一次性创建所有文件。每个功能切片只应抽取真实需要的模块，不能为了目录完整而建立空的万能抽象。

### 3.3 责任边界

| 层 | 允许做的事 | 禁止做的事 |
|---|---|---|
| Middleware | 认证、CSRF、限流、request-id、响应安全头 | 业务授权决策、读取资源状态 |
| Route | method/path/role/CSRF/参数解析 | 调用外部命令、拼接业务流程 |
| Query | 按 actor 读取一致快照、生成 DTO | 返回内部 atom、返回未脱敏秘密 |
| Application Service | 调用领域入口、处理 mutation、产生事件 | 生成 HTML、操作 DOM |
| View | 将 DTO 渲染为 Hiccup | 读取 atom、调用 CLI、执行 mutation |
| Frontend Module | DOM、HTMX、WS、加载和交互反馈 | 重新实现权限、配额和资源所有权 |
| Domain | 最终授权、状态变更、外部副作用 | 依赖 DOM 或 HTTP 细节 |

## 4. 资源和页面设计

### 4.1 页面信息架构

第一阶段保留 `/` 作为兼容入口，逐步将页面组织为：

```text
总览
├── 配额和能力摘要
├── 资源数量摘要
└── 最近状态变化

端口转发
├── 创建转发
└── 转发表格

反向代理
├── 创建代理
└── Caddy 规则

运行时
├── Docker 容器
└── SmolVM

管理员
├── 用户与配额
└── 主机容器分配
```

迁移期间可以使用同一页面上的 Tab 或锚点；功能稳定后再增加独立 URL。普通用户不应下载管理员区域的 HTML，管理员区域也不应阻塞普通用户首屏。

### 4.2 初始页面和片段

初始 `GET /` 只负责：

- 页面布局、导航和当前用户；
- CSRF 上下文；
- 轻量能力摘要；
- 配额摘要；
- 各面板的 loading placeholder。

资源面板使用独立 fragment endpoint，并使用 `hx-trigger="load"` 或明确的用户操作懒加载。每个面板必须可以独立失败，不能因为 Caddy、Docker 或 SmolVM 不可用而导致整个页面失败。

### 4.3 稳定 DTO

Query 层提供面向 Web 的 DTO，例如：

```clojure
{:revision 42
 :user {:username "alice"
        :role :user}
 :capabilities {:docker true
                :caddy true
                :smolvm false}
 :quota {:containers {:used 1 :limit 4}
         :cpu {:used 1.0 :limit 4.0}}
 :forwards [...]
 :caddy [...]
 :containers [...]
 :machines [...]}
```

DTO 规则：

- 使用稳定的枚举值，例如 `:running`、`:stopped`、`:degraded`、`:unknown`；
- 不暴露领域 atom、外部命令结果和内部 map 结构；
- 默认不包含密码、shell token 或外部服务 secret；
- 资源操作使用明确的 `resource-kind` 和 `resource-id`；
- 同一请求内的多个面板尽量来自同一查询快照；
- 能力探测在配置或运行时边界缓存，不能在每次视图渲染时执行外部命令。

## 5. 前端协议重构

### 5.1 资源事件

当前 WebSocket 使用 DOM ID 作为协议字段。目标协议改为资源语义：

```json
{
  "schema": 1,
  "event": "resource.changed",
  "resources": ["forwards", "quota"],
  "revision": 43
}
```

可选字段：

```json
{
  "event_id": "...",
  "operation_id": "...",
  "scope": {"users": ["alice"]}
}
```

前端面板声明自己的资源和 endpoint：

```html
<div
  data-panel="forwards"
  data-refresh-topic="forwards"
  data-endpoint="/panel/forwards">
</div>
```

最终删除 `panelRefreshMap`。迁移期间可以保留旧 `panels` 字段作为兼容协议，但必须设置删除期限。

### 5.2 刷新策略

最终只保留一种明确的刷新来源：

1. mutation 成功产生领域事件；
2. WebSocket 向相关用户发送资源失效事件；
3. 客户端按资源找到面板并重新请求 fragment；
4. 重连后执行一次必要的面板同步。

不要同时让同一个请求依赖：

- mutation 响应中的 OOB；
- 当前客户端收到的 WebSocket 广播；
- 120 秒轮询；
- `afterSwap` 额外请求；

迁移阶段允许保留 OOB，但必须增加去重和退出标准，最终移除基于 `all-atoms` 的差异推断。

### 5.3 WebSocket

目标：

- 使用短期、一次性的 WebSocket ticket，避免长期 token 出现在 URL；
- 按用户和角色定向推送；
- 连接断开时清理连接；
- 重连使用指数退避且不能建立重复连接；
- 消息带 schema/version；
- 页面隐藏时降低或暂停非必要同步；
- 不接受未经设计的客户端命令，当前只读事件通道保持清晰。

Stats 不应为每个连接独立执行 Docker/SmolVM 外部查询。优先采用统一采样缓存；如果产品不需要秒级监控，则改为容器面板可见时的低频轮询。

### 5.4 错误协议

统一错误结构：

```clojure
{:error {:code :quota-exceeded
         :message "容器配额已用尽"
         :field "image"
         :retryable? false}}
```

禁止直接把 `Exception.getMessage` 作为 toast 或响应正文。HTMX 响应可以包含错误片段和 `HX-Trigger`，但 HTTP 状态必须反映失败。

## 6. 安全和正确性任务

这些任务在视觉重构前完成。

### P0

- 将凭据读取从 `GET ...?pass=1` 改为带 CSRF 的 POST；
- Docker 和 SmolVM 使用显式 `data-resource-kind`、`data-resource-id` 和 endpoint；
- Toast 使用 `textContent`，禁止使用未经处理的 `innerHTML`；
- 统一错误码和错误状态码；
- 删除内联 `onclick`、`hx-on-*`；
- 去掉 CSP 中的 `unsafe-inline`；
- WebSocket 改为一次性短期 ticket；
- 密码和外部服务 secret 不进入 URL、日志、异常和普通 DTO；
- 所有新窗口链接添加 `rel="noopener noreferrer"`；
- 所有 query/path 参数通过统一编码函数生成。

### P1

- 移除未使用的 HTMX WebSocket/SSE 扩展；
- 将 body 上的 CSRF header 和 JavaScript 重复注入逻辑统一为一个来源；
- 对 401、403、409、422、503 增加统一页面反馈；
- 为表单增加防重复提交、加载状态和 `aria-busy`；
- 日志 ttyd 会话增加服务端 TTL 和失联回收；
- 统一 Docker、SmolVM、Forward、Caddy 的状态枚举和状态组件；
- 统一 `X-Frame-Options` 与 CSP `frame-ancestors` 的策略，避免相互矛盾。

## 7. 分阶段实施计划

### 阶段 0：基线和契约冻结

**目标：** 在不改变用户行为的情况下建立回归基线。

任务：

- 记录当前路由、method、认证、角色和 CSRF 要求；
- 为现有 fragment 建立最小 HTML 结构断言；
- 记录 DOM ID、HTMX endpoint、WebSocket 事件和 stats 字段；
- 增加普通用户、管理员、能力不可用、资源不存在和并发操作场景；
- 添加浏览器 smoke 测试框架，覆盖初始加载、表单提交和片段刷新；
- 明确旧路由和旧协议的弃用期限。

完成条件：

- 能通过测试发现 DOM ID、刷新事件或响应状态被意外改变；
- 任何旧协议变更都有对应迁移记录；
- 不依赖真实生产凭据和真实 secret。

### 阶段 1：P0 安全和正确性修复

**目标：** 不做视觉改版，先消除高风险协议问题。

主要文件：

- `src/culvert/web/core.clj`
- `src/culvert/web/handlers.clj`
- `src/culvert/web/views.clj`
- `src/culvert/web/server.clj`
- `public/main.js`
- `src/culvert/i18n.clj`

完成条件：

- 凭据读取不再使用 URL；
- 所有失败 mutation 返回非 2xx；
- 错误内容不会被当作 HTML 执行；
- SmolVM 凭据和刷新不再走 Docker 硬编码路径；
- CSP 不再依赖内联脚本；
- 既有 Web 测试和新增安全测试通过。

### 阶段 2：Query/DTO 层和页面外壳

**目标：** 先把查询和渲染分离，再拆页面。

任务：

- 增加 `web/query.clj`；
- 为概览、配额、Forward、Caddy、Docker、SmolVM、Admin 定义 DTO；
- 将能力探测和配额计算移出视图；
- 将 `render-dashboard` 拆成 layout、overview 和功能面板；
- 保留旧 fragment endpoint，先由新 DTO 驱动；
- 首屏改为轻量 shell，运行时面板懒加载。

完成条件：

- 视图函数可使用固定 DTO 独立测试；
- 视图不再直接依赖领域状态 atom；
- 一个外部能力不可用时其他面板仍能渲染；
- 普通用户不会收到管理员面板 HTML。

### 阶段 3：Forward 垂直切片

**目标：** 作为第一个完整样板迁移功能。

任务：

- 增加 Forward Query、View、Handler 和事件映射；
- 引入资源 ID，减少以 `port/protocol/method` 作为隐式主键；
- 统一创建、删除、启停、备注编辑的响应；
- 迁移 Forward 的 fragment 和资源事件；
- 保留旧 `/add`、`/remove`、`/toggle` 等路由兼容别名；
- 增加成功、校验失败、权限失败、并发冲突和外部命令失败测试。

完成条件：

- Forward 完全通过新 Query/Application Service 路径；
- 新旧路径结果一致；
- 新客户端不依赖 `panelRefreshMap` 和 atom OOB；
- 旧路径在文档中标记弃用。

### 阶段 4：Caddy、Docker、SmolVM、Admin

按以下顺序逐个迁移，不能并行进行未验证的多个垂直切片：

1. Caddy；
2. Docker 端口管理；
3. Docker 生命周期和日志；
4. SmolVM；
5. Admin/User。

每个切片必须重复阶段 3 的流程：

```text
DTO
→ 纯视图
→ Query
→ Application Service
→ 领域事件
→ fragment / JSON adapter
→ WebSocket 失效通知
→ 浏览器回归测试
```

Docker 和 SmolVM 只共享经过验证的展示组件、资源状态组件和请求工具，不共享丢失语义的领域命令。

### 阶段 5：JavaScript 模块化和刷新协议

**目标：** 将 `public/main.js` 拆成职责单一的浏览器模块。

任务：

- `http.js`：请求、CSRF、响应类型和错误处理；
- `toast.js`：安全文本渲染和可访问通知；
- `panels.js`：面板登记、刷新、加载状态和去重；
- `websocket.js`：ticket、连接、重连和事件分发；
- `credentials.js`：凭据显示、复制和清除；
- `logs.js`：日志窗口、TTL 失效和关闭清理；
- `modal.js`：密码弹窗和焦点管理；
- `filters.js`：管理员容器过滤；
- `boot.js`：初始化顺序和页面卸载。

完成条件：

- 没有全局重复 click listener；
- 没有 `innerHTML` 注入用户或服务端错误文本；
- 没有 `onclick` 和 `hx-on-*`；
- WebSocket 事件按资源 topic 分发；
- 所有 fetch 请求使用统一 helper。

### 阶段 6：视觉、响应式和可访问性

**目标：** 在业务协议稳定后进行 UI 重构。

任务：

- Forward/Caddy 桌面端使用表格，移动端提供卡片布局；
- Docker/SmolVM 使用统一资源卡片；
- 操作按钮使用图标加可访问文字；
- 所有表单提供 label、错误位置和焦点反馈；
- 删除、停止、打包使用统一确认组件；
- 支持键盘操作、焦点恢复和 `prefers-reduced-motion`；
- 将浏览器 Tailwind 运行时迁移为固定 CSS；
- 使用构建阶段 SHA-256 资源 manifest。

## 8. 测试和质量门禁

### 8.1 Clojure 测试

每个功能切片至少覆盖：

- DTO 对敏感字段的脱敏；
- 普通用户和管理员的查询范围；
- 领域授权失败；
- CSRF 缺失、错误和过期；
- 参数验证；
- 资源不存在；
- 外部依赖不可用；
- 成功 mutation 产生正确领域事件；
- fragment 响应的稳定 DOM 标识；
- HTTP 状态和 `HX-Trigger`。

### 8.2 浏览器测试

至少覆盖：

- 普通用户加载总览；
- 管理员加载 Admin 区域；
- 创建、删除、启停 Forward；
- Caddy 和容器操作；
- HTMX 片段刷新后事件仍然有效；
- WebSocket 断开、重连和重复事件；
- SmolVM 不会请求 Docker 凭据 endpoint；
- 密码不会进入浏览器 URL；
- 失败表单不会被错误清空；
- 移动视口下表单和操作按钮可用；
- 键盘打开、关闭和提交弹窗。

### 8.3 运行命令

每个阶段至少运行：

```bash
bb test-ns culvert.web-test
bb test-ns culvert.web-ws-test
bb test-ns culvert.web-server-test
bb test
bb fmt-check
bb check
```

如果加入浏览器测试，应将其纳入 `mise run test` 或独立的明确任务，不能只依靠开发者手工验证。

## 9. 回滚策略

1. 每次只切换一个功能垂直切片。
2. 新 Handler 和旧 Handler 同时保留一个明确期限。
3. 新 DTO 先通过兼容 adapter 驱动旧 fragment，验证后再改变 HTML。
4. WebSocket 新事件协议与旧 `refresh` 协议短期并存。
5. 出现错误率、权限异常或资源残留时，优先关闭新切片 feature flag，恢复旧路由。
6. 不删除旧代码，直到新路径完成测试、预生产演练和至少一个观察窗口。
7. 回滚不能回写未经转换的旧 JSON，也不能让 Babashka 与 JVM 同时写同一数据目录。

## 10. 完成定义

本计划完成必须同时满足：

- `views.clj` 不再直接读取领域 atom、调用外部 CLI 或执行 mutation；
- `handlers.clj` 不再承担所有功能的重复编排；
- 路由、认证、角色、CSRF 和错误响应有统一契约；
- 每个资源都有稳定 DTO 和资源语义事件；
- WebSocket 不以 DOM ID 作为业务协议；
- Stats 不按 WebSocket 连接数重复执行外部命令；
- 凭据不出现在 URL、日志和普通页面 DTO；
- 页面不依赖 `unsafe-inline`；
- JavaScript 文案来自统一 i18n；
- 普通用户首屏不加载管理员和所有运行时面板；
- 移动端、键盘和基础屏幕阅读器场景通过测试；
- 旧兼容路由和协议都有明确删除记录；
- Babashka、JVM、Web、WebSocket 和浏览器测试均通过。

## 11. 当前首个执行批次

下一次开发不应直接重写整个 `views.clj`。建议只执行以下批次：

1. 建立路由和 DOM/事件契约测试；
2. 修复凭据 POST、Toast XSS、错误状态码和 SmolVM endpoint；
3. 将 `main.js` 中的请求与 toast 逻辑抽出；
4. 增加 `web/query.clj`，先覆盖配额和 Forward 查询；
5. 保留现有页面外观，使用 DTO 驱动 Forward fragment；
6. 用 Forward 作为第一个完整垂直切片；
7. 通过现有三组 Web 测试和新增浏览器 smoke 后，再迁移 Caddy。

这样可以把风险限制在单个功能范围内，同时为后续 Docker、SmolVM 和 Admin 迁移提供可复用样板。

## 12. 首批实施记录

首批安全和契约改动已完成，范围保持在凭据交互与面板刷新：

- 状态查询接口不再根据 `pass=1` 返回明文凭据；
- Docker 与 SmolVM 分别提供带 CSRF 校验的 POST 凭据接口；
- 凭据卡片使用 `data-resource-kind`、`data-resource-id` 和
  `data-credential-endpoint`，客户端不再拼接 Docker 专用 URL；
- toast 使用 `textContent` 写入消息，避免把服务端文案当作 HTML；
- `smolvm-tbody` 已接入 WebSocket 面板刷新映射；
- 增加了状态 GET 不泄露凭据、凭据 POST 必须带 CSRF 的契约测试。

尚未完成的 P0 项（短期 WebSocket ticket 和统一错误协议）继续按阶段 1
执行，不应因为本批次完成而视为已完成。内联 `onclick`、`hx-on-*` 已从
Web 页面代码中清理；后续只需在浏览器 smoke 测试中防止回归。
