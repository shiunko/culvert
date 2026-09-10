# 阶段 3：JVM 生产切换

## 1. 阶段目标

建立可重复构建、部署和回滚的 JVM 制品，在测试环境验证后将生产运行时从 Babashka 切换到 Eclipse Temurin JDK `26.0.2+10`。

本阶段只切换运行与发布方式，不同时实施大规模业务重构。

## 2. 进入条件

- 阶段 1 的高风险安全问题已完成；
- 阶段 2 的 BB/JVM 单元测试均通过；
- 配置和状态目录已移出源码仓库；
- 已准备生产数据备份和恢复演练；
- 外部命令 adapter 在 JVM 下通过测试；
- 开发、CI 和测试环境的 JVM 均已确认为 Eclipse Temurin `26.0.2+10`。

## 3. 工作项

### 3.1 创建构建配置

任务：

1. 创建 `build.clj`；
2. 定义 uberjar 主类 `culvert.main`；
3. 包含 `public/` 和必要 resources；
4. 排除测试和开发依赖；
5. 制品名称包含项目版本；
6. 输出依赖锁定信息、Git revision 和构建时间；
7. 构建结果生成 SHA-256；
8. 构建元数据记录 JVM 供应商、完整版本和 build number，构建任务在不是 Eclipse Temurin `26.0.2+10` 时直接失败。

验收：

```sh
clojure -T:build clean
clojure -T:build uber
java -jar target/culvert-<version>.jar
```

可以在安装 Eclipse Temurin `26.0.2+10` 的干净环境启动；使用其他 JVM 构建不得通过发布门禁。

### 3.2 配置与目录规范

建议运行目录：

```text
/etc/culvert/config.json
/var/lib/culvert/
/var/log/culvert/
/opt/culvert/culvert.jar
```

任务：

1. 支持 CLI 参数或环境变量指定配置和数据目录；
2. 所有相对路径基于明确的 base directory，而不是当前工作目录；
3. 启动时检查目录存在性、权限和磁盘可写性；
4. 配置、数据和静态资源路径分别管理；
5. 输出脱敏后的有效配置摘要。

验收：

- 从任意工作目录启动结果一致；
- 配置错误不会覆盖现有数据；
- 敏感值不会出现在日志中。

### 3.3 服务管理

优先支持 systemd，必要时再提供容器镜像。

任务：

1. 创建非 root 服务用户；
2. 定义 systemd unit；
3. 设置合理的 `Restart`、`TimeoutStopSec` 和文件限制；
4. 明确 Docker socket、iptables、挂载等权限；
5. 避免直接以全权限 root 运行整个控制面；
6. 增加 `/health/live` 和 `/health/ready`；
7. readiness 检查配置、数据目录及必需 adapter 状态。

验收：

- systemd 可以启动、停止和重启服务；
- SIGTERM 在超时内完成清理；
- 必需依赖不可用时 readiness 为失败；
- 不需要的 Linux capabilities 被移除。

### 3.4 JVM 参数和资源基线

生产环境必须精确安装 Eclipse Temurin `26.0.2+10`，并在服务启动前校验供应商、完整版本和 build number。不得仅依赖 `java` 位于 `PATH`；systemd 或容器配置应使用明确的 `JAVA_HOME`/Java 可执行文件路径。Temurin 26 不是 LTS，升级到其他补丁或 feature release 必须作为独立发布执行，禁止自动跟随系统 JDK 更新。

任务：

1. 在测试环境测量启动时间和常驻内存；
2. 设置合理的堆内存边界；
3. 选择适合小型长期服务的 GC 默认配置；
4. 明确时区和文件编码；
5. 对线程数、WebSocket 数和外部进程数建立上限；
6. 开启 JVM 崩溃和 OOM 诊断文件，但保护其中的敏感信息；
7. 将 Temurin `26.0.2+10` 纳入制品清单、部署检查和运行时监控标签；
8. 建立非 LTS JDK 的支持周期检查与升级窗口，升级前运行完整回归和灰度验证。

初始建议仅作为基线，最终参数需通过测量确定：

```text
-Dfile.encoding=UTF-8
-Duser.timezone=UTC 或部署所需时区
-XX:+ExitOnOutOfMemoryError
```

不要在没有压测数据时添加大量复杂 GC 参数。

### 3.5 测试环境验证

至少覆盖：

1. 用户登录、注销和会话撤销；
2. Forward 和 Caddy 新增、切换、删除；
3. Docker 创建、启动、停止、删除和日志查看；
4. SmolVM 创建、启动、停止、删除和端口暴露；
5. WebSocket 状态推送；
6. 服务重启后的状态恢复；
7. 外部命令失败；
8. Caddy reload 失败；
9. 磁盘不可写；
10. SIGTERM 和异常退出。

验收：

- JVM 与原 BB 版本在受支持行为上等价；
- 没有额外孤儿进程、端口或挂载；
- 重启恢复结果可预测；
- 数据备份和恢复演练成功。

### 3.6 灰度与生产切换

建议步骤：

1. 冻结写操作或安排短维护窗口；
2. 备份配置和全部状态；
3. 停止 Babashka 服务并确认子进程状态；
4. 启动 JVM 服务；
5. 执行 smoke test；
6. 观察错误率、内存、线程、外部进程和状态恢复；
7. 达到观察窗口后完成切换。

不要让 BB 和 JVM 实例同时写同一数据目录。

## 4. 回滚条件

出现以下任一情况应回滚：

- 无法正确加载现有状态；
- 资源操作行为与已验证版本不一致；
- 出现持续性高内存或线程泄漏；
- graceful shutdown 无法清理关键资源；
- 发生数据损坏或重复端口分配。

## 5. 回滚步骤

1. 停止 JVM 实例；
2. 保留故障现场和日志；
3. 如数据格式未变化，直接恢复 BB 服务；
4. 如数据发生变化，从切换前备份恢复；
5. 运行资源 reconciliation 或人工核对孤儿进程、Caddy、iptables 和挂载；
6. 记录失败原因后再安排下一次切换。

## 6. 退出标准

- JVM uberjar 已成为生产制品；
- 生产运行时已校验为 Eclipse Temurin `26.0.2+10`，且不会随系统更新自动漂移；
- 生产环境稳定运行达到约定观察周期；
- 部署和回滚文档经过实际演练；
- Babashka 启动路径暂时保留但不再作为默认生产入口；
- 监控可以识别 JVM、HTTP、WebSocket 和外部资源异常。
