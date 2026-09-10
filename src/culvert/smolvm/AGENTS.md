# SmolVM 域约定

SmolVM 域负责微虚机的生命周期、端口暴露、ttyd 终端接入与 Caddy 反代，代码位于 `src/culvert/smolvm/`。

## 文件职责

- `cli.clj`：外部命令适配器。封装 smolvm 二进制的全部 `machine`/`pack` 子命令，经 `culvert.runtime.process` 以 argv 向量执行（`exec-cmd`/`exec-check`，非零退出即抛异常），自身不持有状态。`get-ttyd-exec-args` 只返回 ttyd 附着终端所需的 argv，不负责执行。
- `manager.clj`：状态与编排。持有唯一的 `state` atom（`:machines`、`:used-ports`、`:ttyd-procs`），负责 CRUD、端口分配、ttyd 进程、Caddy 规则、JSON 持久化与重启恢复。要变更资源的代码必须走 manager 入口，不得绕过它直接调用 CLI。

## 生命周期不变量

- `create-machine!` 按序执行：镜像白名单 → 配额校验 → `allocate-port` → CLI create/start → ttyd → Caddy 规则 → 持久化；任一步失败必须回滚（删除虚机、`release-port`），不得留下半成品记录。
- `kill-machine!` 清理顺序固定：停 ttyd → 移除 shell 与端口的 Caddy 规则 → 删虚机 → 释放 `shellPort` 与全部 `smolvmPort` → 移除记录并 `save-entries!`。
- `expose-port!`/`unexpose-port!`/`toggle-port!` 修改 `-p` 映射必须走 stop → `update-machine!` → start 循环（IP 模式；域名模式下 `toggle-port!` 只切 Caddy 规则）；失败回滚时先释放新分配的端口，再尝试拉起虚机并重新抛出异常。

## 端口、ttyd 与 Caddy

- 端口取自 `:smolvm-port-start`/`:smolvm-port-end` 配置池，分配前用 `ServerSocket` 探测可用性；每次分配必须与 `release-port` 对称，防止池泄漏。
- `start-ttyd!` 附带守护 watcher 线程：ttyd 意外退出时清理 `:ttyd-procs`、将状态置为 `error` 并 `save-entries!`，不得静默吞掉。
- Caddy 规则区分 `:ip-mode`（`public-ip:port` 直连）与域名模式（`caddy/add-rule!` 反代到 127.0.0.1）；两种模式的启停与 toggle 语义不同，改动时两边都要核对。

## 状态恢复与授权

- `load-entries!`/`save-entries!` 是 JSON 持久化唯一入口；`sync-machines!` 在重启后恢复 ttyd、shell 与端口 Caddy 规则，单台恢复失败只记 warn，不中断其余虚机。
- 所有 mutation 入口必须经 `require-machine-access!`、查询入口经 `authorization/authorized?` 校验 actor 与所有权（兼容 `legacy-user`）；Handler 不是授权边界。

## 测试约定

`smolvm_test.clj` 只写纯单元测试，不依赖 smolvm 二进制：以 save/restore 包裹 `state` atom 保证隔离，用 `with-redefs` 替换 CLI 函数与 `save-entries!`，并断言被拒绝的 mutation（如 `expose-port!`）不产生任何副作用。二进制集成测试目前不存在，不要声称已有。

## 复杂度与抽象边界

- `manager.clj` 约 694 行，是复杂度热点：新增策略应抽成纯函数（参照 `sanitize-machine-name`、`parse-mem-bytes`）或显式适配器，不要继续在既有函数里堆叠分支。
- `docker.clj` 与本域在凭据生成、内存解析等模式上相似，但运行时语义并不相同；除非两个真实实现的职责与语义完全一致，否则不得抽取通用 runtime 抽象，更不得合并两者的领域语义。
