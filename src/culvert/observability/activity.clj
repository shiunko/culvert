(ns culvert.observability.activity
  "进程内活动记录（飞行记录仪）环形缓冲：只读展示用，不落盘、不执行任何修复动作。
   所有条目先经 event/redact 递归脱敏，密码/token/secret/请求正文不得进入缓冲。"
  (:require [culvert.observability.event :as event]))

(def capacity
  "缓冲容量上限。"
  200)

(defonce store (atom []))

(defn- normalize-severity [s]
  (if (#{:info :warning :error} s) s :info))

(defn record!
  "写入一条活动事件并返回它。借助 `event/event` 补齐 timestamp/request-id 并递归脱敏，
   再补上唯一 `:id` 与规范化 `:severity`。缓冲只保留最近 `capacity` 条。"
  [entry]
  (let [e (-> (event/event entry)
              (assoc :id (str (java.util.UUID/randomUUID)))
              (update :severity normalize-severity))]
    (swap! store (fn [items] (vec (take-last capacity (conj items e)))))
    e))

(defn recent
  "返回最近 n 条事件，最新在前；n 超过容量时按容量截断，缺省返回全部。"
  ([] (recent capacity))
  ([n]
   (let [k (max 0 (min capacity (if (nil? n) capacity n)))]
     (vec (reverse (take-last k @store))))))

(defn clear!
  "清空缓冲，仅供测试或显式重置。"
  []
  (reset! store []))
