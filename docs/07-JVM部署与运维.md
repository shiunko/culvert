# JVM 部署与运维 Runbook

本文定义 culvert JVM 制品在 Linux x86_64 + systemd 上的构建、部署、升级、回滚、备份和恢复基线。推荐制品是 jpackage `app-image` 归档，已内置 **Eclipse Temurin `26.0.2+10`** runtime，目标主机无需安装 Java。Temurin 26 非 LTS，任何 JDK 补丁或 feature release 升级都必须作为独立变更完成测试、灰度和回滚演练。

## 1. 安全边界与目录

建议目录：

```text
/etc/culvert/config.json                  # 配置，root:culvert 0600
/etc/culvert/culvert.env              # 非敏感运行参数，root:culvert 0640
/opt/culvert/releases/<release>/          # 解压后的不可变 app-image
/opt/culvert/current                      # 指向当前 app-image 的软链
/opt/culvert/current/bin/culvert      # 使用内置 runtime 的启动器
/var/lib/culvert/data/                    # 可变状态
/var/lib/culvert/diagnostics/             # JVM 崩溃/OOM 诊断，可能含敏感信息
/var/backups/culvert/                     # 受限备份目录
```

硬性规则：

- 服务使用专用的 `culvert` 用户和组，禁止以 root 运行 JVM 控制面。
- **BB 与 JVM 实例绝不能同时写同一个数据目录。** 切换前必须停止旧实例并确认进程退出。需要并行对比时，为两个实例配置完全不同的 `CULVERT_DATA_DIR`，且不要复制后再双向合并。
- `/etc/culvert/config.json` 可能含首次启动凭据；不要提交到 Git、写入 unit 或输出到日志。示例不含真实凭据。
- heap dump、崩溃日志和备份可能含敏感数据，按生产数据同等级保护。
- `GET /health` 与 `GET /health/live` 仅表示 HTTP 进程存活；部署编排应使用 `GET /health/ready` 判断 system 启动完成，停止和启动失败期间该端点返回 `503`。
- 首次 JVM + JSON 切换默认设置 `dockerMutationsEnabled=false`、`smolvmMutationsEnabled=false`、`ttydEnabled=false` 和 `s3.enabled=false`。启用 Docker/SmolVM/S3 前必须另行完成其业务事务、故障补偿和真实 adapter 验收；若对应 JSON 已有资源记录，禁用状态会拒绝启动，运维必须先制定迁移方案。

## 2. CI 与本地构建

GitHub Actions 工作流 `.github/workflows/jvm-ci.yml` 在 `ubuntu-24.04` 上执行：

1. 安装 Temurin `26.0.2+10`，并要求 `java -version` 包含 `Temurin-26.0.2+10`；
2. `bb test`；
3. `clojure -M:test`；
4. `bb check`；
5. `clj-kondo --lint src test`；
6. `clojure -T:build uber`；
7. 生成并验证 jar SHA-256；
8. 在 Linux x86_64 上用 `jpackage` 生成内置 runtime 的 app-image 归档并验证 SHA-256；
9. 上传 jar、app-image 归档及对应 `.sha256` 文件。

本地发布构建也必须使用同一 JVM：

```sh
java -version 2>&1 | grep -F 'Temurin-26.0.2+10'
bb test
clojure -M:test
bb check
clj-kondo --lint src test
clojure -T:build uber
# 必须在 Linux x86_64 上执行；mise 会注入固定 JAVA_HOME。
mise run package
cd target/package
sha256sum --check culvert-4.0.0-GIT_SHA-linux-x64-jre26.0.2+10.tar.gz.sha256
```

`deploy/bin/build-jpackage` 会拒绝非 Linux x86_64、未设置 `JAVA_HOME`、非精确 Temurin `26.0.2+10` 或缺少 uberjar 的环境。归档名称包含 12 位 Git revision，摘要文件只记录归档 basename，可在下载目录直接使用 `sha256sum --check`。只部署来自受信任 CI run 的归档和校验文件；先比对下载渠道提供的摘要，再安装。

