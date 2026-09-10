# 阶段 2：JVM 双运行时迁移

## 1. 阶段目标

在尽量不改变业务行为的前提下，让项目同时支持 Babashka 和 JVM Clojure，为生产切换建立兼容性和回归基线。

本阶段不进行大规模模块拆分、数据库替换或 HTTP 框架迁移。

## 2. 目标技术栈

- Eclipse Temurin JDK `26.0.2+10`；
- Clojure JVM；
- `deps.edn`；
- `tools.build`；
- http-kit；
- Cheshire；
- Hiccup；
- `babashka.process` 作为 JVM 依赖继续使用，后续再决定是否包装或替换；
- 标准 nREPL，仅用于开发 alias。

所有依赖必须固定到实际验证过的版本，不使用 `latest`。JVM 必须精确锁定为 Eclipse Temurin `26.0.2+10`，开发、CI 和测试不得使用其他供应商、补丁版本或 build number 替代。

## 3. 工作项

### 3.1 创建 `deps.edn`

任务：

1. 默认 classpath 只包含 `src` 和 `resources`；
2. 显式声明所有生产依赖；
3. `:test` alias 增加 `test` 路径和测试 runner；
4. `:dev` alias 增加 nREPL 和开发工具；
5. `:build` alias 引入 `tools.build`；
6. 确认 JVM 下没有依赖 Babashka 隐式内置库。

建议 alias：

```text
:dev
:test
:build
:repl
:integration
```

验收：

```sh
clojure -M -m culvert.main
clojure -M:test
```

至少能完成 namespace 加载和纯单元测试。

### 3.2 调整 nREPL

当前 `src/culvert/main.clj` 动态加载 `babashka.nrepl/start-server!`。

任务：

1. 从生产 `-main` 中移除自动启动调试 nREPL；
2. JVM 开发通过 `:dev`/`:repl` alias 启动标准 nREPL；
3. Babashka 开发模式如需保留，放在 `bb.edn` 专用任务中；
4. `.nrepl-port` 加入忽略规则并由开发工具管理。

验收：

- 生产启动不会暴露 nREPL；
- JVM REPL 可以连接并 reload namespace；
- 未安装 nREPL 不影响生产构建。

### 3.3 建立运行时兼容层

任务：

1. 搜索并登记所有 Babashka 专有 API；
2. 将进程调用统一包到内部 adapter，例如 `culvert.runtime.process`；
3. adapter 提供同步执行、流式执行、超时、终止和结构化结果；
4. 暂时可以由 `babashka.process` 实现，业务模块不再直接 require 它；
5. 禁止通过字符串 shell 拼接用户输入，统一使用 argv；
6. 对非零退出码建立一致的异常协议。

建议结果结构：

```clojure
{:exit 0
 :out "..."
 :err "..."
 :duration-ms 15
 :command ["docker" "inspect" "..."]}
```

验收：

- `docker.clj`、`forward.clj`、`caddy.clj`、`s3.clj` 和 SmolVM manager 不直接依赖具体进程库；
- adapter 的 argv、超时、非零退出和流式关闭均有测试；
- BB/JVM 的行为测试一致。

### 3.4 修复测试入口和隔离

任务：

1. JVM 使用自动测试发现工具；
2. Babashka 测试任务改为当前版本支持的 task 形式；
3. 测试数据库全部使用独立临时目录；
4. fixture 使用 `finally` 保证清理；
5. 单元测试不加载真实 `data/config.json`；
6. 单元测试不探测本机 Docker/Podman；
7. 外部依赖测试放入 `:integration`。

验收：

- 新增 `*_test.clj` 不需要手工登记；
- 测试失败不会在仓库留下 JSON 或临时文件；
- JVM 与 BB 的纯单元测试结果一致；
- 本机没有 Docker 时单元测试仍能通过。

### 3.5 消除 namespace 加载副作用的最小改造

完整生命周期重构放在阶段 4，但 JVM 兼容阶段需要先移除妨碍测试的副作用。

任务：

1. 配置读取改为显式函数，不在 require 时 `System/exit`；
2. token、CSRF、限流清理线程不在 namespace 加载时启动；
3. capability 探测延迟到启动阶段；
4. 测试可构造独立配置 map；
5. 暂不全面引入 system library，但建立清晰的 start/stop 函数。

验收：

- require 生产 namespace 不会启动线程、HTTP 服务或外部进程；
- 配置错误通过结构化异常返回；
- 测试可以独立加载任意 namespace。

### 3.6 固定工具版本

任务：

1. `mise.toml` 将 Java 精确固定为 Eclipse Temurin `26.0.2+10`，并固定 Clojure CLI、Babashka、clj-kondo 和 formatter；
2. CI 在测试前校验 `java -version` 的供应商、版本和 build number，发现偏差立即失败；
3. 删除源码树中单平台 `bb` 二进制；
4. 统一使用 cljfmt 或 zprint，不同时维护两套格式工具；
5. 明确支持的 Linux/macOS 范围；
6. 记录 Temurin 26 非 LTS 风险，并为 JDK 补丁或 feature release 升级建立独立的测试、灰度和回滚流程。

验收：

- 新环境可根据锁定配置安装相同工具；
- 本地和 CI 的 `java -version` 均确认为 Eclipse Temurin `26.0.2+10`；
- 仓库不包含单平台运行时二进制；
- `fmt-check` 与 `fmt` 使用相同工具和配置。

## 4. 验证矩阵

| 检查 | Babashka | JVM |
|---|---:|---:|
| namespace 加载 | 必须 | 必须 |
| 纯单元测试 | 必须 | 必须 |
| lint | 共享 | 共享 |
| format check | 共享 | 共享 |
| HTTP smoke test | 建议 | 必须 |
| 外部命令集成测试 | 建议 | 必须 |

## 5. 退出标准

- `deps.edn` 和 JVM 开发命令可用；
- 所有生产依赖显式声明并锁定；
- BB/JVM 共用的纯单元测试全部通过；
- namespace 加载没有后台线程和外部进程副作用；
- 外部进程调用开始通过统一 adapter；
- 尚未移除 Babashka 生产启动方式。

## 6. 回滚策略

- 保留现有 `bb.edn` 生产启动任务；
- JVM 兼容改动不得改变数据格式；
- 每个兼容性 PR 同时运行 BB 和 JVM 测试；
- 如果依赖在 JVM 下行为不一致，优先在 adapter 中兼容，不在多个业务模块中增加运行时判断。
