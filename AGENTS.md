# 项目知识库

**生成时间：** 2026-09-03 14:52:34 +0800
**提交：** `67d4c46`
**分支：** `main`

## 项目概览

`culvert` 是同时支持 Babashka 与 JVM Clojure 的单机控制面，管理端口转发、Caddy、Docker/Podman、SmolVM、ttyd、S3/FUSE 和基于 Hiccup/HTMX 的管理界面。生产状态目前仍以 JSON 为主；SQLite、事务预留和工作流原语已经实现，但尚未接入默认运行链。

## 目录结构

```text
.
├── src/culvert/          # 应用、领域、运行时适配器与迁移中子系统
├── test/culvert/         # clojure.test 测试，基本镜像源码命名空间
├── public/                   # 无独立构建链的浏览器静态资源
├── data/                     # 样例与本地可变状态；大部分内容不入库
├── deploy/                   # Linux + systemd 生产部署资产
├── docs/                     # 迁移、架构、测试和运维决策记录
├── bb.edn                    # Babashka 运行与开发任务
├── deps.edn                  # JVM 依赖与别名
└── build.clj                 # uberjar、构建信息和 SHA-256
```

## 任务定位

| 任务 | 位置 | 说明 |
|---|---|---|
| 启动与关闭 | `src/culvert/main.clj`、`system.clj` | `system` 是唯一组合根 |
| 配置与能力探测 | `src/culvert/config.clj` | 配置目录、数据目录和外部命令路径在此收口 |
| 用户、会话与授权 | `auth.clj`、`authorization.clj` | 领域授权必须是最终边界 |
| 转发与代理 | `forward.clj`、`caddy.clj` | 涉及 socat、iptables、Caddy 和原子配置替换 |
| 容器与微虚机 | `docker.clj`、`smolvm/` | 两者策略相似，但运行时语义不可强行统一 |
| HTTP、HTMX 与 WebSocket | `src/culvert/web/`、`public/` | 路由、DOM 标识和事件协议彼此关联 |
| JSON 状态 | `src/culvert/db.clj` | 当前默认生产路径 |
| SQLite 与导入 | `src/culvert/persistence/` | 显式启用，尚非默认生产路径 |
| 状态机、补偿与协调报告 | `src/culvert/workflow/` | 独立原语，不直接连接存储或外部命令 |
| 外部进程与实例锁 | `src/culvert/runtime/` | argv 执行和数据目录单写者保护 |
| 当前实施状态 | `docs/10-实施状态与生产切换清单.md` | 不得把规划目标描述成已完成功能 |
| 生产运维 | `docs/07-JVM部署与运维.md`、`deploy/` | 操作细节以 runbook 为准 |

## 代码地图

| 符号 | 类型 | 位置 | 作用 |
|---|---|---|---|
| `culvert.main/-main` | 入口函数 | `src/culvert/main.clj` | 注册关闭钩子并启动系统 |
| `system/default-dependencies` | 组合配置 | `src/culvert/system.clj` | 声明十个组件及启动顺序 |
| `system/start!`、`system/stop!` | 生命周期 | `src/culvert/system.clj` | 顺序启动、失败回滚、逆序停止 |
| `web.handlers/route-dispatcher` | HTTP 路由 | `src/culvert/web/handlers.clj` | 所有 HTTP 业务入口和健康检查 |
| `web.server/start-server!` | 传输入口 | `src/culvert/web/server.clj` | 组合 HTTP 中间件和 WebSocket 升级 |
| `runtime.process/run` | 基础设施函数 | `src/culvert/runtime/process.clj` | 以 argv 执行外部命令并保留结构化结果 |
| `persistence.sqlite/with-transaction` | 事务边界 | `src/culvert/persistence/sqlite.clj` | SQLite 事务和连接配置 |
| `workflow.saga/execute` | 工作流原语 | `src/culvert/workflow/saga.clj` | 顺序执行并逆序补偿 |
| `workflow.reconciliation/report` | 纯函数 | `src/culvert/workflow/reconciliation.clj` | 只报告差异，不执行修复或删除 |

> 当前未安装 `clojure-lsp`，仓库内 CodeGraph 索引也不可用于本项目；引用热度由命名空间依赖与测试关系测量。`config`、`runtime.process`、`auth` 和 Web 子树是主要扇出点。

## 全局约定

