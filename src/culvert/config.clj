(ns culvert.config
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [culvert.runtime.process :as process]))

(s/def ::port (s/or :int pos-int? :str (s/and string? #(re-matches #"^\d+$" %))))
(s/def ::publicIp string?)
(s/def ::listenAddress string?)
(s/def ::authUser string?)
(s/def ::authPass string?)
(s/def ::containerCli #{"docker" "podman" "nerdctl"})
(s/def ::caddyDomains (s/coll-of string? :kind vector?))
(s/def ::defaultShell string?)
(s/def ::defaultCpuLimit string?)
(s/def ::defaultMemLimit string?)
(s/def ::maxCpuLimit string?)
(s/def ::maxMemLimit string?)
(s/def ::allowedImages (s/coll-of string? :kind vector?))
(s/def ::socatPath string?)
(s/def ::iptablesPath string?)
(s/def ::caddyPath string?)
(s/def ::ttydPath string?)
(s/def ::ttydEnabled boolean?)
(s/def ::dockerMutationsEnabled boolean?)
(s/def ::smolvmMutationsEnabled boolean?)
(s/def ::containerCliPath string?)
(s/def ::start pos-int?)
(s/def ::end pos-int?)
(s/def ::ttydPortRange map?)
(s/def ::ttydMaxClients int?)
(s/def ::portForwardRange map?)
(s/def ::defaultQuota map?)
(s/def ::s3 map?)
(s/def ::smolvmPath string?)
(s/def ::smolvmDefaultShell string?)
(s/def ::smolvmDefaultCpu string?)
(s/def ::smolvmDefaultMem string?)
(s/def ::smolvmPortRange map?)

(s/def ::config-raw
  (s/keys :req-un [::port ::publicIp]
          :opt-un [::listenAddress ::authUser ::authPass ::containerCli ::caddyDomains
                   ::defaultShell ::defaultCpuLimit ::defaultMemLimit
                   ::maxCpuLimit ::maxMemLimit ::allowedImages
                   ::socatPath ::iptablesPath ::caddyPath ::ttydPath ::ttydEnabled
                   ::dockerMutationsEnabled ::smolvmMutationsEnabled ::containerCliPath
                   ::ttydPortRange ::ttydMaxClients ::portForwardRange ::defaultQuota ::s3
                   ::smolvmPath ::smolvmDefaultShell ::smolvmDefaultCpu
                   ::smolvmDefaultMem ::smolvmPortRange]))

(def ^:private default-raw-config
  {:port 10092
   :publicIp "127.0.0.1"
   :listenAddress "127.0.0.1"
   :caddyDomains []})

(defn resolve-paths
  "从环境变量解析配置和可变数据路径。两个目录相互独立且默认均为 data；
  本函数不读取文件系统。"
  ([] (resolve-paths (System/getenv)))
  ([env]
   (let [configured-config-dir (not-empty (get env "CULVERT_CONFIG_DIR"))
         configured-data-dir (not-empty (get env "CULVERT_DATA_DIR"))
         config-dir (io/file (or configured-config-dir "data"))
         data-dir (io/file (or configured-data-dir "data"))
         absolute #(.getAbsolutePath (io/file %))]
     {:enforce-sensitive-permissions (boolean (or configured-config-dir configured-data-dir))
      :config-dir (absolute config-dir)
      :data-dir (absolute data-dir)
      :config-file (absolute (io/file config-dir "config.json"))
      :images-file (absolute (io/file config-dir "images.json"))
      :smolvm-images-file (absolute (io/file config-dir "smolvm.images.json"))
      :caddyfile-path (absolute (io/file data-dir "Caddyfile"))
      :db-path (absolute (io/file data-dir "ports.json"))
      :caddy-db-path (absolute (io/file data-dir "caddy.json"))
      :docker-db-path (absolute (io/file data-dir "dockers.json"))
      :users-db-path (absolute (io/file data-dir "users.json"))
      :smolvm-db-path (absolute (io/file data-dir "smolvm.json"))})))

(defn read-config
  "读取并解析 JSON 配置文件；本函数不执行校验或退出进程。"
  ([] (read-config (:config-file (resolve-paths))))
  ([file]
   (try
     (json/parse-string (slurp (io/file file)) true)
     (catch Exception e
       (throw (ex-info (format "无法读取配置文件 %s: %s" file (.getMessage e))
                       {:type ::config-read-error :file (str file)}
                       e))))))

(defn- unsafe-bootstrap-credential? [raw]
  (let [user (some-> (:authUser raw) str/trim str/lower-case)
        pass (some-> (:authPass raw) str/trim str/lower-case)]
    (or (= user "change_me")
        (= pass "admin123")
        (and pass (str/starts-with? pass "change_me")))))

(defn validate-config
  "返回校验通过的原始配置，否则抛出 ex-info。占位启动凭据会在创建首个管理员前被拒绝。"
  [raw]
  (let [spec-valid? (s/valid? ::config-raw raw)
        unsafe-credentials? (unsafe-bootstrap-credential? raw)
        problems (cond-> []
                   (not spec-valid?) (conj (s/explain-str ::config-raw raw))
                   unsafe-credentials? (conj "authUser/authPass 不能使用 CHANGE_ME 或 admin123 等默认凭据"))]
    (if (empty? problems)
      raw
      (throw (ex-info (str "config.json 校验失败:\n" (str/join "\n" problems))
                      {:type ::invalid-config
                       :problems problems})))))

(defn- read-image-list [file]
  (try
    (let [data (json/parse-string (slurp (io/file file)) true)]
      (if (vector? (:images data)) (:images data) []))
    (catch Exception _ [])))

(defn- configured-command [explicit-path command-name]
  (if (str/blank? explicit-path) command-name explicit-path))

(defn- normalize-domains [raw]
  (let [domains (:caddyDomains raw)]
    (if (vector? domains)
      (vec (sort (distinct (remove str/blank? domains))))
      [])))

(defn build-config
  "构建应用配置映射，不探测外部命令。"
  ([raw paths] (build-config raw paths [] []))
  ([raw paths images smolvm-images]
   (let [images-map (into {} (map (juxt :name identity) images))
         smolvm-images-map (into {} (map (juxt :name identity) smolvm-images))
         configured-allowed (:allowedImages raw)
         allowed-images (if (and (vector? configured-allowed) (seq configured-allowed))
                          (vec (filter #(contains? images-map %) configured-allowed))
                          (vec (map :name images)))
         container-cli (or (:containerCli raw) "docker")
         domains (normalize-domains raw)
         raw-s3 (or (:s3 raw) {})]
     {:port (if (string? (:port raw)) (Integer/parseInt (:port raw)) (or (:port raw) 10092))
      :listen-address (or (:listenAddress raw) "127.0.0.1")
      :public-ip (:publicIp raw)
      :auth-user (:authUser raw)
      :auth-pass (:authPass raw)
      :config-dir (:config-dir paths)
      :data-dir (:data-dir paths)
      :config-file (:config-file paths)
      :enforce-sensitive-permissions (:enforce-sensitive-permissions paths)
      :socat-path (configured-command (:socatPath raw) "socat")
      :iptables-path (configured-command (:iptablesPath raw) "iptables")
      :caddy-path (configured-command (:caddyPath raw) "caddy")
      :caddyfile-path (:caddyfile-path paths)
      :caddy-domains domains
      :caddy-domain (first domains)
      :ip-mode (or (empty? domains)
                   (every? #(re-matches #"^\d{1,3}(\.\d{1,3}){3}$" %) domains))
      :db-path (:db-path paths)
      :caddy-db-path (:caddy-db-path paths)
      :docker-db-path (:docker-db-path paths)
      :users-db-path (:users-db-path paths)
      :socat-max-restarts 5
      :socat-restart-base-delay-ms 1000
      :container-cli container-cli
      :container-cli-path (configured-command (:containerCliPath raw) container-cli)
      :docker-mutations-enabled (true? (:dockerMutationsEnabled raw))
      :smolvm-mutations-enabled (true? (:smolvmMutationsEnabled raw))
      :ttyd-path (configured-command (:ttydPath raw) "ttyd")
      :ttyd-enabled (true? (:ttydEnabled raw))
      :ttyd-port-start (or (get-in raw [:ttydPortRange :start]) 17680)
      :ttyd-port-end (or (get-in raw [:ttydPortRange :end]) 17999)
      :ttyd-max-clients (let [v (:ttydMaxClients raw)] (if (nil? v) 1 (int v)))
      :default-shell (or (:defaultShell raw) "/bin/bash")
      :default-cpu-limit (or (:defaultCpuLimit raw) "2")
      :default-mem-limit (or (:defaultMemLimit raw) "512m")
      :max-cpu-limit (or (:maxCpuLimit raw) "8")
      :max-mem-limit (or (:maxMemLimit raw) "4g")
      :allowed-images allowed-images
      :images-config images
      :images-map images-map
      :port-forward-range (or (:portForwardRange raw) {:start 10000 :end 65535})
      :default-quota (or (:defaultQuota raw)
                         {:portForwards 5 :reverseProxies 3 :containers 2
                          :cpuLimit 8 :memLimit "8g"})
      :s3 {:enabled (true? (:enabled raw-s3))
           :fs-type (or (:fsType raw-s3) "goofys")
           :fs-path (configured-command (:fsPath raw-s3) (or (:fsType raw-s3) "goofys"))
           :host-mount-root (or (:hostMountRoot raw-s3) "/mnt/s3")
           :container-mount-path (or (:containerMountPath raw-s3) "/mnt/data")
           :mount-timeout-sec (if (string? (:mountTimeoutSec raw-s3))
                                (Integer/parseInt (:mountTimeoutSec raw-s3))
                                (or (:mountTimeoutSec raw-s3) 15))
           :default-options {:dir-mode (get-in raw-s3 [:defaultOptions :dirMode] "0755")
                             :file-mode (get-in raw-s3 [:defaultOptions :fileMode] "0644")
                             :uid (get-in raw-s3 [:defaultOptions :uid] "1000")
                             :gid (get-in raw-s3 [:defaultOptions :gid] "1000")}}
      :smolvm-path (configured-command (:smolvmPath raw) "smolvm")
      :smolvm-available? false
      :smolvm-default-shell (or (:smolvmDefaultShell raw) "/bin/sh")
      :smolvm-default-cpu (or (:smolvmDefaultCpu raw) "4")
      :smolvm-default-mem (or (:smolvmDefaultMem raw) "1024")
      :smolvm-port-start (or (get-in raw [:smolvmPortRange :start]) 30000)
      :smolvm-port-end (or (get-in raw [:smolvmPortRange :end]) 31999)
      :smolvm-db-path (:smolvm-db-path paths)
      :smolvm-images-config smolvm-images
      :smolvm-images-map smolvm-images-map
      :smolvm-allowed-images (vec (distinct (map :name smolvm-images)))})))

(defn- executable? [path]
  (let [file (io/file path)]
    (and (.isFile file) (.canExecute file))))

(defn find-command
  "通过直接检查文件系统查找可执行文件，绝不调用 shell。"
  ([command] (find-command command (System/getenv "PATH")))
  ([command path-value]
   (let [command-file (io/file command)]
     (cond
       (and (or (.isAbsolute command-file) (str/includes? command java.io.File/separator))
            (executable? command))
       (.getAbsolutePath command-file)

       (or (.isAbsolute command-file) (str/includes? command java.io.File/separator)) nil

       :else
       (some (fn [directory]
               (let [candidate (io/file directory command)]
                 (when (executable? candidate) (.getAbsolutePath candidate))))
             (remove str/blank? (str/split (or path-value "")
                                           (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))))))))

(defn detect-capabilities
  "探测命令可用性。仅在应用启动时显式调用，加载命名空间时不会执行；PATH 查找不经过 shell。"
  ([application-config] (detect-capabilities application-config (System/getenv "PATH")))
  ([application-config path-value]
   (let [commands {:socat-path (:socat-path application-config)
                   :iptables-path (:iptables-path application-config)
                   :caddy-path (:caddy-path application-config)
                   :container-cli-path (:container-cli-path application-config)
                   :ttyd-path (:ttyd-path application-config)
                   :smolvm-path (:smolvm-path application-config)}
         detected (into {}
                        (map (fn [[key command]]
                               (let [path (find-command command path-value)]
                                 [key {:configured command
                                       :path path
                                       :available? (boolean path)}])))
                        commands)
         smolvm-path (get-in detected [:smolvm-path :path])
         smolvm-runnable? (and smolvm-path
                               (try (zero? (:exit (process/run [smolvm-path "--version"])))
                                    (catch Exception _ false)))]
     (assoc detected :smolvm-available? (boolean smolvm-runnable?)))))

(defn check-sensitive-permissions
  "返回现有敏感路径的权限问题且不修改文件；不支持 POSIX 权限时返回 :unsupported。"
  [paths]
  (vec
   (mapcat
    (fn [path]
      (let [file (io/file path)]
        (when (.exists file)
          (try
            (let [permissions (java.nio.file.Files/getPosixFilePermissions
                               (.toPath file)
                               (make-array java.nio.file.LinkOption 0))
                  exposed (->> permissions
                               (map str)
                               (filter #(or (str/starts-with? % "GROUP_")
                                            (str/starts-with? % "OTHERS_")))
                               sort
                               vec)]
              (when (seq exposed)
                [{:path (.getAbsolutePath file)
                  :type :overly-permissive
                  :permissions exposed}]))
            (catch UnsupportedOperationException _
              [{:path (.getAbsolutePath file) :type :unsupported}])
            (catch Exception e
              [{:path (.getAbsolutePath file) :type :check-failed :message (.getMessage e)}])))))
    paths)))

(defn sensitive-paths
  "返回应用配置引用的敏感配置和状态文件路径。"
  [application-config]
  (mapv application-config
        [:config-file :users-db-path :docker-db-path :smolvm-db-path
         :db-path :caddy-db-path]))

(defn validate-sensitive-permissions!
  "显式配置生产目录时，拒绝权限过宽或无法检查的现有敏感文件。"
  [application-config]
  (when (:enforce-sensitive-permissions application-config)
    (let [issues (->> (sensitive-paths application-config)
                      (remove nil?)
                      check-sensitive-permissions
                      (remove #(= :unsupported (:type %)))
                      vec)]
      (when (seq issues)
        (throw (ex-info "敏感配置或状态文件权限不安全"
                        {:type ::unsafe-sensitive-permissions
                         :issues issues})))))
  nil)

(def paths (resolve-paths))
(def config-dir (io/file (:config-dir paths)))
(def config-json-file (io/file (:config-file paths)))
(def images-json-file (io/file (:images-file paths)))
(def smolvm-images-json-file (io/file (:smolvm-images-file paths)))

(def raw-config-json default-raw-config)
(def images-config [])
(def images-map {})
(def smolvm-images-config [])
(def smolvm-images-map {})
(def allowed-images [])
(def container-cli "docker")
(def container-cli-path "docker")
(def ttyd-path "ttyd")
(def caddy-domains [])
(def caddy-domain nil)
(def ip-mode? true)
(def config (build-config raw-config-json paths images-config smolvm-images-config))

(defn load-config!
  "显式读取并安装运行配置。任何读取或校验失败都会阻止应用启动。"
  []
  (let [loaded-paths (resolve-paths)
        raw (validate-config (read-config (:config-file loaded-paths)))
        images (read-image-list (:images-file loaded-paths))
        smolvm-images (read-image-list (:smolvm-images-file loaded-paths))
        application-config (build-config raw loaded-paths images smolvm-images)
        loaded-images-map (:images-map application-config)
        loaded-smolvm-images-map (:smolvm-images-map application-config)]
    (alter-var-root #'paths (constantly loaded-paths))
    (alter-var-root #'config-dir (constantly (io/file (:config-dir loaded-paths))))
    (alter-var-root #'config-json-file (constantly (io/file (:config-file loaded-paths))))
    (alter-var-root #'images-json-file (constantly (io/file (:images-file loaded-paths))))
    (alter-var-root #'smolvm-images-json-file (constantly (io/file (:smolvm-images-file loaded-paths))))
    (alter-var-root #'raw-config-json (constantly raw))
    (alter-var-root #'images-config (constantly images))
    (alter-var-root #'images-map (constantly loaded-images-map))
    (alter-var-root #'smolvm-images-config (constantly smolvm-images))
    (alter-var-root #'smolvm-images-map (constantly loaded-smolvm-images-map))
    (alter-var-root #'allowed-images (constantly (:allowed-images application-config)))
    (alter-var-root #'container-cli (constantly (:container-cli application-config)))
    (alter-var-root #'container-cli-path (constantly (:container-cli-path application-config)))
    (alter-var-root #'ttyd-path (constantly (:ttyd-path application-config)))
    (alter-var-root #'caddy-domains (constantly (:caddy-domains application-config)))
    (alter-var-root #'caddy-domain (constantly (:caddy-domain application-config)))
    (alter-var-root #'ip-mode? (constantly (:ip-mode application-config)))
    (alter-var-root #'config (constantly application-config))
    application-config))