## 3. 首次部署

以下命令由有 sudo 权限的运维人员执行。示例 release 名应替换为本次发布的不可变标识，例如版本加 Git SHA。

### 3.1 验证内置 runtime

目标主机无需安装 Java。下载归档后先验证 SHA-256，解压到隔离 staging 目录，再检查 jlink runtime 清单与构建脚本写入的原始 Temurin 构建清单。裁剪 runtime 通常不包含独立的 `bin/java`：

```sh
sha256sum --check culvert-4.0.0-GIT_SHA-linux-x64-jre26.0.2+10.tar.gz.sha256
tar --extract --gzip --file culvert-4.0.0-GIT_SHA-linux-x64-jre26.0.2+10.tar.gz
grep -F 'JAVA_VERSION="26.0.2"' culvert/lib/runtime/release
grep -F 'IMPLEMENTOR="Eclipse Adoptium"' culvert/lib/runtime/runtime-build-info
grep -F 'JAVA_RUNTIME_VERSION="26.0.2+10"' culvert/lib/runtime/runtime-build-info
```

不得用系统 `/usr/bin/java` 替换包内 runtime，也不得只按归档文件名推断版本。

### 3.2 创建非 root 账户和目录

```sh
sudo useradd --system --home-dir /var/lib/culvert --shell /usr/sbin/nologin culvert
sudo install -d -o root -g culvert -m 0750 /etc/culvert
sudo install -d -o root -g root -m 0755 /opt/culvert/releases
sudo install -d -o culvert -g culvert -m 0700 /var/lib/culvert/data /var/lib/culvert/diagnostics
sudo install -d -o root -g root -m 0700 /var/backups/culvert
```

### 3.3 安装配置和模板

```sh
sudo install -o root -g culvert -m 0640 deploy/culvert.env.example /etc/culvert/culvert.env
sudo install -o root -g culvert -m 0600 deploy/config.json.example /etc/culvert/config.json
sudo install -o root -g root -m 0644 deploy/systemd/culvert.service /etc/systemd/system/culvert.service
```

在主机上编辑 `/etc/culvert/config.json`。如需首次管理员引导，在受限文件中设置唯一用户名和高强度随机密码；引导完成后按应用认证流程轮换或移除不再需要的引导值。不得使用 `CHANGE_ME`、`admin123` 等占位值。

按主机测量结果调整 `/etc/culvert/culvert.env` 中堆大小；环境文件不要放凭据。确保 `CULVERT_CONFIG_DIR` 与 `CULVERT_DATA_DIR` 指向不同职责的目录。

### 3.4 安装并校验制品

假设下载文件位于受限 staging 目录，且已执行第 3.1 节校验：

```sh
sudo install -d -o root -g root -m 0755 /opt/culvert/releases/4.0.0-GIT_SHA
sudo cp -a culvert/. /opt/culvert/releases/4.0.0-GIT_SHA/
sudo chown -R root:root /opt/culvert/releases/4.0.0-GIT_SHA
sudo ln -sfn /opt/culvert/releases/4.0.0-GIT_SHA /opt/culvert/current.new
sudo mv -Tf /opt/culvert/current.new /opt/culvert/current
```

记录归档 SHA-256、Git revision、CI run URL、内置 runtime 版本、部署人和部署时间到变更单。app-image 内置 `-Xms128m`、`-Xmx512m`、UTF-8、UTC 和 OOM 退出参数；依赖生产路径的诊断参数由 `/etc/culvert/culvert.env` 中的 `JAVA_TOOL_OPTIONS` 提供；launcher 启动时会在日志打印该变量名和值，因此其中不得放置秘密。

### 3.5 外部命令权限

应用可按配置调用 Podman/Docker、Caddy、socat、iptables、ttyd、SmolVM 和 S3/FUSE 工具。只安装实际启用的能力，并遵循最小权限：

