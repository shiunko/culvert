(ns culvert.cli
  (:require [clojure.string :as str]
            [culvert.config :refer [config]]
            [culvert.log :as log]
            [culvert.runtime.process :as process]))

;; ============================================================
;; Container Runtime CLI Adapter (Docker/Podman/Nerdctl)
;; ============================================================

(def cli-path (:container-cli-path config))
(def cli-name (:container-cli config))

(defn- exec-cmd [& args]
  (let [res (process/run (into [cli-path] (map str) args))]
    (if (zero? (:exit res))
      (str/trim (:out res))
      (let [stderr (str/trim (:err res))
            msg (if (str/blank? stderr) (:out res) stderr)]
        (throw (Exception. (str "[" cli-name "] " (str/trim msg))))))))

;; --- Availability & Capabilities ---

(defn available? []
  (try
    (let [res (process/run [cli-path "version"])]
      (zero? (:exit res)))
    (catch Exception _
      false)))

(defn get-version []
  (try
    (exec-cmd "version" "--format" "{{.Client.Version}}")
    (catch Exception _
      (try
        (let [out (exec-cmd "version")]
          (or (second (re-find #"Version:\s+([^\s\n]+)" out)) "unknown"))
        (catch Exception _
          "unknown")))))

(defn rootless? []
  (if-not (= cli-name "podman")
    false
    (try
      (let [info (exec-cmd "info" "--format" "{{.Host.Security.Rootless}}")]
        (= (str/lower-case info) "true"))
      (catch Exception _
        false))))

(defn can-access-container-ip? []
  (not (rootless?)))

;; --- 容器管理 ---

(defn image-exists? [image]
  (let [res (process/run [cli-path "image" "inspect" image "--format" "{{.Id}}"])]
    (zero? (:exit res))))

(defn pull-image [image]
  (if (image-exists? image)
    (do
      (log/info (format "镜像已存在，跳过拉取: %s" image))
      true)
    (do
      (log/info (format "拉取镜像: %s" image))
      (let [res (process/run [cli-path "pull" image])]
        (if (zero? (:exit res))
          (do
            (log/info (format "镜像拉取完成: %s" image))
            true)
          (throw (Exception. (str "镜像拉取失败: " (str/trim (:err res))))))))))

(defn run-container
  "Spawns a container. opts contains :image, :name, :cpus, :memory, :network, :command, :publish (map of hostPort->containerPort), :volumes (map of hostPath->containerPath), :gpu (enable GPU with --gpus all)"
  [{:keys [image name cpus memory network command publish volumes gpu]
    :or {command ["sleep" "infinity"]
         network "bridge"}}]
  (let [args (cond-> ["run" "-d" "--rm"]
               name (conj "--name" name)
               cpus (conj "--cpus" cpus)
               memory (conj "--memory" memory)
               network (conj "--network" network)
               gpu (conj "--gpus" "all"))
        ;; Add port publications
        ports-args (mapcat (fn [[hp cp]] ["-p" (str hp ":" cp)]) publish)
        vol-args (mapcat (fn [[hp cp]] ["-v" (str hp ":" cp ":shared")]) volumes)
        final-args (concat args ports-args vol-args [image] command)]
    (log/info (format "创建并运行容器: %s" (or name image)))
    (apply exec-cmd final-args)))

(defn kill-container [container-name-or-id]
  (log/info (format "终止容器: %s" container-name-or-id))
  (try
    (exec-cmd "kill" container-name-or-id)
    (catch Exception e
      (log/warn (format "终止容器时出错 (可能已不存在): %s" (.getMessage e))))))

(defn inspect-container [container-name-or-id format-str]
  (exec-cmd "inspect" "--format" format-str container-name-or-id))

(defn get-container-ip-strict
  "严格查询容器 IP；CLI 或 inspect 异常由调用方处理。"
  [container-name-or-id]
  (inspect-container container-name-or-id
                     "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}"))

(defn get-container-ip [container-name-or-id]
  (try
    (get-container-ip-strict container-name-or-id)
    (catch Exception e
      (log/warn (format "获取容器 IP 失败: %s" (.getMessage e)))
      "")))

(defn container-running-strict?
  "严格查询容器是否运行；CLI 或 inspect 异常由调用方处理。"
  [container-name-or-id]
  (= "running"
     (inspect-container container-name-or-id "{{.State.Status}}")))

(defn container-running? [container-name-or-id]
  (try
    (container-running-strict? container-name-or-id)
    (catch Exception _
      false)))

(defn start-container [container-name-or-id]
  (log/info (format "启动容器: %s" container-name-or-id))
  (exec-cmd "start" container-name-or-id))

(defn get-container-info
  "Returns container details from docker inspect for adopt purpose."
  [container-name-or-id]
  (try
    (let [image (inspect-container container-name-or-id "{{.Config.Image}}")
          entrypoint (inspect-container container-name-or-id "{{range .Config.Entrypoint}}{{.}} {{end}}")
          cmd (inspect-container container-name-or-id "{{range .Config.Cmd}}{{.}} {{end}}")]
      {:image (str/trim image)
       :entrypoint (str/trim entrypoint)
       :cmd (str/trim cmd)})
    (catch Exception e
      (log/warn (format "获取容器信息失败: %s" (.getMessage e)))
      {:image "unknown"})))

(defn list-containers [& [filter-str]]
  (let [args (cond-> ["ps" "-a" "--no-trunc" "--format" "{{.ID}}|{{.Names}}|{{.Image}}|{{.State}}|{{.Status}}"]
               filter-str (conj "--filter" filter-str))]
    (try
      (let [output (apply exec-cmd args)]
        (if (str/blank? output)
          []
          (map (fn [line]
                 (let [[id name img state status] (str/split line #"\|")]
                   {:id id :name name :image img :state state :status status}))
               (str/split-lines output))))
      (catch Exception e
        (log/warn (format "列出容器失败: %s" (.getMessage e)))
        []))))

(defn get-container-logs
  "Returns recent container logs. opts supports :tail (default 200) and :since (e.g. '10m')."
  [container-name-or-id & [opts]]
  (let [tail (or (:tail opts) 200)]
    (try
      (let [args (cond-> ["logs" "--tail" (str tail) "--timestamps"]
                   (:since opts) (conj "--since" (:since opts))
                   true (conj container-name-or-id))
            res (process/run (into [cli-path] (map str) args))]
        (if (zero? (:exit res))
          (or (str/trim (:out res)) (str/trim (:err res)) "(无日志输出)")
          (let [stderr (str/trim (:err res))]
            (str "获取日志失败: " (if (str/blank? stderr) (:out res) stderr)))))
      (catch Exception e
        (str "获取日志异常: " (.getMessage e))))))

(defn get-ttyd-exec-args [container-name shell-path]
  [cli-path "exec" "-it" container-name shell-path])

;; --- Log Streaming Support (for real-time log tail via SSE) ---

(defn stream-container-logs
  "Returns a process adapter handle for streaming logs via 'docker logs -f'.
   The caller is responsible for reading from :out and terminating the process."
  [container-name-or-id & [opts]]
  (let [tail (or (:tail opts) 200)
        args (cond-> ["logs" "--tail" (str tail) "--timestamps" "-f"]
               (:since opts) (conj "--since" (:since opts))
               true (conj container-name-or-id))]
    (try
      (process/spawn (into [cli-path] (map str) args) {:out :stream :err :string})
      (catch Exception e
        (log/error (format "启动日志流失败: %s" (.getMessage e)))
        nil))))

;; --- GPU Support ---

(defn gpu-available?
  "Returns true if NVIDIA GPU is available (nvidia-smi exists and reports GPUs)."
  []
  (try
    (let [res (process/run ["nvidia-smi" "-L"])]
      (and (zero? (:exit res))
           (not (str/blank? (str/trim (:out res))))))
    (catch Exception _
      false)))

(defn gpu-info
  "Returns GPU information string from nvidia-smi, or nil if unavailable."
  []
  (try
    (let [res (process/run ["nvidia-smi" "--query-gpu=name,memory.total" "--format=csv,noheader"])]
      (when (zero? (:exit res))
        (let [info (str/trim (:out res))]
          (when (not (str/blank? info))
            info))))
    (catch Exception _
      nil)))

(defn get-container-stats
  "Returns a map of {container-name {:cpu-pct double, :mem-pct double, :mem-used str, :mem-limit str}}.
   Calls `docker stats --no-stream` once for all running containers."
  []
  (try
    (let [output (exec-cmd "stats" "--no-stream" "--format"
                           "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}")]
      (if (str/blank? output)
        {}
        (into {}
              (for [line (str/split-lines output)
                    :let [parts (str/split line #"\|")]
                    :when (= (count parts) 4)]
                (let [[name cpu-pct mem-usage mem-pct] parts
                      cpu-val (try (Double/parseDouble (str/replace cpu-pct #"%" ""))
                                   (catch Exception _ 0.0))
                      mem-val (try (Double/parseDouble (str/replace mem-pct #"%" ""))
                                   (catch Exception _ 0.0))
                      mem-parts (str/split mem-usage #" / ")
                      mem-used (or (first mem-parts) "")
                      mem-limit (or (second mem-parts) "")]
                  [name {:cpu-pct cpu-val
                         :mem-pct mem-val
                         :mem-used mem-used
                         :mem-limit mem-limit}])))))
    (catch Exception _
      {})))
