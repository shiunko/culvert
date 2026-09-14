# 13-3 P3 Kiosk 大屏

上级文档：[13 前端原型对齐与落地计划](13-前端原型对齐与落地计划.md)

## 目标

按原型提供独立监控大屏 `GET /wall`：远距离可读、异常突出、持续刷新；无普通顶栏导航，无抽屉/命令面板/编辑按钮，保持只读。

## 交付物

| 类型 | 文件 |
|---|---|
| 路由 | `src/culvert/web/handlers.clj`（`GET /wall`，`wrap-auth`） |
| 视图 | `src/culvert/web/views.clj`（`render-wall`，复用接线图 lane 渲染） |
| JS | `public/console.js`（时钟与连接态） |
| CSS | `styles/app.css`（`pf-kiosk-*`） |

## 依赖

- P1（令牌、图标、接线图渲染函数）；P4 的异常告警若已做则复用 `activity`，未做前用状态计算。

## TODOs

### A 页面与路由

- [x] A1 `render-wall` 输出极简 shell：品牌 `CULVERT MONITOR`、右上角时钟与连接状态灯、无一级导航/Drawer/命令面板。
- [x] A2 `route-dispatcher` 新增 `GET /wall`（`core/wrap-auth`），复用与 `/` 相同的认证边界。
- [x] A3 页面只读，不输出任何 mutation 入口。

### B 大屏内容

- [x] B1 4 张 KPI 卡：在线资源、异常资源（红色高亮）、实时吞吐（**无真实数据则改为其他真实指标，如端口占用/运行时长，不得造吞吐**）、活动事件计数。
- [x] B2 「系统接线概览」：复用 `ui/topology.clj` 的 lane 渲染，取前 4–5 条，放大字号与间距。
- [x] B3「异常告警」区：由 forward/caddy/docker/smolvm 状态计算 dead/abnormal 列表，优先展示；`severity` 分 info/warning/error。
- [x] B4 刷新：复用 `hx-get` + WS + 轮询；`#wall-kpis` / `#wall-topology` / `#wall-alerts` 各自声明刷新触发器。
- [x] B5 CSS：大字号、宽间距、高对比；`≤600px` 允许网格重排，禁止横向溢出。

### C 时钟与连接态

- [x] C1 `console.js` 每秒更新大屏时钟与日期（`zh-CN`）。
- [x] C2 连接状态灯复用 `ws-status` 事件的 connected/reconnecting。

### D 契约与门禁

- [x] D1 若新增片段容器，登记进 `fragment-registry` / `panelRefreshMap` 并更新上级文档 5.2。
- [x] D2 更新 `src/culvert/web/AGENTS.md` 路由与静态资源表。
- [x] D3 更新 `docs/12` 进度记录与本文/上级文档状态。
- [x] D4 门禁：`bb check`、`bb fmt-check`、`bb test`、`mise run css-check`、`node --check public/console.js`。

## 验收标准

- `/wall` 需登录访问，未登录重定向/拒绝，与 `/` 边界一致。
- 大屏无任何编辑/启停入口。
- 时钟实时走动；连接灯随 WS 状态变化。
- 异常资源在告警区突出显示；无异常时有明确空状态。
- 宽屏铺满、窄屏重排无横向溢出。

## 风险与注意

- 大屏是"只读监看"，禁止为好看而引入假指标；无真实数据时改写为其他真实指标或留空并说明。
- 复用 P1 的 lane 渲染函数，避免维护第二套接线图。

## 实施记录（2026-09-14）

P3 全部 TODO 已落地：

- `views.clj` 新增 `render-wall`（极简 shell：品牌 `CULVERT MONITOR`、连接状态灯、实时时钟、返回控制台链接；不含一级导航/Drawer/命令面板/任何 `hx-post`）与三个片段渲染函数 `render-wall-kpis-content`/`render-wall-topology-content`/`render-wall-alerts-content`。
- 接线概览复用 P1 的 `topo-connector` lane 与 `status-colors`，新增只读 `wall-topo-row`（无 `data-action`）；异常告警由 forward/caddy/docker/smolvm 的 dead/unknown 计算，dead 优先，severity 为 error/warning，无异常时有明确空状态。
- 4 张 KPI 全部为真实指标：在线资源、异常资源、端口转发（含运行中条数）、容器/微虚机；**未使用原型中的吞吐/事件审计数**（无真实来源，P4 活动记录落地后再补事件计数）。
- `handlers.clj` 新增 `GET /wall`（`wrap-auth`，与 `/` 同边界）与 `GET /panel/wall-kpis|wall-topology|wall-alerts`；`derived-panels` 追加 `wall-*`，mutation 广播可实时刷新大屏，配合 `panelRefreshMap` 与 30/30/15 秒轮询。
- `public/console.js` 新增大屏时钟（`zh-CN`）与通用 `ws-status`→`[data-ws-indicator]` 状态灯（`/wall` 不加载 `tabs.js`，故在此兜底）。
- 新增 `pf-kiosk-*` 样式，宽屏铺满、窄屏（≤1000/≤640px）网格重排且无横向溢出。

### 顺带修复（P1/P3 共同依赖）

`public/main.js` 的 WebSocket 仍在使用旧的 `getWsToken()` / `?token=`，而 `server.clj` 的 `/ws` 分流只认一次性 `ticket`（见 AGENTS「WebSocket 短期 ticket」），导致实时连接实际无法建立。已改为先用带 CSRF 的 `POST /ws/ticket` 换取票据，再以 `/ws?ticket=` 升级，并抽出 `scheduleWsReconnect`；移除失效的 `getWsToken`。这是 P1 实时刷新与 P3 状态灯生效的前置条件。

验证：`bb fmt-check`、`clj-kondo`（0/0）、`bb check`、`bb test`（156/860）、`node --check` 通过；`route-dispatcher` 实测 `/wall` 未认证 401、已认证 200 且全局无 `hx-post`/`command-palette`/`app-tabs`/`tabs.js`；伪造 dead 转发后大屏告警区正确显示；三个 `/panel/wall-*` 端点均 200。新增 `wall-is-read-only-and-authenticated` 测试。
