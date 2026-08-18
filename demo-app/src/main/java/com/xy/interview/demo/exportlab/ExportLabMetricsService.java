package com.xy.interview.demo.exportlab;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class ExportLabMetricsService {

    private static final int MAX_SAMPLES = 180;

    private final ExportLabService exportLabService;
    private final ExportDataAdminService dataAdminService;
    private final HikariDataSource dataSource;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean operatingSystem =
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final Deque<RuntimeSample> samples = new ArrayDeque<>();
    private volatile RuntimeSample latest;

    public ExportLabMetricsService(ExportLabService exportLabService,
                                   ExportDataAdminService dataAdminService,
                                   DataSource dataSource) {
        this.exportLabService = exportLabService;
        this.dataAdminService = dataAdminService;
        this.dataSource = dataSource instanceof HikariDataSource hikari ? hikari : null;
    }

    @Scheduled(fixedDelay = 1000)
    public void sample() {
        RuntimeSample next = createSample();
        synchronized (samples) {
            samples.addLast(next);
            while (samples.size() > MAX_SAMPLES) {
                samples.removeFirst();
            }
        }
        latest = next;
    }

    public ExportLabStatus status() {
        RuntimeSample current = latest;
        if (current == null) {
            current = createSample();
            latest = current;
        }
        long rows = -1;
        long maxId = -1;
        try {
            // The one-second status poll must not scan the demo table on every request,
            // otherwise the monitoring page itself would contaminate the probe result.
            ExportDataAdminService.DataSummary summary = dataAdminService.cachedSummary();
            rows = summary.rows();
            maxId = summary.maxId();
        } catch (RuntimeException ignored) {
            // Keep JVM metrics available even if MySQL is saturated or unavailable.
        }
        List<RuntimeSample> history;
        synchronized (samples) {
            history = new ArrayList<>(samples);
        }
        return new ExportLabStatus(
                ProcessHandle.current().pid(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                System.getProperty("java.version"),
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString(),
                exportLabService.outputDirectory().toString(),
                rows,
                maxId,
                current,
                history,
                exportLabService.taskViews(),
                "top -H -p <PID> → printf '%x\\n' <TID> → jcmd <PID> Thread.print",
                "错误模式只复现遗漏外层退出：数据读完后仍反复创建工作簿并执行空查询；它不预设全站卡顿或 OOM"
        );
    }

    private RuntimeSample createSample() {
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            gcCount += Math.max(0, collector.getCollectionCount());
            gcTime += Math.max(0, collector.getCollectionTime());
        }
        ThreadPoolExecutor executor = exportLabService.executor();
        int hikariActive = 0;
        int hikariIdle = 0;
        if (dataSource != null) {
            HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
            if (pool != null) {
                hikariActive = pool.getActiveConnections();
                hikariIdle = pool.getIdleConnections();
            }
        }
        double cpu = operatingSystem == null ? -1 : operatingSystem.getProcessCpuLoad() * 100.0;
        return new RuntimeSample(
                Instant.now(),
                cpu,
                memory.getHeapMemoryUsage().getUsed(),
                memory.getHeapMemoryUsage().getCommitted(),
                memory.getHeapMemoryUsage().getMax(),
                gcCount,
                gcTime,
                threads.getThreadCount(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                hikariActive,
                hikariIdle,
                directoryBytes(Path.of(System.getProperty("java.io.tmpdir"))),
                directoryBytes(exportLabService.outputDirectory()),
                exportLabService.totalQueries()
        );
    }

    private long directoryBytes(Path directory) {
        if (!Files.exists(directory)) {
            return 0;
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0L;
                }
            }).sum();
        } catch (IOException | RuntimeException ignored) {
            return -1;
        }
    }
}
