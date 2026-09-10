(ns culvert.main
  (:require [culvert.config :as config]
            [culvert.log :as log])
  (:gen-class))

(def ^:volatile ^boolean running (volatile! true))
(defonce app-system (atom nil))

(defn- graceful-shutdown! []
  (log/info "收到退出信号，开始清理系统状态...")
  (vreset! running false)
  (when-let [system @app-system]
    (try
      ((requiring-resolve 'culvert.system/stop!) system)
      (println "[shutdown] 清理完成，安全退出。")
      (catch clojure.lang.ExceptionInfo error
        (log/error "系统关闭不完整:" (ex-data error)))))
  (flush))

(defn -main [& _args]
  (let [application-config (config/load-config!)
        create-system (requiring-resolve 'culvert.system/create)
        default-dependencies (requiring-resolve 'culvert.system/default-dependencies)
        start-system! (requiring-resolve 'culvert.system/start!)
        system (create-system (default-dependencies))]
    (reset! app-system system)
    (vreset! running true)
    (log/info "============================================================")
    (log/info " 🐳 端口转发与开发容器管理器 (Babashka + HTMX v4.0.0)")
    (log/info "============================================================")
    (if (:ip-mode application-config)
      (log/info " 📡 运行模式: IP 直连 (无 Caddy)")
      (log/info " 🌐 运行模式: 域名反向代理 (Caddy)"))

    ;; 关闭钩子响应 SIGINT/SIGTERM，钩子内不调用 System/exit。
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (graceful-shutdown!))))

    (log/info "正在同步并恢复运行状态...")
    (start-system! system)
    (log/info "初始化就绪。按 Ctrl+C 停止服务。")

    ;; 保持主线程存活，关闭钩子清除运行标记后退出。
    (while @running
      (try
        (Thread/sleep 1000)
        (catch InterruptedException _
          (vreset! running false))))))