- 所有新增或修改的文档、注释、docstring、测试说明和运维说明必须使用中文；代码标识符、协议字段、命令、路径和第三方专名保持原样。
- 修改含英文注释或 docstring 的代码时，应在同一修改范围内将相关说明翻译为中文；不要为此顺带改写无关文件。
- 项目采用 `deps.edn + tools.build`，不得无明确约束引入 Leiningen 或第二套构建体系。
- Babashka 与 JVM 必须保持同一业务代码和核心测试；JVM 专属 SQLite 测试可在 Babashka 下显式跳过。
- 外部命令使用 argv 向量，经 `culvert.runtime.process` 或职责明确的适配器调用；禁止拼接含用户输入的 shell 字符串。
- 错误使用带稳定 `:type` 和必要上下文的 `ex-info`；不得吞掉导致状态不确定的异常。
- 测试文件命名为 `*_test.clj`，由 `test/culvert/runner.clj` 自动发现；测试从仓库根目录运行。

## Simple Made Easy

- “简单”指独立概念没有交织，不等于代码短、文件少、写起来熟悉或依赖就在手边。
- 函数、命名空间和模块围绕单一职责；当一个单元同时决定授权、持久化、进程、渲染和生命周期时，应先分离决策维度。
- 优先不可变值、纯函数和显式参数/返回值；把 atom、时间、I/O、线程和外部进程限制在清晰边界。
- 区分组合与交织：允许调用方组合独立组件，不让组件依赖彼此内部状态、隐式调用顺序或动态约定。
- 依赖由组合根注入，副作用由边界执行；核心策略应能只凭输入值测试。
- 评审最终产物是否容易理解、独立修改和调试，而不是实现过程是否省事。
- 允许少量局部重复以保留不同领域语义；只有至少两个真实实现的职责和语义一致时才抽取公共机制。
- 不建立万能 runtime、repository、workflow 或 application 抽象；新抽象必须减少交织，而非隐藏交织。

权威依据：[Simple Made Easy 演讲转录](https://github.com/matthiasn/talk-transcripts/blob/ff1b612a9aebd503ee8decc349f2cd31056a1620/Hickey_Rich/SimpleMadeEasy.md) 与 [Clojure Values and Change](https://clojure.org/about/state)。

## 项目禁令

- Babashka 与 JVM 实例绝不能同时写同一个 `CULVERT_DATA_DIR`；切换前停止旧实例、确认退出并完成备份。
- 不得长期双写 JSON 与 SQLite，也不得把迁移后的 SQLite 状态未经转换回写旧 JSON。
- 不得把 Handler 作为唯一授权边界；资源 mutation 必须在领域入口再次校验 actor 与所有权。
- 不得在视图或 Handler 中继续扩大直接外部命令、全局 atom 和跨领域编排。
- 不得自动删除来源不明的外部资源；reconciliation 默认只报告差异。
- 不得记录密码、Bearer token、CSRF token、S3 secret、ttyd 凭据或未脱敏请求正文。
- 不得以 root 运行 JVM 控制面，也不得通过关闭全部 systemd 防护解决外部工具权限问题。
- 不使用 `latest` 或任意 JDK 26；构建、CI 和生产精确固定 Eclipse Temurin `26.0.2+10`。

## 常用命令

```bash
bb run                              # Babashka 运行
mise run run                        # JVM 运行
bb test-ns culvert.web-test     # 单命名空间测试
mise run test                       # 完整本地质量门禁
bb fmt                              # 修改源码格式
bb fmt-check                        # 仅检查格式
bb check                            # 检查主要命名空间加载
clojure -T:build uber               # 构建 uberjar 和 SHA-256
mise run package                    # 生成 jpackage 应用镜像
mise run css                        # 用 Tailwind CLI 把 styles/app.css 编译为 public/app.css
```

## 注意事项

- `handlers.clj`、`views.clj`、`docker.clj` 和 `smolvm/manager.clj` 是主要复杂度热点；修改时优先缩小职责，不继续堆叠分支。
- `web.clj` 仅为兼容门面；新代码直接依赖 `web.core`、`web.handlers`、`web.views`、`web.server` 或 `web.ws`。
- `persistence/*` 与 `workflow/*` 当前不在 `system/default-dependencies` 主链中，不能假设其已驱动生产资源。
- `public/` 会由 `build.clj` 显式复制到 `target/classes/public`；新增资源必须同步静态路由和页面引用。
- `docs/` 中部分内容是目标设计；判断当前能力时以代码、测试和 `docs/10-实施状态与生产切换清单.md` 为准。
