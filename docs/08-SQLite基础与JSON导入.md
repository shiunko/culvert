# 阶段 5：SQLite 基础与 JSON 只读导入

## 1. 当前边界

本阶段只建立 SQLite 基础能力，**没有切换生产持久化路径**。

- 现有 `culvert.db/write-json` 及其调用点保持不变；
- `culvert.persistence.*` 不被现有 system/runtime 自动启动；
- 不存在 JSON 与 SQLite 双写；
- SQLite 初始化和 JSON 导入必须由维护流程显式调用；
- SQLite JDBC 测试仅在 JVM 运行，纯 schema/protocol/内存 repository 可由 Babashka 加载。

## 2. 固定依赖

`deps.edn` 固定使用：

- `org.xerial/sqlite-jdbc` `3.50.3.0`；
- `com.github.seancorfield/next.jdbc` `1.3.1070`。

## 3. Schema

迁移由 `culvert.persistence.schema/migrations` 定义，
`culvert.persistence.sqlite/migrate!` 在同一事务内按版本执行。
`migrations` 表记录版本、名称和应用时间，重复执行不会重复应用。

初始 schema 包含：

| 表 | 关键约束与索引 |
|---|---|
| `migrations` | `version` 主键，`name` 唯一 |
| `users` | 大小写不敏感用户名主键，角色与 auth version 检查 |
| `sessions` | session 主键、用户外键、用户及过期时间索引 |
| `resources` | `(resource_type, resource_key)` 唯一，owner/type 和 state 索引 |
| `port_reservations` | `(protocol, port)` 唯一，端口范围、协议、状态检查，owner/status 和 TTL 索引 |
| `proxy_rules` | `(domain, worker)` 大小写不敏感唯一，owner 索引 |
| `resource_events` | 资源外键，resource/time 和 event type 索引 |

所有 datasource 连接默认启用 SQLite foreign keys 和 5 秒 busy timeout。

## 4. API

### 4.1 生命周期与事务

```clojure
(require '[culvert.persistence.core :as persistence]
         '[culvert.persistence.sqlite :as sqlite])

(def handle (persistence/start! "/var/lib/culvert/state.sqlite"))
;; 使用 (:datasource handle) 或 (:connection handle)
(persistence/stop! handle)

(sqlite/with-transaction datasource
  (fn [transaction]
    ;; transaction 内的 next.jdbc/repository 操作
    ))
```

也可使用 `open-connection`、`close-connection!` 或 `with-connection` 管理连接。
连接由创建它的调用方关闭；`core/start!` 返回的连接由 `core/stop!` 关闭。

### 4.2 Resource repository

`culvert.persistence.repository/ResourceRepository` 提供最小 CRUD：

- `create-resource!`
- `get-resource`
- `list-resources`
- `update-resource!`
- `delete-resource!`

`memory-repository` 用于轻量测试；`sqlite-repository` 接受 datasource 或事务连接。
资源身份字段 `id`、`owner`、`resource-type`、`resource-key` 创建后不可变。

## 5. JSON 只读导入

导入器只调用 `slurp` 读取 JSON，不调用 `spit` 或现有 `write-json`，因此不会修改源文件，也不会形成双写。

```clojure
(require '[culvert.persistence.importer :as importer]
         '[culvert.persistence.sqlite :as sqlite])

(def sources
  {:users "/backup/users.json"
   :resources
   {:port-forward "/backup/ports.json"
    :container {:path "/backup/dockers.json" :root-key :containers}
    :smolvm {:path "/backup/smolvm.json" :root-key :machines}}
   :proxies "/backup/caddy.json"})

;; 严格解析和校验，不创建数据库文件
(importer/import! (sqlite/datasource "/tmp/state.sqlite")
                  sources {:dry-run? true})

;; 正式导入；目标 users/resources/proxy_rules 必须为空
(importer/import! (sqlite/datasource "/var/lib/culvert/state.sqlite")
                  sources {:dry-run? false})
```

导入在写入前完成以下检查：

1. 文件必须存在且是普通文件；
2. JSON 必须严格解析，损坏数据不会被当作空数据；
3. users、资源集合和代理集合必须是 JSON object；
4. 用户名、密码、资源 owner、代理 target 和唯一键不得为空；
5. 每个 owner 必须存在于待导入 users；
6. `(resource-type, resource-key)` 与 `(domain, worker)` 不得重复；
7. 导入目标必须为空；
8. 整批写入处于同一事务，最终表数量必须与计划一致，否则回滚。

`dry-run` 返回规范化计划和 `counts`，且不会执行 migration 或打开 SQLite 文件。

## 6. 备份与完整性检查

```clojure
(sqlite/integrity-check datasource)
;; => {:ok? true, :messages ["ok"]}

(sqlite/backup! datasource "/backup/culvert-20260903.sqlite")
;; 使用 SQLite VACUUM INTO 创建一致备份；目标文件必须不存在。
```

建议备份流程：

1. 保持当前 JSON 生产写路径不变；
2. 停止服务或进入维护窗口，确保 JSON 停写；
3. 备份全部 JSON；
4. 对 SQLite 执行 `backup!`（若已有预演库）；
5. dry-run 导入并核对 counts；
6. 正式导入；
7. 执行 `integrity-check` 并再次核对表数量；
8. 在后续独立变更中切换读取/写入路径。

本阶段不提供 SQLite 到 JSON 的反向写入。回滚方式是停止新实例并恢复迁移前 JSON/SQLite 备份，禁止把迁移后的 SQLite 状态直接双写回旧 JSON。
