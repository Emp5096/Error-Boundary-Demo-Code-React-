package com.xy.interview.demo.exportlab;

import jakarta.annotation.PreDestroy;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Service
public class ExportLabService {

    private static final Logger log = LoggerFactory.getLogger(ExportLabService.class);
    private final ExportDataMapper mapper;
    private final Path outputDirectory;
    private final int maxRetainedTasks;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final LongAdder totalQueries = new LongAdder();

    public ExportLabService(ExportDataMapper mapper,
                            @Value("${export-lab.output-dir}") String outputDirectory,
                            @Value("${export-lab.max-retained-tasks:30}") int maxRetainedTasks,
                            @Qualifier("applicationTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.mapper = mapper;
        this.outputDirectory = Path.of(outputDirectory).toAbsolutePath().normalize();
        this.maxRetainedTasks = maxRetainedTasks;
        this.executor = executor;
    }

    public ExportTaskView submit(ExportTaskRequest request) {
        NormalizedRequest normalized = normalize(request);
        trimCompletedTasks();
        TaskState state = new TaskState(UUID.randomUUID().toString(), normalized);
        tasks.put(state.id, state);
        try {
            executor.execute(() -> runTask(state));
        } catch (RejectedExecutionException error) {
            tasks.remove(state.id);
            throw new IllegalStateException("通用异步执行器已经关闭，任务无法提交");
        }
        log.info("EXPORT_TASK_SUBMITTED taskId={} mode={} queryStrategy={} pageSize={} fileRowLimit={} duration={}s saveFiles={} oomInjectionKb={}",
                state.id, normalized.mode, normalized.queryStrategy, normalized.pageSize, normalized.fileRowLimit,
                normalized.maxDurationSeconds, normalized.saveFiles, normalized.oomInjectionKbPerOuterLoop);
        return state.toView();
    }

    public ExportTaskView stop(String taskId) {
        TaskState state = requireTask(taskId);
        state.stopRequested.set(true);
        state.message = "收到停止请求；任务会在当前 SQL 或当前页写入完成后退出";
        log.warn("EXPORT_TASK_STOP_REQUESTED taskId={} status={}", taskId, state.status);
        return state.toView();
    }

    public List<ExportTaskView> taskViews() {
        return tasks.values().stream()
                .sorted(Comparator.comparing((TaskState state) -> state.submittedAt).reversed())
                .map(TaskState::toView)
                .toList();
    }

    public ThreadPoolExecutor executor() {
        return executor.getThreadPoolExecutor();
    }

    public long totalQueries() {
        return totalQueries.sum();
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    private void runTask(TaskState state) {
        state.status = "RUNNING";
        state.startedAt = Instant.now();
        state.currentPhase = "CAPTURE_SNAPSHOT_BOUNDARY";
        state.message = "任务正在导出线程中执行";
        try {
            Files.createDirectories(outputDirectory.resolve(state.id));
            state.snapshotUpperBound = mapper.maxId();
            log.info("EXPORT_TASK_STARTED taskId={} snapshotUpperBound={} thread={}",
                    state.id, state.snapshotUpperBound, Thread.currentThread().getName());

            boolean hasMore = true;
            while (!state.stopRequested.get()) {
                if (expired(state)) {
                    state.status = "SAFETY_STOPPED";
                    state.message = "达到实验最长运行时间，保护器终止了错误循环";
                    break;
                }

                state.outerLoops.incrementAndGet();
                state.currentPhase = "CREATE_WORKBOOK";
                SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                int fileRows = 0;
                Sheet sheet = null;
                try {
                    while (fileRows < state.request.fileRowLimit && !state.stopRequested.get()) {
                        if (expired(state)) {
                            break;
                        }
                        state.currentPhase = state.request.queryStrategy == QueryStrategy.UNINDEXED_DEEP_OFFSET
                                ? "QUERY_UNINDEXED_DEEP_OFFSET"
                                : "QUERY_INDEXED_CURSOR";
                        int queryLimit = Math.min(state.request.pageSize, state.request.fileRowLimit - fileRows);
                        List<ExportDataRow> page = queryPage(state, queryLimit);
                        if (page.isEmpty()) {
                            hasMore = false;
                            state.emptyQueryCount.incrementAndGet();
                            if (state.request.mode != Mode.FIXED) {
                                logRunawayProgress(state);
                            } else {
                                log.info("EXPORT_END_REACHED taskId={} cursor={} outerLoops={}",
                                        state.id, state.cursorId.get(), state.outerLoops.get());
                            }
                            break;
                        }

                        if (sheet == null) {
                            sheet = workbook.createSheet("data");
                            writeHeader(sheet);
                        }
                        state.currentPhase = "WRITE_SXSSF_TEMP";
                        writePage(sheet, fileRows + 1, page);
                        fileRows += page.size();
                        state.rowsWritten.addAndGet(page.size());

                        if (state.request.queryStrategy == QueryStrategy.UNINDEXED_DEEP_OFFSET) {
                            state.cursorId.addAndGet(page.size());
                        } else {
                            long nextCursor = page.get(page.size() - 1).id();
                            if (nextCursor <= state.cursorId.get()) {
                                throw new IllegalStateException("游标没有前进：lastId=" + state.cursorId.get() + ", nextId=" + nextCursor);
                            }
                            state.cursorId.set(nextCursor);
                        }
                    }

                    if (fileRows > 0 && state.request.saveFiles) {
                        state.currentPhase = "SAVE_XLSX";
                        saveWorkbook(state, workbook);
                    }
                } finally {
                    state.currentPhase = "CLOSE_AND_DISPOSE";
                    try {
                        workbook.close();
                    } finally {
                        workbook.dispose();
                    }
                }

                // 正确代码必须在这里使用 hasMore 退出。BUGGY_MISSING_BREAK 模式故意漏掉。
                if (state.request.mode == Mode.FIXED && !hasMore) {
                    state.status = "COMPLETED";
                    state.message = "数据读取完成，正确退出外层循环";
                    break;
                }

                if (state.request.mode == Mode.BUGGY_MISSING_BREAK && !hasMore) {
                    injectOptionalOom(state);
                }
            }

            if (state.stopRequested.get()) {
                state.status = "CANCELLED";
                state.message = "任务已通过实验停止开关结束";
                state.retained.clear();
                state.retainedChunks.set(0);
                state.retainedBytes.set(0);
            } else if ("RUNNING".equals(state.status)) {
                state.status = "COMPLETED";
                state.message = "任务已完成";
            }
        } catch (OutOfMemoryError error) {
            state.status = "OOM";
            state.message = "实验线程触发 OutOfMemoryError；是否退出 JVM 取决于启动参数 ExitOnOutOfMemoryError";
            state.finishedAt = Instant.now();
            log.error("EXPORT_TASK_OOM taskId={} retainedBytes={} rows={} queries={}",
                    state.id, state.retainedBytes.get(), state.rowsWritten.get(), state.queryCount.get());
            // Without ExitOnOutOfMemoryError the worker Future would swallow the Error. Release the
            // deliberate lab retention so the surviving JVM has a chance to recover after the dump.
            state.retained.clear();
            throw error;
        } catch (Throwable error) {
            state.status = "FAILED";
            state.message = error.getClass().getSimpleName() + ": " + error.getMessage();
            log.error("EXPORT_TASK_FAILED taskId={} phase={}", state.id, state.currentPhase, error);
        } finally {
            if (state.finishedAt == null) {
                state.finishedAt = Instant.now();
            }
            state.currentPhase = "FINISHED";
            log.info("EXPORT_TASK_FINISHED taskId={} status={} elapsedMs={} rows={} queries={} emptyQueries={} outerLoops={} files={} outputBytes={}",
                    state.id, state.status, state.elapsedMillis(), state.rowsWritten.get(), state.queryCount.get(),
                    state.emptyQueryCount.get(), state.outerLoops.get(), state.filesWritten.get(), state.outputBytes.get());
        }
    }

    private List<ExportDataRow> queryPage(TaskState state, int pageSize) {
        state.queryCount.incrementAndGet();
        totalQueries.increment();
        if (state.request.queryStrategy == QueryStrategy.UNINDEXED_DEEP_OFFSET) {
            return mapper.queryUnindexedOffsetPage(state.cursorId.get(), state.snapshotUpperBound, pageSize);
        }
        return mapper.queryPage(state.cursorId.get(), state.snapshotUpperBound, pageSize);
    }

    private void injectOptionalOom(TaskState state) {
        int chunkKb = state.request.oomInjectionKbPerOuterLoop;
        if (chunkKb <= 0) {
            return;
        }
        byte[] retained = new byte[chunkKb * 1024];
        retained[0] = 1;
        state.retained.add(retained);
        state.retainedChunks.incrementAndGet();
        state.retainedBytes.addAndGet(retained.length);
    }

    private void saveWorkbook(TaskState state, SXSSFWorkbook workbook) throws IOException {
        int fileNumber = Math.toIntExact(state.filesWritten.incrementAndGet());
        Path path = outputDirectory.resolve(state.id).resolve(String.format("report-%03d.xlsx", fileNumber));
        try (OutputStream output = Files.newOutputStream(path)) {
            workbook.write(output);
        }
        long bytes = Files.size(path);
        state.outputBytes.addAndGet(bytes);
        log.info("EXPORT_FILE_SAVED taskId={} file={} bytes={} cursor={} totalRows={}",
                state.id, path, bytes, state.cursorId.get(), state.rowsWritten.get());
    }

    private void writeHeader(Sheet sheet) {
        Row header = sheet.createRow(0);
        String[] names = {"ID", "订单号", "客户", "区域", "金额", "状态", "说明", "创建时间"};
        for (int index = 0; index < names.length; index++) {
            header.createCell(index).setCellValue(names[index]);
        }
    }

    private void writePage(Sheet sheet, int startRow, List<ExportDataRow> page) {
        int rowNumber = startRow;
        for (ExportDataRow data : page) {
            Row row = sheet.createRow(rowNumber++);
            set(row, 0, data.id());
            set(row, 1, data.orderNo());
            set(row, 2, data.customerName());
            set(row, 3, data.region());
            row.createCell(4).setCellValue(data.amount().doubleValue());
            set(row, 5, data.status());
            set(row, 6, data.description());
            set(row, 7, data.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
    }

    private void set(Row row, int column, long value) {
        row.createCell(column).setCellValue(value);
    }

    private void set(Row row, int column, String value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
    }

    private void logRunawayProgress(TaskState state) {
        long emptyQueries = state.emptyQueryCount.get();
        if (emptyQueries <= 8 || (emptyQueries & (emptyQueries - 1)) == 0) {
            log.warn("EXPORT_EMPTY_PAGE_LOOP taskId={} emptyQueries={} outerLoops={} cursor={} hint=missing_outer_break",
                    state.id, emptyQueries, state.outerLoops.get(), state.cursorId.get());
        }
    }

    private boolean expired(TaskState state) {
        return state.request.maxDurationSeconds > 0
                && state.startedAt != null
                && Duration.between(state.startedAt, Instant.now()).toSeconds() >= state.request.maxDurationSeconds;
    }

    private TaskState requireTask(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) {
            throw new IllegalArgumentException("任务不存在：" + taskId);
        }
        return state;
    }

    private NormalizedRequest normalize(ExportTaskRequest raw) {
        if (raw == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Mode mode;
        try {
            mode = Mode.valueOf((raw.mode() == null ? "FIXED" : raw.mode()).toUpperCase(Locale.ROOT));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("mode 只能是 FIXED 或 BUGGY_MISSING_BREAK");
        }
        QueryStrategy queryStrategy;
        try {
            queryStrategy = QueryStrategy.valueOf((raw.queryStrategy() == null
                    ? "INDEXED_CURSOR"
                    : raw.queryStrategy()).toUpperCase(Locale.ROOT));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("queryStrategy 只能是 INDEXED_CURSOR 或 UNINDEXED_DEEP_OFFSET");
        }
        int pageSize = raw.pageSize() == null ? 1000 : raw.pageSize();
        int fileRowLimit = raw.fileRowLimit() == null ? 50_000 : raw.fileRowLimit();
        int duration = raw.maxDurationSeconds() == null ? 0 : raw.maxDurationSeconds();
        boolean saveFiles = raw.saveFiles() == null || raw.saveFiles();
        int oomKb = raw.oomInjectionKbPerOuterLoop() == null ? 0 : raw.oomInjectionKbPerOuterLoop();

        if (pageSize < 10 || pageSize > 5000) {
            throw new IllegalArgumentException("pageSize 必须在 10 到 5000 之间");
        }
        if (fileRowLimit < pageSize || fileRowLimit > 500_000) {
            throw new IllegalArgumentException("fileRowLimit 必须不小于 pageSize，且不超过 500000");
        }
        if (duration < 0 || (duration > 0 && duration < 10) || duration > 600) {
            throw new IllegalArgumentException("maxDurationSeconds 必须为 0（不限制），或在 10 到 600 之间");
        }
        if (oomKb < 0 || oomKb > 1024) {
            throw new IllegalArgumentException("oomInjectionKbPerOuterLoop 必须在 0 到 1024 之间");
        }
        if (oomKb > 0 && mode != Mode.BUGGY_MISSING_BREAK) {
            throw new IllegalArgumentException("OOM 注入只能和 BUGGY_MISSING_BREAK 一起使用");
        }
        if (oomKb > 0 && !"ENABLE_OOM".equals(raw.dangerConfirmation())) {
            throw new IllegalArgumentException("启用额外 OOM 注入时 dangerConfirmation 必须为 ENABLE_OOM");
        }
        return new NormalizedRequest(mode, queryStrategy, pageSize, fileRowLimit, duration, saveFiles, oomKb);
    }

    private void trimCompletedTasks() {
        if (tasks.size() < maxRetainedTasks) {
            return;
        }
        tasks.values().stream()
                .filter(TaskState::isTerminal)
                .sorted(Comparator.comparing(state -> state.submittedAt))
                .limit(Math.max(1, tasks.size() - maxRetainedTasks + 1L))
                .forEach(state -> tasks.remove(state.id));
    }

    @PreDestroy
    public void shutdown() {
        tasks.values().forEach(state -> state.stopRequested.set(true));
    }

    private enum Mode {
        FIXED,
        BUGGY_MISSING_BREAK
    }

    private enum QueryStrategy {
        INDEXED_CURSOR,
        UNINDEXED_DEEP_OFFSET
    }

    private record NormalizedRequest(
            Mode mode,
            QueryStrategy queryStrategy,
            int pageSize,
            int fileRowLimit,
            int maxDurationSeconds,
            boolean saveFiles,
            int oomInjectionKbPerOuterLoop
    ) {
    }

    private static final class TaskState {
        private final String id;
        private final NormalizedRequest request;
        private final Instant submittedAt = Instant.now();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicLong cursorId = new AtomicLong();
        private final AtomicLong rowsWritten = new AtomicLong();
        private final AtomicLong queryCount = new AtomicLong();
        private final AtomicLong emptyQueryCount = new AtomicLong();
        private final AtomicLong outerLoops = new AtomicLong();
        private final AtomicLong filesWritten = new AtomicLong();
        private final AtomicLong outputBytes = new AtomicLong();
        private final AtomicLong retainedBytes = new AtomicLong();
        private final AtomicLong retainedChunks = new AtomicLong();
        private final List<byte[]> retained = new ArrayList<>();
        private volatile String status = "QUEUED";
        private volatile String currentPhase = "WAITING_FOR_EXPORT_THREAD";
        private volatile String message = "任务已提交到应用异步执行器";
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile long snapshotUpperBound;

        private TaskState(String id, NormalizedRequest request) {
            this.id = id;
            this.request = request;
        }

        private long elapsedMillis() {
            if (startedAt == null) {
                return 0;
            }
            return Duration.between(startedAt, finishedAt == null ? Instant.now() : finishedAt).toMillis();
        }

        private boolean isTerminal() {
            return !"QUEUED".equals(status) && !"RUNNING".equals(status);
        }

        private ExportTaskView toView() {
            return new ExportTaskView(
                    id,
                    request.mode.name(),
                    request.queryStrategy.name(),
                    status,
                    submittedAt,
                    startedAt,
                    finishedAt,
                    elapsedMillis(),
                    snapshotUpperBound,
                    cursorId.get(),
                    rowsWritten.get(),
                    queryCount.get(),
                    emptyQueryCount.get(),
                    outerLoops.get(),
                    (int) filesWritten.get(),
                    outputBytes.get(),
                    (int) retainedChunks.get(),
                    retainedBytes.get(),
                    stopRequested.get(),
                    currentPhase,
                    message
            );
        }
    }
}
