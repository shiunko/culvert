(ns culvert.smolvm.cli
  "SmolVM 二进制文件的 CLI 适配器，封装 machine 与 pack 命令。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [culvert.config :refer [config]]
            [culvert.log :as log]
            [culvert.runtime.process :as process]))

;; ============================================================
;; SmolVM CLI 适配器
;; ============================================================

(def smolvm-path (:smolvm-path config))

;; --- 内部辅助函数 ---

(defn- exec-cmd [& args]
  (let [res (process/run (into [smolvm-path] (map str) args))]
    (if (zero? (:exit res))
      (str/trim (:out res))
      (let [stderr (str/trim (:err res))
            msg (if (str/blank? stderr) (:out res) stderr)]
        (throw (Exception. (str "[smolvm] " (str/trim msg))))))))

(defn- exec-check [& args]
  (let [res (process/run (into [smolvm-path] (map str) args))]
    (when-not (zero? (:exit res))
      (let [stderr (str/trim (:err res))
            msg (if (str/blank? stderr) (:out res) stderr)]
        (throw (Exception. (str "[smolvm] " (str/trim msg))))))))

;; --- 可用性探测 ---

(defn available? []
  (try
    (let [res (process/run [smolvm-path "--version"])]
      (zero? (:exit res)))
    (catch Exception _
      false)))

(defn get-version []
  (try
    (exec-cmd "--version")
    (catch Exception _
      "unknown")))

;; --- VM 生命周期 ---

(defn create-machine!
  "创建持久化 SmolVM。opts 参数：
   :image       — OCI 镜像字符串
   :name        — VM 名称（必填）
   :net         — 是否启用网络
   :gpu         — 是否启用 GPU
   :volumes     — \"HOST:GUEST\" 或 \"HOST:GUEST:ro\" 字符串向量
   :ports       — \"HOST:GUEST\" 字符串向量（SmolVM -p 端口映射）
   :ssh-agent   — 是否转发宿主机 SSH agent
   :allow-hosts — 出站过滤主机名向量
   :allow-cidrs — 出站过滤 CIDR 字符串向量
   :smolfile    — 可选 Smolfile 路径
   返回 VM 名称。"
  [{:keys [image name net gpu volumes ports ssh-agent allow-hosts allow-cidrs smolfile]
    :or {volumes [] ports [] allow-hosts [] allow-cidrs []}}]
  (log/info (format "创建 VM: %s (image=%s)" name image))
  (let [args (cond-> ["machine" "create" name]
               image      (conj "--image" image)
               net        (conj "--net")
               gpu        (conj "--gpu")
               ssh-agent  (conj "--ssh-agent")
               smolfile   (conj "--smolfile" smolfile)
               true       (into (mapcat (fn [v] ["-v" v]) volumes))
               true       (into (mapcat (fn [p] ["-p" p]) ports))
               true       (into (mapcat (fn [h] ["--allow-host" h]) allow-hosts))
               true       (into (mapcat (fn [c] ["--allow-cidr" c]) allow-cidrs)))]
    (apply exec-cmd args)
    name))

(defn start-machine!
  "启动已停止的 VM。"
  [name]
  (log/info (format "启动 VM: %s" name))
  (exec-check "machine" "start" "--name" name))

(defn stop-machine!
  "停止运行中的 VM。"
  [name]
  (log/info (format "停止 VM: %s" name))
  (exec-check "machine" "stop" "--name" name))

(defn delete-machine!
  "强制删除 VM。"
  [name]
  (log/info (format "删除 VM: %s" name))
  (try
    (exec-check "machine" "delete" name "-f")
    (catch Exception e
      (log/warn (format "删除 VM 时出错 (可能已不存在): %s" (.getMessage e))))))

;; --- VM 状态查询 ---

(defn machine-running-strict?
  "严格查询 VM 是否运行；命令失败时传播异常。"
  [name]
  (let [output (exec-cmd "machine" "status" "--name" name)]
    (str/includes? (str/lower-case output) "running")))

(defn machine-running?
  "查询 VM 是否运行；命令失败时返回 false。"
  [name]
  (try
    (machine-running-strict? name)
    (catch Exception _
      false)))

(defn list-machines
  "列出所有 VM，返回从 JSON 输出解析的映射向量。"
  []
  (try
    (let [output (exec-cmd "machine" "ls" "--json")]
      (if (str/blank? output)
        []
        (let [data (try
                     (let [parsed (json/parse-string output true)]
                       (if (vector? parsed) parsed [parsed]))
                     (catch Exception _ []))]
          data)))
    (catch Exception e
      (log/warn (format "列出 VM 失败: %s" (.getMessage e)))
      [])))

;; --- VM 操作 ---

(defn update-machine!
  "更新已停止 VM 的设置。opts 与 create-machine! 类似，仅更新提供的键。"
  [name opts]
  (log/info (format "更新 VM 配置: %s" name))
  (let [{:keys [volumes ports]
         :or {volumes [] ports []}} opts
        args (cond-> ["machine" "update" name]
               true (into (mapcat (fn [v] ["-v" v]) volumes))
               true (into (mapcat (fn [p] ["-p" p]) ports)))]
    (apply exec-check args)))

(defn exec-command
  "在运行中的 VM 内执行命令并返回标准输出。"
  [name command]
  (exec-cmd "machine" "exec" "--name" name "--" command))

(defn get-ttyd-exec-args
  "返回 ttyd 连接 VM shell 所需的参数向量。"
  [name shell-path]
  [smolvm-path "machine" "exec" "--name" name "--" shell-path])

;; --- 打包 ---

(defn pack-machine!
  "将 VM 打包为可移植可执行文件并返回输出路径。"
  [name output-path]
  (log/info (format "打包 VM: %s → %s" name output-path))
  (exec-check "pack" "create" "--from-vm" name "-o" output-path)
  output-path)
