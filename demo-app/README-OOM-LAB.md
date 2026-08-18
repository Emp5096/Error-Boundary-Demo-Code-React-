# JVM Incident Lab

这是一个由 Spring Boot 提供控制接口、由隔离子 JVM 制造真实故障信号的诊断练习网页。

## 启动

在仓库根目录执行：

```powershell
mvn -pl demo-app -am package
java -jar demo-app/target/demo-app-1.0.0-SNAPSHOT.jar
```

然后访问 <http://127.0.0.1:8080/>。

## 可练习的场景

- `HEAP_OOM`：对象被强引用保留，堆持续上升，最终触发真实的 `Java heap space`。
- `CPU_HIGH`：计算线程持续运行，进程 CPU 接近或超过单核 100%。
- `GC_THRASH`：大量短命对象快速产生，累计分配量和 GC 次数快速增长，但存活堆不一定持续上升。
- `THREAD_POOL`：4 个业务线程全部阻塞、队列积压，探测请求达到 1.5 秒超时，而 CPU、堆和 GC 可能正常。
- `RANDOM`：随机选择以上场景，提交诊断前隐藏根因，适合盲测。

## 推荐练习顺序

1. 只根据页面描述记录发生时间、影响范围和 RT 现象，不直接判断 OOM。
2. 观察 CPU、堆、GC、线程数、线程池 active/queue 的组合变化。
3. 主动采集进程概览、GC 采样、线程快照、堆概览、JVM 参数或应用日志。
4. 至少取得两项证据后提交根因判断。
5. 对照页面给出的决定性证据、立即止损和长期修复进行复盘。
6. 另开一轮实验，先点击“模拟重启 / 丢失现场”，体会进程退出后无法补采 `jstack`、`jcmd` 的区别。

## 安全边界

- 所有故障负载都在最大堆为 32～256 MB 的独立 Java 子进程中运行。
- 实验时长限制为 10～90 秒，且同一时刻只允许一个实验。
- 页面本身运行在 Spring Boot 主进程；子 JVM OOM 或退出后页面仍可访问。
- 线程快照、堆概览和 JVM 参数使用 JDK 自带的 `jcmd` 从仍然存活的子进程采集。
- CPU 场景仍会使用本机计算资源，请勿在共享生产环境中运行。

## 主要接口

```text
GET    /api/lab/status
POST   /api/lab/experiments
DELETE /api/lab/experiments/current
GET    /api/lab/evidence/{PROCESS|GC|THREAD_DUMP|HEAP|JVM_FLAGS|APP_LOG}
POST   /api/lab/diagnoses
GET    /api/lab/probe
```

旧的 `/api/oom/*` 路径仍保留为兼容别名。
