# MyBatis + SXSSFWorkbook 导出循环实验手册

## 1. 启动

在仓库根目录执行：

```powershell
docker compose -f docker-compose.export-lab.yml up -d
mvn -pl demo-app -am package
.\scripts\start-export-lab.ps1 -HeapMb 384
```

访问 `http://127.0.0.1:8090/export-lab.html`。需要让真实 OOME 退出整个服务并生成 heap dump 时，单独重启为：

```powershell
.\scripts\start-export-lab.ps1 -HeapMb 256 -CrashOnOom
```

不要在共享、生产或保存重要数据的环境运行。

实验 MySQL 映射在本机 `13307`，不会使用已经被占用的 3306/3307。

## 2. 推荐实验顺序

1. 先准备 100000 行测试数据；如果当前库已有更多数据，不必删除。
2. 先运行 `FIXED`，分页 1000、每文件 50000，确认生成两个文件并正常退出。
3. 运行 `BUGGY_MISSING_BREAK`，OOM 注入保持 0。数据导完后，观察空查询、外循环、CPU 和 GC 持续增长，但没有空 Excel 文件。
4. 页面默认把保护时长设为 0，因此错误任务必须用“停止”按钮结束；停止会在当前 SQL 或当前页处理完成后生效。
5. 默认只提交一次。需要观察重复点击时，可以改成 2～3 次，但不要把它描述成历史事实。
6. 在任务仍存活时采集 2～3 份线程快照，每份间隔 5～10 秒。
7. 停止任务，检查应用日志、GC 日志、输出目录和快照文件。重点验证“任务没有结束”这件事，不把 CPU 高、RT 高或 OOM 当成必然结果。
8. 最后才使用独立的 OOM 注入：以 `-CrashOnOom` 启动，设置每外循环保留 256KB，确认词填写 `ENABLE_OOM`。这只是为了学习 hprof，不属于历史代码。

### RT 对照实验

页面的“三层 RT 对照采样”会在每一轮同时发送三个请求：

- 纯 JVM：`GET /api/export-lab/probe/jvm`，经过浏览器、Tomcat、Controller 和 JSON 序列化，但不访问数据库。
- MySQL 基础探针：`GET /api/export-lab/probe/mysql`，执行 `SELECT 1`，用于观察 Tomcat、Hikari 和 MySQL 基础链路。
- 同表业务探针：`GET /api/export-lab/probe/table?lastId=0&limit=100`，读取 `export_demo_data`，包含 MyBatis 映射和结果 JSON 序列化。

推荐操作：

1. 不启动导出任务，点击“开始持续采样”，至少运行 30 秒。
2. 点击“保存为基线并清零”。页面会保存三类请求的基线 P95。
3. 启动 1 个错误任务，继续采样至少 30 秒，观察当前 P95 相对基线的差值。
4. 停止任务，重新清零；再分别测试 2 个、3 个任务。
5. 实验期间不要切换页面到系统强节流的后台状态，否则浏览器调度会污染 RT。

解释时优先看相对关系：

| 现象 | 更可能的方向 |
|---|---|
| 三类 P95 同时升高 | JVM/Tomcat调度、长时间GC、整机CPU或浏览器本身 |
| 纯JVM稳定，后两类升高 | Hikari连接等待或MySQL整体压力 |
| 只有同表查询升高 | 业务查询路径、数据页/索引竞争或结果映射开销 |
| 三类都基本不变 | 当前1～3个错误任务没有对普通接口造成可测影响 |

这些探针统计的是浏览器完整往返时间。一次请求的高值不能证明问题，至少比较平均值、P95、P99和错误数。为了避免监控本身干扰实验，`/status` 不再每秒对50万行表执行 `COUNT(*)`；数据量只在首次读取或补数据后刷新并缓存。

### 未索引排序与深分页实验

“分页查询策略”默认是主键游标：

```sql
SELECT ...
FROM export_demo_data
WHERE id > ? AND id <= ?
ORDER BY id
LIMIT ?;
```

可选的 `UNINDEXED_DEEP_OFFSET` 使用：

```sql
SELECT ...
FROM export_demo_data
WHERE id <= ?
ORDER BY customer_name, id
LIMIT ?, ?;
```

`customer_name` 没有索引，因此MySQL需要扫描快照范围并执行 filesort；OFFSET越大，需要排序和丢弃的记录越多。当前50万行数据的 `EXPLAIN` 已确认出现 `Using filesort`。错误模式读到末页后缺少外层退出，会继续在最大OFFSET执行同一条重查询。

建议先把“提交次数”设为1、取消“保存真实 xlsx 文件”，只观察数据库竞争；保存30秒基线后启动任务，对比三类探针。停止按钮只能设置任务停止标记，已经发给MySQL的SQL必须先返回。确认单任务可以停止后，再测试2～3个任务。

这是一条显式选择的对照实验路径，不属于已经确认的历史代码事实。它可能提高MySQL CPU、排序内存或临时文件I/O，但是否让普通接口RT明显升高仍以探针P95/P99为准。

### CPU百分比口径

网页读取的是JVM进程占整台机器全部逻辑CPU的比例，范围为0%～100%。在8逻辑CPU机器上，一个Java线程持续占满一个核心，网页通常约显示12.5%；两个核心约25%。Linux `top -H -p PID` 在默认线程口径下，一个线程占满单核通常显示接近100%，而进程总CPU在Irix模式下还可能超过100%。因此比较数据前必须先说明工具的统计口径。

## 3. Linux 在线排查

