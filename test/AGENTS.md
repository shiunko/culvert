# test/ 测试树说明

本目录是 `culvert` 的独立验证边界，现有 27 个 Clojure 文件：26 个 `*_test.clj` 测试命名空间，加上 `culvert/runner.clj` 本身。全部测试基于 `clojure.test`，以行为验证为目标，不与实现细节绑定。

## 测试发现与运行

- `culvert.runner` 从仓库根目录递归扫描 `test/` 下所有 `*_test.clj` 文件，把路径转换为命名空间、排序后逐一 `require` 并运行；因此必须从仓库根目录启动，且任何测试都不得依赖执行顺序。
- runner 以 `fail` 与 `error` 计数之和作为进程退出码，供 CI 判定成败。
- Babashka 侧：`bb test` 运行全部测试；`bb test-ns culvert.web-test` 只运行单个命名空间。
- JVM 侧：`clojure -M:test` 走 `deps.edn` 的 `:test` 别名，入口同样是 `culvert.runner`。
- 新增测试必须落在 `test/` 内并以 `_test.clj` 结尾，否则不会被自动发现。

## 目录镜像与 Web 例外

测试文件按 `src/culvert/` 的命名空间结构镜像放置，如 `src/culvert/persistence/` 对应 `test/culvert/persistence/`。唯一的例外是 Web 子树：源码拆分在 `src/culvert/web/` 多个命名空间中，测试却是顶层的 `web_test.clj` 与 `web_ws_test.clj`，修改 Web 逻辑时两个文件都要核对。

## 隔离与清理约定

- 临时目录：需要落盘的测试用 `Files/createTempDirectory` 创建目录，并在 `finally` 中逆序删除整棵树，参考 `db_test.clj`、`config_test.clj`。
- 全局 atom：触及 `docker/state`、`smolvm/state` 等全局状态的测试，先用 `let` 保存原值，`finally` 中 `reset!` 还原，不得让残留状态泄漏到其他测试。
- 外部副作用：Docker、Caddy、iptables 等外部命令一律用 `with-redefs` 桩替换，不真正调用外部进程，参考 `caddy_test.clj`、`forward_test.clj`。
- 每个测试自管准备与清理；遗留的 `test-*.json`、`*.tmp` 临时文件可用 `bb clean` 清除。

## 断言风格

用 `testing` 描述行为场景，`is` 做最小断言。错误路径通过 `ex-data` 断言稳定的 `:type` 关键字（如 `::db/corrupt-json`、`::process/timeout`），不断言异常消息文本。

## JVM 专属持久化测试

`persistence/sqlite_test.clj`、`reservation_test.clj` 与 `importer_test.clj` 依赖 JDBC，只在 JVM 上运行：以 `(System/getProperty "babashka.version")` 判定运行时，用 `requiring-resolve` 在运行期解析 JVM 专属 var；Babashka 下各测试以成功断言显式跳过，不报失败。

## 刻意的进程适配器例外

`runtime/process_test.clj` 是本树唯一真实执行外部进程的测试（`sh -c` 子进程），因为它验证的对象就是进程适配器 `culvert.runtime.process`：退出码结构、超时语义、argv 向量校验与流式输出。不要把其他测试改成这种直接执行外部命令的风格。

## 当前覆盖边界

- 没有覆盖率门槛，通过与否只看 `fail` 与 `error` 是否为零。
- 没有集成测试别名；本树全部是行为级单元测试，不得将其称为集成测试。
- 与真实 Docker、Caddy、SmolVM、S3、iptables 的端到端集成尚未被证明，相关外部交互目前均由桩替换。