- 优先使用 rootless Podman，并为 `culvert` 用户配置独立存储。
- 若使用 Docker socket，将用户加入 `docker` 组等价于高权限主机访问，必须单独风险审批；不要默认授予。
- 不要给整个 Java 进程或 jar 设置 capabilities/setuid。需要网络特权时，优先把受限动作拆到经审核的 helper/policy；在未建立该边界前，不启用相应功能。
- `ProtectSystem=strict` 下仅 `/var/lib/culvert` 可写；外部工具如需其他写路径，应逐项增加精确的 `ReadWritePaths=`，不要关闭全部 systemd 防护。
- 配置中的命令路径优先写绝对路径，且可执行文件由 root 管理、服务用户不可修改。

### 3.6 启动与验收

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now culvert.service
sudo systemctl --no-pager --full status culvert.service
sudo journalctl --no-pager -u culvert.service -n 100
curl --fail --silent --show-error http://127.0.0.1:10092/health/live
curl --fail --silent --show-error http://127.0.0.1:10092/health/ready
```

同时验证：

- `/opt/culvert/current/lib/runtime/release` 为 Java `26.0.2`，且 `runtime-build-info` 为 Eclipse Adoptium `26.0.2+10`；
- 服务进程用户为 `culvert`；
- 配置和数据目录可按预期读取/写入，其他系统目录不可写；
- 登录、状态恢复及已启用的外部 adapter 正常；
- 日志不包含密码、token、S3 密钥等秘密。

## 4. 升级部署

1. 记录当前软链目标、已批准归档和 SHA-256：

   ```sh
   readlink -f /opt/culvert/current
   sha256sum --check culvert-4.0.0-CURRENT_SHA-linux-x64-jre26.0.2+10.tar.gz.sha256
   ```

2. 安排维护窗口，停止写操作；执行第 6 节停机备份。
3. 下载新 app-image 归档和 `.sha256`，验证摘要与内置 runtime 后，以新的不可变目录名安装到 `releases/`。
4. 确认没有 BB 或第二个 JVM 进程使用同一数据目录：

   ```sh
   sudo systemctl stop culvert.service
   sudo systemctl is-active --quiet culvert.service && exit 1 || true
   pgrep -af 'culvert|babashka|bb'
   ```

   人工核对输出；存在旧实例时不得继续。

5. 确认 `config.json` 中 Docker、SmolVM、ttyd 与 S3 均保持禁用，并确认 `dockers.json`、`smolvm.json` 不含需要恢复的资源；存在相关资源时停止切换并制定迁移方案。
6. 原子切换软链并启动：

   ```sh
   sudo ln -sfn /opt/culvert/releases/NEW-RELEASE /opt/culvert/current.new
   sudo mv -Tf /opt/culvert/current.new /opt/culvert/current
   sudo systemctl start culvert.service
   ```

7. 执行健康检查、登录、关键资源读取和一项可回收的 Forward/Caddy 写入 smoke test；观察日志、内存、线程与子进程。
8. 观察窗口结束前保留上一 app-image release 和切换前备份。

## 5. 回滚

出现无法加载状态、持续启动失败、行为不一致、资源泄漏、关闭失败、数据损坏或重复端口分配时立即停止写入并回滚。

### 5.1 数据格式未变化

```sh
sudo systemctl stop culvert.service
sudo systemctl is-active --quiet culvert.service && exit 1 || true
sudo ln -sfn /opt/culvert/releases/PREVIOUS-RELEASE /opt/culvert/current.new
sudo mv -Tf /opt/culvert/current.new /opt/culvert/current
sudo systemctl start culvert.service
```

执行第 3.6 节验收，并检查孤儿 socat/ttyd/容器、端口、Caddy 配置和挂载。

### 5.2 数据可能已变化或损坏

保持服务停止，先保存故障现场（数据副本、journal、heap dump/崩溃文件），再按第 7 节从切换前备份恢复，然后切回上一 app-image release。恢复期间绝不能启动 BB 或 JVM 写入目标数据目录。

如果回滚到 BB：先停止 JVM 并确认退出，再启动 BB。不得让两种运行时重叠运行；只有确认数据格式兼容时才可让 BB 使用恢复后的目录。

## 6. 备份

JSON 状态由多个文件组成，因此使用**停机备份**获得一致快照。不要在服务写入期间直接打包数据目录。

```sh
sudo systemctl stop culvert.service
sudo systemctl is-active --quiet culvert.service && exit 1 || true
pgrep -af 'culvert|babashka|bb'
sudo tar --create --gzip --numeric-owner --file /var/backups/culvert/culvert-YYYYMMDDTHHMMSSZ.tar.gz \
  /etc/culvert/config.json \
  /etc/culvert/culvert.env \
  /var/lib/culvert/data
