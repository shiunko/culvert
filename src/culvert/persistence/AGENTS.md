# persistence 子树约定

本子树是显式启用的 SQLite 基础设施，未接入 `system/default-dependencies` 启动链；生产状态默认仍是 `src/culvert/db.clj` 的 JSON 路径。在这里工作时不得宣称迁移已完成或 SQLite 已成为默认存储，只能描述为可用的迁移原语。

## 文件职责

| 文件 | 职责 |
|---|---|
| `core.clj` | 生命周期：`start!` 构造 datasource、打开连接、执行 PRAGMA 配置并运行 `migrate!`，任一步失败即关闭连接并重抛；`stop!` 只负责关闭连接 |
| `sqlite.clj` | 连接与事务机制：`datasource`、`with-connection`、`with-transaction`、`migrate!`、`integrity-check`、`backup!`，以及 `SQLiteResourceRepository` 的 SQL 实现 |
| `schema.clj` | 纯数据：`migrations` 向量按版本号声明 DDL 语句，本身不执行任何 SQL |
| `repository.clj` | 资源仓储抽象：`ResourceRepository` 协议、`validate-resource` 校验和 `MemoryResourceRepository` 内存实现，不依赖 JDBC，可在两个运行时直接测试 |
| `reservation.clj` | `port_reservations` 表的领域操作：`reserve!`、`commit!`、`release!`、`expire!`、`list-active` |
| `importer.clj` | JSON 到 SQLite 的一次性导入：`read-json-strict` 读取、`build-plan` 纯校验、`import!` 落库 |

## 测试对应

`test/culvert/persistence/repository_test.clj` 是纯 Clojure 测试，Babashka 与 JVM 都执行。`sqlite_test.clj`、`reservation_test.clj`、`importer_test.clj` 依赖 JDBC，仅 JVM 执行：它们用 `babashka.version` 系统属性探测运行时，在 Babashka 下以占位断言跳过测试体。改动任一文件后必须运行其对应命名空间的测试。

## 连接与事务约定

- `datasource` 通过 SQLiteConfig 启用外键强制和 `busy_timeout = 5000`；`configure-connection!` 在连接上显式执行 `PRAGMA foreign_keys = ON` 与 `PRAGMA busy_timeout = 5000`。
- `with-transaction` 会对事务连接重复执行 PRAGMA 配置，因为这些设置是连接级的；提交与回滚由该宏统一管理，回调内不得自行处理。
- `migrate!` 在单个事务内按序执行未应用的 `schema/migrations` 语句并把版本写入 `migrations` 表，半途失败整体回滚。
- 新增 DDL 只能追加更高版本的 `migrations` 条目，不得改写已发布版本的语句。

## 预约语义

`reserve!` 是全有或全无：任一端口冲突即抛 `::conflict` 并回滚整个请求。事务内先执行过期清理写入，用以串行化并发预约；通配地址 `0.0.0.0` 和 `::` 与任何具体地址互相冲突。`commit!` 只把仍处于 `reserved` 的预约转为 `committed` 并清除过期时间，找不到或已过期返回 nil；`release!` 立即删除预约行；`expire!` 只清理过期且未提交的行。

## 导入安全

`build-plan` 是纯校验：重复用户名、引用了不存在用户的 owner、重复资源键或代理键都会让整个计划失败。`import!` 在非 dry-run 模式下先执行 `migrate!`，再在单个事务内校验 `users`、`resources`、`proxy_rules` 三张表为空，随后插入全部数据并核对行数；任一步失败整体回滚，不留部分导入。`dry-run?` 只读取并返回计划，不触碰数据库。

## 迁移红线

- 不得长期双写 JSON 与 SQLite，也不得把导入后的 SQLite 状态回写旧 JSON 文件。
- 备份使用 `backup!`（`VACUUM INTO` 生成目标路径副本），切换前后用 `integrity-check` 验证数据库完整性。
- 本子树不得演化为万能持久化层：schema 声明、事务机制、仓储协议、端口预约、导入各自独立演进，新增抽象必须减少交织而不是隐藏交织。
