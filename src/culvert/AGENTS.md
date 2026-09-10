# src/culvert 源码树

只约束本目录。语言规范、安全禁令、工具链版本、构建与测试命令一律见根 AGENTS.md，此处不重复。

## 去哪里找

| 目的 | 位置 |
|---|---|
| 组合根 | `system.clj`：`default-dependencies` 声明十个组件与启动顺序，`start!` 失败时逆序回滚 |
| 进程入口 | `main.clj`：注册关机钩子、调用 `system/start!`、主线程保活 |
| 配置收口 | `config.clj`：配置目录、数据目录、外部命令路径只从这里取 |
| 授权最终边界 | `auth.clj`、`authorization.clj` |
| 转发与反代 | `forward.clj`（socat/iptables）、`caddy.clj`（原子替换配置） |
| HTTP 面 | `web/handlers.clj` 路由分发、`web/server.clj` 传输与 WS 升级、`web/views.clj` 渲染、`web/ws.clj` 事件协议 |
| 当前生产状态 | `db.clj`（JSON 读写） |

## 结构与分层

- 扁平领域文件（`auth`、`forward`、`docker`、`s3`、`i18n` 等）目前常把策略、atom 与 I/O 放在同一命名空间；`system` 只组合生命周期入口，不代表 application/domain 分层已经完成。
- 六个子目录形成较明确的职责边界：`web/`、`runtime/`、`smolvm/`、`persistence/`、`workflow/`、`observability/`。
- `persistence/` 与 `workflow/` 已实现但未接入 `system/default-dependencies`：SQLite 事务、schema、导入、saga、状态机、协调报告都不驱动生产资源。判断线上行为看 `db.clj` 与各领域 `sync-*!`，不看这两个目录。
- `web.clj` 只是兼容门面；新代码直接 require `web.core` / `web.handlers` / `web.views` / `web.server` / `web.ws`。

## 依赖方向

- 组合方向是 `main` → `system` → 领域文件与 `web/` 子树；基础设施位于 `config`、`log`、`runtime/`。新增依赖不得让底层命名空间反向 require `system` 或 `main`。
- 外部命令只经 `runtime/process.clj` 的 argv 向量执行；禁止 shell 字符串拼接，禁止绕开它另起进程通道。
- `runtime/instance_lock.clj` 守数据目录单写者；拿不到锁就退出，不降级继续写。
- `workflow/*` 保持纯函数：只吃值、吐报告，不接存储、不发命令、不修复。
- 当前 `docker`、`smolvm` 与 Web 仍有跨域直接调用；新增协作必须通过公开函数和显式数据，禁止直接读写其他组件的私有状态。

## 本树约定

- 领域错误和边界失败使用 `ex-info`，带稳定 `:type`（如 `system` 的 `::start-failed`、`::stop-failed`）与上下文数据；不得吞掉会让状态不确定的异常。
- 副作用压在边界：进程执行在 `runtime/`，监听与升级在 `web/server.clj`，由 `system` 组合各领域成对的 `sync-*!` / `shutdown!` 生命周期入口。
- 核心决策保持只凭输入值可测试的形态；atom、线程、时间留在边界内。
- 新增子命名空间必须对应单一职责；不为设想中的第二实现预抽公共机制。

## 热点与反模式

- 热点是 `web/handlers.clj`、`web/views.clj`、`docker.clj`、`smolvm/manager.clj`：每次改动的目标是缩小职责、拆掉分支，不是继续堆条件。
- 反模式：
  - 在 views 或 handlers 里直接执行外部命令、写全局 atom、做跨领域编排。
  - 把 Handler 当唯一授权边界；资源 mutation 必须回到 `auth` / `authorization` 复核。
  - 给 `docker` 与 `smolvm` 强行统一 runtime 抽象；策略相似但运行时语义不同，保留局部重复。
  - 假设 SQLite / workflow 已在生产链路上，或为将来接入往 `db.clj` 加双写。
  - 让路由、DOM 标识与 WS 事件协议各自演化；三者改动必须同一次对齐。
