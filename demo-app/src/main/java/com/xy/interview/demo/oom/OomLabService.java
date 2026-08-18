package com.xy.interview.demo.oom;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class OomLabService {

    private static final int MIN_HEAP_MB = 32;
    private static final int MAX_HEAP_MB = 256;
    private static final int MIN_CHUNK_KB = 64;
    private static final int MAX_CHUNK_KB = 4096;
    private static final int MAX_INTERVAL_MS = 1000;
    private static final int MIN_DURATION_SECONDS = 10;
    private static final int MAX_DURATION_SECONDS = 90;
    private static final int MAX_LOG_LINES = 180;
    private static final int MAX_SAMPLES = 240;
    private static final int MAX_EVIDENCE_CHARS = 24_000;

    private final ExecutorService experimentExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "incident-lab-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService evidenceExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "incident-lab-evidence");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ExperimentState current;

    public synchronized OomExperimentView start(OomExperimentRequest request) {
        if (current != null && current.isActive()) {
            throw new IllegalStateException("已有实验正在运行，请先完成诊断或停止当前实验");
        }

        NormalizedRequest normalized = normalize(request);
        ExperimentState experiment = new ExperimentState(normalized);
        current = experiment;
        experimentExecutor.submit(() -> runExperiment(experiment));
        return experiment.toView();
    }

    public OomExperimentView status() {
        ExperimentState experiment = current;
        return experiment == null ? idleView() : experiment.toView();
    }

    public synchronized OomExperimentView stop() {
        if (current == null || !current.isActive()) {
            return status();
        }
        current.cancel();
        return current.toView();
    }

    public EvidenceView collectEvidence(String rawType) {
        ExperimentState experiment = requireExperiment();
        EvidenceType type;
        try {
            type = EvidenceType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("未知证据类型：" + rawType);
        }

        experiment.markEvidence(type.name());
        OomExperimentView view = experiment.toView();
        boolean processAlive = experiment.hasLiveProcess();
        String unavailable = "实验进程已经退出，无法补采这项现场证据；这里只保留最后一次采样。";

        return switch (type) {
            case PROCESS -> new EvidenceView(
                    type.name(), "进程概览", "top -Hp " + displayPid(view.pid()), Instant.now(),
                    processAlive, processAlive ? "进程仍在，可继续采集现场。" : unavailable,
                    processOverview(view),
                    "CPU 高只说明消耗发生了，仍需结合线程栈、GC 和堆趋势判断根因。"
            );
            case GC -> new EvidenceView(
                    type.name(), "GC 采样", "jstat -gcutil " + displayPid(view.pid()) + " 1000 5", Instant.now(),
                    !view.samples().isEmpty(), gcSummary(view), gcEvidence(view),
                    "GC 次数多不等于内存泄漏：短命对象分配过快也会造成高频 Young GC。"
            );
            case THREAD_DUMP -> liveJcmdEvidence(
                    experiment, type, "线程快照", "Thread.print", "jstack " + displayPid(view.pid()),
                    "连续采集 2～3 次并比较相同线程是否一直停在同一栈帧，单次快照容易误判。"
            );
            case HEAP -> liveJcmdEvidence(
                    experiment, type, "堆概览", "GC.heap_info", "jcmd " + displayPid(view.pid()) + " GC.heap_info",
                    "堆接近上限不一定是泄漏，还要看 Full GC 后能否明显下降以及对象保留链。"
            );
            case JVM_FLAGS -> liveJcmdEvidence(
                    experiment, type, "JVM 参数", "VM.flags", "jcmd " + displayPid(view.pid()) + " VM.flags",
                    "重点确认 Xmx、垃圾收集器、OOM dump 和 GC 日志参数，而不是只看默认值。"
            );
            case APP_LOG -> new EvidenceView(
                    type.name(), "应用日志", "grep <time-window> application.log", Instant.now(),
                    true, "应用日志可以在进程退出后保留，但未必包含 JVM 资源耗尽的直接证据。",
                    String.join(System.lineSeparator(), view.logs()),
                    "没有异常日志不能排除 CPU、GC 或线程池问题；日志只是证据源之一。"
            );
        };
    }

    public DiagnosisResult submitDiagnosis(DiagnosisRequest request) {
        if (request == null || request.cause() == null) {
            throw new IllegalArgumentException("请选择一个根因假设");
        }
        ExperimentState experiment = requireExperiment();
        Scenario selected;
        try {
            selected = Scenario.valueOf(request.cause().toUpperCase(Locale.ROOT));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("未知根因假设：" + request.cause());
        }

        Scenario actual = experiment.request.scenario;
        experiment.markDiagnosis(selected.name());
        boolean correct = selected == actual;
        return new DiagnosisResult(
                correct,
                selected.name(),
                actual.name(),
                correct ? "判断正确：证据链能够互相印证" : "判断不正确：需要区分现象与根因",
                reasoning(actual),
                decisiveEvidence(actual),
                immediateActions(actual),
                longTermFixes(actual)
        );
    }

    public ProbeView replayProbe() {
        ExperimentState experiment = requireExperiment();
        long delay = Math.max(20L, Math.min(1500L, experiment.probeRtMillis()));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("业务探测被中断");
        }
        boolean timeout = delay >= 1500L;
        return new ProbeView(
                delay,
                timeout ? "TIMEOUT" : "OK",
                timeout
                        ? "业务线程池中的探测任务在 1.5 秒内没有获得执行机会"
                        : "按子 JVM 最近一次实测 RT 回放完成"
        );
    }

    @PreDestroy
    public void shutdown() {
        ExperimentState experiment = current;
        if (experiment != null && experiment.isActive()) {
            experiment.cancel();
        }
        experimentExecutor.shutdownNow();
        evidenceExecutor.shutdownNow();
    }

    private void runExperiment(ExperimentState experiment) {
        Process process = null;
        try {
            List<String> command = buildCommand(experiment.request);
            experiment.logCommand(command);
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            experiment.processStarted(process);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    experiment.acceptWorkerLine(line);
                }
            }

            int exitCode = process.waitFor();
            experiment.processFinished(exitCode);
        } catch (IOException error) {
            experiment.failed("无法启动故障实验子进程", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            experiment.failed("实验线程已被中断", error);
        } catch (RuntimeException error) {
            experiment.failed("实验执行失败", error);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    private List<String> buildCommand(NormalizedRequest request) {
        String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
        ).toString();

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.add("-Xms16m");
        command.add("-Xmx" + request.heapMb + "m");
        command.add("-XX:+UseSerialGC");
        command.add("-Dfile.encoding=UTF-8");

        Path executableJar = executableJar();
        if (executableJar != null) {
            command.add("-Dloader.main=" + OomWorker.class.getName());
            command.add("-cp");
            command.add(executableJar.toString());
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(OomWorker.class.getName());
        }

        command.add(request.scenario.name());
        command.add(String.valueOf(request.chunkKb * 1024));
        command.add(String.valueOf(request.intervalMs));
        command.add(String.valueOf(request.intensity));
        command.add(String.valueOf(request.durationSeconds * 1000L));
        return command;
    }

    private EvidenceView liveJcmdEvidence(
            ExperimentState experiment,
            EvidenceType type,
            String title,
            String jcmdOperation,
            String displayCommand,
            String caution
    ) {
        long pid = experiment.pid();
        if (!experiment.hasLiveProcess() || pid <= 0) {
            return new EvidenceView(
                    type.name(), title, displayCommand, Instant.now(), false,
                    "实验进程已经退出，无法补采这项现场证据。",
                    "[unavailable] PID 已不存在。生产事故中如果必须先重启，应在重启前尽快保存线程栈、GC 和进程信息。",
                    caution
            );
        }

        String content = runJcmd(pid, jcmdOperation);
        boolean available = !content.startsWith("[unavailable]");
        return new EvidenceView(
                type.name(), title, displayCommand, Instant.now(), available,
                available ? "已从仍在运行的子 JVM 采集真实输出。" : "采集命令未成功执行。",
                content, caution
        );
    }

    private String runJcmd(long pid, String operation) {
        Path executable = Path.of(
                System.getProperty("java.home"), "bin", isWindows() ? "jcmd.exe" : "jcmd"
        );
        if (!Files.isRegularFile(executable)) {
            return "[unavailable] 当前 Java 运行时未包含 jcmd：" + executable;
        }

        try {
            Process process = new ProcessBuilder(
                    executable.toString(), String.valueOf(pid), operation
            ).redirectErrorStream(true).start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    process.getInputStream().transferTo(buffer);
                    return buffer.toByteArray();
                } catch (IOException error) {
                    return ("[unavailable] 读取 jcmd 输出失败：" + error.getMessage())
                            .getBytes(StandardCharsets.UTF_8);
                }
            }, evidenceExecutor);

            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroy();
                return "[unavailable] jcmd 在 6 秒内没有返回，已停止采集，避免进一步影响故障进程。";
            }
            String text = new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return "[unavailable] jcmd 退出码 " + process.exitValue() + System.lineSeparator() + text;
            }
            return limit(text, MAX_EVIDENCE_CHARS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "[unavailable] jcmd 执行失败：" + error.getMessage();
        } catch (Exception error) {
            return "[unavailable] jcmd 执行失败：" + error.getMessage();
        }
    }

    private String processOverview(OomExperimentView view) {
        return String.format(Locale.ROOT,
                "PID             %s%n状态            %s%n进程 CPU        %.1f%%（单核口径）%n堆使用          %s / %s%nGC 次数/耗时    %d / %d ms%n存活线程        %d%n业务池 active   %d / 4%n业务池 queue    %d%n业务探测 RT     %d ms",
                displayPid(view.pid()), view.status(), view.cpuPercent(),
                formatBytes(view.usedHeapBytes()), formatBytes(view.maxHeapBytes()),
                view.gcCollections(), view.gcTimeMillis(), view.liveThreads(),
                view.poolActive(), view.poolQueue(), view.probeRtMillis());
    }

    private String gcSummary(OomExperimentView view) {
        if (view.samples().size() < 2) {
            return "采样不足，请等待 2～3 秒后再次查看。";
        }
        MemorySample first = view.samples().get(0);
        MemorySample last = view.samples().get(view.samples().size() - 1);
        long elapsed = Math.max(1L, last.elapsedMillis() - first.elapsedMillis());
        long collections = Math.max(0L, last.gcCollections() - first.gcCollections());
        double perSecond = collections * 1000d / elapsed;
        return String.format(Locale.ROOT, "采样窗口内发生 %d 次 GC，约 %.1f 次/秒。", collections, perSecond);
    }

    private String gcEvidence(OomExperimentView view) {
        if (view.samples().isEmpty()) {
            return "尚无 GC 样本。";
        }
        MemorySample first = view.samples().get(0);
        MemorySample last = view.samples().get(view.samples().size() - 1);
        return String.format(Locale.ROOT,
                "样本数          %d%n累计 GC 次数     %d -> %d%n累计 GC 耗时     %d ms -> %d ms%n堆使用          %s -> %s%n累计对象分配     %s",
                view.samples().size(), first.gcCollections(), last.gcCollections(),
                first.gcTimeMillis(), last.gcTimeMillis(),
                formatBytes(first.usedHeapBytes()), formatBytes(last.usedHeapBytes()),
                formatBytes(last.allocatedBytes()));
    }

    private String reasoning(Scenario scenario) {
        return switch (scenario) {
            case HEAP_OOM -> "堆使用量持续逼近 Xmx，GC 后无法回落，最终出现真实的 java.lang.OutOfMemoryError。根因是对象被强引用长期保留。";
            case CPU_HIGH -> "堆和 GC 基本稳定，但进程 CPU 持续接近或超过单核 100%；线程快照中的 cpu-spin 线程一直处于 RUNNABLE，说明热点在计算循环。";
            case GC_THRASH -> "堆并非单调增长，却存在极高的累计分配量、GC 次数和 GC 耗时；短命对象生产速度超过了回收的舒适区。";
            case THREAD_POOL -> "CPU、堆和 GC 都不高，但业务池 active 长期等于最大线程数、queue 持续有积压，业务探测达到超时阈值。";
        };
    }

    private List<String> decisiveEvidence(Scenario scenario) {
        return switch (scenario) {
            case HEAP_OOM -> List.of("堆曲线持续上升且 Full GC 后不回落", "最终日志出现 Java heap space", "堆转储中应继续检查大对象及 GC Root 保留链");
            case CPU_HIGH -> List.of("进程 CPU 持续高，堆和 GC 平稳", "多次线程栈中同一 RUNNABLE 线程停在相同计算栈", "定位线程 ID 后再映射业务代码");
            case GC_THRASH -> List.of("单位时间 GC 次数和耗时快速增加", "累计分配量很大，但存活堆没有同比增长", "线程栈可看到分配热点，JFR 更适合确认分配来源");
            case THREAD_POOL -> List.of("active == max 且 queue > 0", "探测请求排队或超时", "线程栈显示工作线程阻塞/等待外部资源，而非消耗 CPU");
        };
    }

    private List<String> immediateActions(Scenario scenario) {
        return switch (scenario) {
            case HEAP_OOM -> List.of("先保存时间点、错误日志、GC 日志和可用的 heap dump", "停止入口流量或取消异常任务，避免继续分配", "确认现场已保留后再重启恢复服务");
            case CPU_HIGH -> List.of("top -Hp 定位高 CPU 线程并连续采集线程栈", "限流或停止触发热点循环的任务", "确认线程栈证据后再决定重启，而不是先猜 OOM");
            case GC_THRASH -> List.of("保存 jstat/GC 日志与分配热点证据", "降低任务并发或批次大小，减少瞬时分配", "若已严重影响请求，隔离或取消高分配任务");
            case THREAD_POOL -> List.of("保存线程池 active/queue/reject 指标和多份线程栈", "停止继续进入的慢任务并做限流/降级", "检查下游超时；必要时取消阻塞任务后恢复容量");
        };
    }

    private List<String> longTermFixes(Scenario scenario) {
        return switch (scenario) {
            case HEAP_OOM -> List.of("修复无边界集合、缓存或循环，并增加停止条件", "大文件采用流式写出，不在堆中保留全部结果", "配置 OOM heap dump、GC 日志和磁盘容量告警");
            case CPU_HIGH -> List.of("修复死循环/低效算法并增加任务超时和取消机制", "对 CPU 型任务使用独立有界线程池", "补充 CPU、热点方法和慢任务监控");
            case GC_THRASH -> List.of("减少临时对象、复用缓冲区并控制批次", "结合 GC 日志/JFR 先优化分配，再评估 GC 参数", "为批处理设置并发、速率和内存预算");
            case THREAD_POOL -> List.of("线程池必须有界，并设置拒绝策略和队列告警", "下游调用设置连接、读取和整体超时", "长任务异步化、隔离线程池，并提供取消与幂等能力");
        };
    }

    private ExperimentState requireExperiment() {
        ExperimentState experiment = current;
        if (experiment == null) {
            throw new IllegalStateException("请先启动一个故障实验");
        }
        return experiment;
    }

    private NormalizedRequest normalize(OomExperimentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }

        String requestedScenario = request.scenario() == null
                ? Scenario.HEAP_OOM.name()
                : request.scenario().trim().toUpperCase(Locale.ROOT);
        boolean blind = "RANDOM".equals(requestedScenario);
        Scenario scenario;
        if (blind) {
            Scenario[] candidates = Scenario.values();
            scenario = candidates[ThreadLocalRandom.current().nextInt(candidates.length)];
        } else {
            try {
                scenario = Scenario.valueOf(requestedScenario);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("场景必须是 RANDOM、HEAP_OOM、CPU_HIGH、GC_THRASH 或 THREAD_POOL");
            }
        }

        int heapMb = request.heapMb() == null ? (scenario == Scenario.GC_THRASH ? 48 : 64) : request.heapMb();
        int chunkKb = request.chunkKb() == null ? 512 : request.chunkKb();
        int intervalMs = request.intervalMs() == null ? 120 : request.intervalMs();
        int intensity = request.intensity() == null ? 1 : request.intensity();
        int durationSeconds = request.durationSeconds() == null ? 40 : request.durationSeconds();

        if (heapMb < MIN_HEAP_MB || heapMb > MAX_HEAP_MB) {
            throw new IllegalArgumentException("堆上限必须在 32 MB 到 256 MB 之间");
        }
        if (chunkKb < MIN_CHUNK_KB || chunkKb > MAX_CHUNK_KB) {
            throw new IllegalArgumentException("每次分配必须在 64 KB 到 4096 KB 之间");
        }
        if (intervalMs < 0 || intervalMs > MAX_INTERVAL_MS) {
            throw new IllegalArgumentException("分配间隔必须在 0 ms 到 1000 ms 之间");
        }
        if (intensity < 1 || intensity > 4) {
            throw new IllegalArgumentException("负载强度必须在 1 到 4 之间");
        }
        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("实验时长必须在 10 秒到 90 秒之间");
        }
        return new NormalizedRequest(scenario, blind, heapMb, chunkKb, intervalMs, intensity, durationSeconds);
    }

    private OomExperimentView idleView() {
        return new OomExperimentView(
                null, "IDLE", "NONE", false, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0,
                null, null, 0, null, null, null,
                List.of("等待启动故障诊断实验…"), List.of(), List.of(), false
        );
    }

    private Path executableJar() {
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.contains(File.pathSeparator)) {
            Path classPathEntry = Path.of(classPath).toAbsolutePath().normalize();
            if (isJar(classPathEntry)) {
                return classPathEntry;
            }
        }
        Path codeSource = applicationLocation().toAbsolutePath().normalize();
        return isJar(codeSource) ? codeSource : null;
    }

    private boolean isJar(Path path) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private Path applicationLocation() {
        try {
            return Path.of(OomWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException error) {
            throw new IllegalStateException("无法解析应用路径", error);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String displayPid(long pid) {
        return pid > 0 ? String.valueOf(pid) : "<pid>";
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0 MB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024d / 1024d);
    }

    private String limit(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + System.lineSeparator() + "…输出已截断…";
    }

    private record NormalizedRequest(
            Scenario scenario,
            boolean blind,
            int heapMb,
            int chunkKb,
            int intervalMs,
            int intensity,
            int durationSeconds
    ) {
    }

    private enum Scenario {
        HEAP_OOM,
        CPU_HIGH,
        GC_THRASH,
        THREAD_POOL
    }

    private enum EvidenceType {
        PROCESS,
        GC,
        THREAD_DUMP,
        HEAP,
        JVM_FLAGS,
        APP_LOG
    }

    private static final class ExperimentState {
        private final String id = UUID.randomUUID().toString();
        private final NormalizedRequest request;
        private final Instant startedAt = Instant.now();
        private final List<String> logs = new ArrayList<>();
        private final List<MemorySample> samples = new ArrayList<>();
        private final List<String> collectedEvidence = new ArrayList<>();

        private String status = "STARTING";
        private long pid;
        private long allocatedBytes;
        private long usedHeapBytes;
        private long maxHeapBytes;
        private double cpuPercent;
        private long gcCollections;
        private long gcTimeMillis;
        private int liveThreads;
        private int poolActive;
        private int poolQueue;
        private long probeRtMillis = 20;
        private Instant finishedAt;
        private Integer exitCode;
        private String errorType;
        private String errorMessage;
        private Process process;
        private boolean cancelled;
        private boolean diagnosisSubmitted;
        private String selectedDiagnosis;

        private ExperimentState(NormalizedRequest request) {
            this.request = request;
            log("[lab] 正在创建隔离的子 JVM…");
            if (request.blind) {
                log("[lab] 盲测模式已启用：根因将在提交判断后揭晓");
            }
        }

        private synchronized boolean isActive() {
            return "STARTING".equals(status)
                    || "RUNNING".equals(status)
                    || (process != null && process.isAlive());
        }

        private synchronized boolean hasLiveProcess() {
            return process != null && process.isAlive();
        }

        private synchronized long pid() {
            return pid;
        }

        private synchronized long probeRtMillis() {
            return probeRtMillis;
        }

        private synchronized void logCommand(List<String> command) {
            String rendered = String.join(" ", command);
            if (request.blind) {
                rendered = rendered.replace(request.scenario.name(), "<HIDDEN_SCENARIO>");
            }
            log("$ " + rendered);
        }

        private synchronized void processStarted(Process process) {
            this.process = process;
            this.pid = process.pid();
            if (!cancelled) {
                status = "RUNNING";
                log("[lab] 子 JVM 已启动，PID=" + pid);
            } else {
                process.destroy();
            }
        }

        private synchronized void acceptWorkerLine(String line) {
            String[] parts = line.split("\\|", 12);
            try {
                switch (parts[0]) {
                    case "READY" -> {
                        maxHeapBytes = Long.parseLong(parts[1]);
                        pid = Long.parseLong(parts[2]);
                        log("[worker] JVM 就绪，maxHeap=" + maxHeapBytes + " bytes");
                    }
                    case "EVENT" -> log("[worker] " + parts[1]);
                    case "SAMPLE" -> {
                        long elapsed = Long.parseLong(parts[1]);
                        usedHeapBytes = Long.parseLong(parts[2]);
                        maxHeapBytes = Long.parseLong(parts[3]);
                        allocatedBytes = Long.parseLong(parts[4]);
                        cpuPercent = Double.parseDouble(parts[5]);
                        gcCollections = Long.parseLong(parts[6]);
                        gcTimeMillis = Long.parseLong(parts[7]);
                        liveThreads = Integer.parseInt(parts[8]);
                        poolActive = Integer.parseInt(parts[9]);
                        poolQueue = Integer.parseInt(parts[10]);
                        probeRtMillis = Long.parseLong(parts[11]);
                        addSample(new MemorySample(
                                elapsed, usedHeapBytes, allocatedBytes, cpuPercent,
                                gcCollections, gcTimeMillis, liveThreads,
                                poolActive, poolQueue, probeRtMillis));
                    }
                    case "OOM" -> {
                        status = "OOM";
                        errorType = parts[1];
                        errorMessage = parts[2];
                        allocatedBytes = Long.parseLong(parts[3]);
                        finishedAt = Instant.now();
                        log("[worker] " + errorType + ": " + errorMessage);
                    }
                    case "DONE" -> {
                        status = "COMPLETED";
                        finishedAt = Instant.now();
                        log("[worker] 场景达到预设时长，正常结束");
                    }
                    default -> log("[worker] " + line);
                }
            } catch (RuntimeException parseError) {
                log("[lab] 无法解析子进程输出：" + parseError.getMessage());
            }
        }

        private synchronized void processFinished(int exitCode) {
            this.exitCode = exitCode;
            this.process = null;
            if (cancelled) {
                status = "CANCELLED";
            } else if (!"OOM".equals(status) && !"COMPLETED".equals(status)) {
                status = exitCode == 0 ? "COMPLETED" : "FAILED";
                if (exitCode != 0) {
                    errorMessage = "子 JVM 异常退出，退出码 " + exitCode;
                }
            }
            if (finishedAt == null) {
                finishedAt = Instant.now();
            }
            log("[lab] 子 JVM 已退出，exitCode=" + exitCode);
        }

        private synchronized void failed(String message, Throwable error) {
            if (cancelled) {
                return;
            }
            status = "FAILED";
            errorType = error.getClass().getName();
            errorMessage = message + "：" + error.getMessage();
            finishedAt = Instant.now();
            log("[lab] " + errorMessage);
        }

        private synchronized void cancel() {
            cancelled = true;
            status = "CANCELLED";
            finishedAt = Instant.now();
            log("[lab] 已模拟重启/停止：进程现场随之丢失");
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }

        private synchronized void markEvidence(String type) {
            if (!collectedEvidence.contains(type)) {
                collectedEvidence.add(type);
            }
        }

        private synchronized void markDiagnosis(String selected) {
            diagnosisSubmitted = true;
            selectedDiagnosis = selected;
            log("[lab] 已提交根因判断：" + selected);
        }

        private synchronized void log(String line) {
            if (logs.size() == MAX_LOG_LINES) {
                logs.remove(0);
            }
            logs.add(line);
        }

        private synchronized void addSample(MemorySample sample) {
            if (samples.size() == MAX_SAMPLES) {
                samples.remove(0);
            }
            samples.add(sample);
        }

        private synchronized OomExperimentView toView() {
            Instant effectiveEnd = finishedAt == null ? Instant.now() : finishedAt;
            long durationMillis = Duration.between(startedAt, effectiveEnd).toMillis();
            String publicScenario = request.blind && !diagnosisSubmitted
                    ? "HIDDEN"
                    : request.scenario.name();
            return new OomExperimentView(
                    id, status, publicScenario, request.blind, pid,
                    request.heapMb * 1024L * 1024L,
                    request.chunkKb * 1024L,
                    allocatedBytes, usedHeapBytes, maxHeapBytes,
                    cpuPercent, gcCollections, gcTimeMillis, liveThreads,
                    poolActive, poolQueue, probeRtMillis,
                    startedAt, finishedAt, durationMillis, exitCode,
                    errorType, errorMessage, List.copyOf(logs), List.copyOf(samples),
                    List.copyOf(collectedEvidence), diagnosisSubmitted
            );
        }
    }
}