sudo sha256sum /var/backups/culvert/culvert-YYYYMMDDTHHMMSSZ.tar.gz | \
  sudo tee /var/backups/culvert/culvert-YYYYMMDDTHHMMSSZ.tar.gz.sha256 >/dev/null
sudo chmod 0600 /var/backups/culvert/culvert-YYYYMMDDTHHMMSSZ.tar.gz*
sudo systemctl start culvert.service
```

人工确认 `pgrep` 没有同目录写入者后再创建归档。将备份及摘要复制到访问受控、加密且与主机故障域隔离的位置；设置保留周期并定期恢复演练。不要把备份提交到仓库或附到公开 issue。

## 7. 恢复

1. 停止所有可能写目标目录的 BB/JVM 实例并人工核对进程。
2. 验证备份摘要：

   ```sh
   sha256sum --check culvert-YYYYMMDDTHHMMSSZ.tar.gz.sha256
   ```

3. 在隔离目录检查清单，防止错误归档覆盖非目标路径：

   ```sh
   mkdir restore-check
   tar --list --file culvert-YYYYMMDDTHHMMSSZ.tar.gz
   tar --extract --gzip --file culvert-YYYYMMDDTHHMMSSZ.tar.gz --directory restore-check
   ```

4. 验证 JSON 可解析、release 与数据格式兼容，并保留当前故障数据副本。
5. 从已检查的隔离目录恢复 `/etc/culvert/config.json`、环境文件和完整数据目录；修复权限：

   ```sh
   sudo chown root:culvert /etc/culvert/config.json /etc/culvert/culvert.env
   sudo chmod 0600 /etc/culvert/config.json
   sudo chmod 0640 /etc/culvert/culvert.env
   sudo chown -R culvert:culvert /var/lib/culvert/data
   sudo chmod 0700 /var/lib/culvert/data
   ```

6. 确认 `current` 指向兼容 app-image release，启动 JVM，并执行第 3.6 节验收及资源 reconciliation。
7. 记录恢复点、摘要、恢复人、结果和发现的问题。

## 8. 常见故障

- **内置 runtime 版本错误**：停止部署，检查归档摘要、CI run、`current/lib/runtime/release` 和 `runtime-build-info`；不要改用系统 Java 或绕过校验。
- **配置读取失败**：检查 `CULVERT_CONFIG_DIR/config.json`、权限和 JSON 格式；不要在 journal 粘贴完整配置。
- **数据不可写**：检查 `CULVERT_DATA_DIR` 所有权以及 unit 的 `ReadWritePaths=`。
- **外部命令不可用**：用服务用户检查绝对路径和最小权限；不要通过改成 root 服务快速绕过。
- **liveness 通过但功能不可用**：检查 `/health/ready`、数据目录和实际启用的 adapter；readiness 目前表示 system 组件启动完成，不替代外部资源逐项 smoke test。
- **停止超时**：保存 journal，检查子进程和挂载；不要在未核对外部资源状态时直接启动第二实例。

## 9. 备用 jar 部署模式

`deploy/bin/culvert-jvm` 继续保留给已有的“外置固定 JDK + uberjar”环境，但不是无依赖生产部署的推荐入口。使用该模式时仍须将 Temurin `26.0.2+10` 安装到不可变目录，并维护 `JAVA_HOME`、`JAVA_BIN`、`CULVERT_JAR` 与 `JAVA_OPTS`；不得让备用实例与 app-image 实例同时写同一 `CULVERT_DATA_DIR`。