```bash
PID=$(pgrep -f 'demo-app-1.0.0-SNAPSHOT.jar' | head -1)
top -p "$PID"
top -H -p "$PID"

# 把 top 中的十进制线程 ID 转十六进制，再在线程栈中找 nid
printf '%x\n' <TID>
jcmd "$PID" Thread.print -l > "thread-$(date +%F-%H%M%S).txt"

jstat -gcutil "$PID" 1000 30
jcmd "$PID" GC.heap_info
jcmd "$PID" GC.class_histogram > "histogram-$(date +%F-%H%M%S).txt"
jcmd "$PID" VM.native_memory summary
jcmd "$PID" VM.flags

lsof -p "$PID" | wc -l
du -sh demo-app/runtime/tmp demo-app/runtime/export-output
```

`top -H` 只负责显示线程 CPU，不会同时给出 Heap、GC、线程池或接口 RT。网页上的这些信息分别来自 JVM MXBean、Hikari 和实验任务计数器。

错误循环有时会位于 JDBC 调用，有时位于创建 `SXSSFWorkbook` 或清理 OPCPackage。线程栈不会直接显示“这里是死循环”；需要连续采样，并结合任务计数器确认同一异步工作线程反复经过相同业务调用链。

## 4. MySQL 侧观察

```bash
docker stats export-lab-mysql
docker exec -it export-lab-mysql mysql -uexport_lab -pexport_lab_2026 export_lab
```

进入 MySQL 后：

```sql
SHOW FULL PROCESSLIST;
SHOW GLOBAL STATUS LIKE 'Threads_connected';
SHOW GLOBAL STATUS LIKE 'Questions';
SHOW GLOBAL STATUS LIKE 'Com_select';
SELECT COUNT(*), MAX(id) FROM export_demo_data;
```

因为查询条件是 `id > ? AND id <= ? ORDER BY id LIMIT ?` 且主键有索引，单次空查询很轻；问题来自它无边界、高频地重复执行。具体先打满应用 CPU 还是数据库，要以当次现场为准。

## 5. Windows 在线排查

```powershell
$labPid = (Get-CimInstance Win32_Process | Where-Object CommandLine -Like '*demo-app-1.0.0-SNAPSHOT.jar*').ProcessId
Get-Process -Id $labPid | Select-Object Id, CPU, WorkingSet64, Threads
jcmd $labPid Thread.print -l
jstat -gcutil $labPid 1000 30
jcmd $labPid GC.heap_info
jcmd $labPid GC.class_histogram
jcmd $labPid VM.native_memory summary
Get-ChildItem .\demo-app\runtime\tmp -Recurse | Measure-Object Length -Sum
Get-ChildItem .\demo-app\runtime\export-output -Recurse | Measure-Object Length -Sum
```

Windows 的 `Get-Process CPU` 是进程启动后的累计 CPU 秒数，不是 Linux `top` 那样的瞬时百分比；瞬时趋势直接看网页或使用 JFR / Process Explorer。

如果 PowerShell 找不到 `jcmd`，使用与运行中 JVM 同版本 JDK 的完整路径，例如：

```powershell
& "$env:JAVA_HOME\bin\jcmd.exe" $labPid VM.command_line
```

## 6. 服务停止后还能看什么

- `runtime/logs/application.log`：检索任务 ID、`EXPORT_EMPTY_PAGE_LOOP`、`EXPORT_FILE_SAVED`、`EXPORT_TASK_FINISHED`。
- `runtime/gc/gc.log*`：比较 Young GC 次数、分配压力、停顿时间、老年代占用以及退出前最后事件。
- `runtime/evidence/thread-dump-*.txt`：这是存活时主动保存的现场；服务退出后不能补采。
- `runtime/dump/*.hprof`：只有真正抛出 OOME 才会生成；人工重启、保护器停止或普通 kill 不会触发。
- `runtime/logs/hs_err_pid*.log`：JVM 崩溃日志；普通 Java OOME 通常不等同于 JVM native crash。

应用日志建议这样过滤：

```bash
grep -E 'EXPORT_TASK_|EXPORT_EMPTY_PAGE_LOOP|OutOfMemoryError' runtime/logs/application.log
grep '<taskId>' runtime/logs/application.log
```

GC 日志先回答四个问题：GC 是 Young 还是 Full、频率是否越来越高、GC 后 Heap 是否明显回落、单次停顿是否足以解释 RT。只有 GC 后存活集持续升高，才进一步怀疑对象被长期引用。

## 7. MAT 查看 heap dump

1. 用 Eclipse MAT 打开 `.hprof` 并生成 Leak Suspects Report。
2. 看 Dominator Tree，按 Retained Heap 排序。
3. 额外 OOM 注入通常会看到大量 `byte[]`，其 GC Root 路径最终指向导出任务状态里的 retained 集合；普通错误模式不保证生成 heap dump。
4. 使用 Path to GC Roots 排除 weak/soft references，确认是谁让对象不能回收。
5. 历史错误模式不开 OOM 注入时，不能预设一定存在巨大的存活对象；它更可能表现为高分配速率和高频 Young GC。

## 8. 结论边界

- `BUGGY_MISSING_BREAK + oomInjection=0`：对应遗漏退出的代码，只保证复现任务不结束、空查询和短命对象抖动，不保证全站卡顿或 OOM。
- `oomInjection>0`：为了稳定制造 `Java heap space` 和 `.hprof` 的实验注入，不属于历史代码。
- SXSSF 写入阶段会产生临时 XML，`dispose()` 后应该删除；最终 xlsx 保留在输出目录。
- 提前重启且未发生 OOME 时，没有新 heap dump 是正常现象。
