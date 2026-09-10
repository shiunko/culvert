# Culvert

同时支持 Babashka 与 JVM Clojure 的单机控制面，管理端口转发、Caddy 反向代理、Docker/Podman 容器、SmolVM 微型虚拟机、ttyd 终端和 S3/FUSE 挂载，配备基于 Hiccup/HTMX 的管理界面。

## JVM 部署

CI 和生产 JVM 基线为 Eclipse Temurin `26.0.2+10`。构建、SHA-256 校验、非 root systemd 安装、升级、回滚及备份恢复步骤见 [`docs/07-JVM部署与运维.md`](docs/07-JVM部署与运维.md)；可部署模板位于 [`deploy/`](deploy/)。

> **数据安全硬约束：** Babashka（BB）实例与 JVM 实例绝不能同时写同一个 `CULVERT_DATA_DIR`。切换运行时时必须先停止旧实例、确认退出并完成备份，再启动新实例。

## 许可证

[Apache License 2.0](LICENSE)。
