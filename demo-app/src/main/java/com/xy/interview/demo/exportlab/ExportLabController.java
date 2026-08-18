package com.xy.interview.demo.exportlab;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/export-lab")
public class ExportLabController {

    private final ExportLabService exportLabService;
    private final ExportLabMetricsService metricsService;
    private final ExportDataAdminService dataAdminService;
    private final ExportDataMapper mapper;
    private final ExportLabDiagnosticService diagnosticService;

    public ExportLabController(ExportLabService exportLabService,
                               ExportLabMetricsService metricsService,
                               ExportDataAdminService dataAdminService,
                               ExportDataMapper mapper,
                               ExportLabDiagnosticService diagnosticService) {
        this.exportLabService = exportLabService;
        this.metricsService = metricsService;
        this.dataAdminService = dataAdminService;
        this.mapper = mapper;
        this.diagnosticService = diagnosticService;
    }

    @GetMapping("/status")
    public ExportLabStatus status() {
        return metricsService.status();
    }

    @PostMapping("/tasks")
    public ResponseEntity<ExportTaskView> submit(@RequestBody ExportTaskRequest request) {
        return ResponseEntity.accepted().body(exportLabService.submit(request));
    }

    @DeleteMapping("/tasks/{taskId}")
    public ExportTaskView stop(@PathVariable String taskId) {
        return exportLabService.stop(taskId);
    }

    @PostMapping("/data/seed")
    public ExportDataAdminService.DataSummary seed(@RequestParam(defaultValue = "100000") long targetRows) {
        return dataAdminService.seedTo(targetRows);
    }

    @GetMapping("/probe/jvm")
    public JvmProbeResult jvmProbe() {
        long started = System.nanoTime();
        return new JvmProbeResult(
                "pong",
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                Instant.now()
        );
    }

    @GetMapping({"/probe", "/probe/mysql"})
    public MysqlProbeResult mysqlProbe() {
        long started = System.nanoTime();
        int value = mapper.probe();
        return new MysqlProbeResult(value, Duration.ofNanos(System.nanoTime() - started).toMillis(), Instant.now());
    }

    @GetMapping("/probe/table")
    public TableProbeResult tableProbe(@RequestParam(defaultValue = "0") long lastId,
                                       @RequestParam(defaultValue = "100") int limit) {
        if (lastId < 0) {
            throw new IllegalArgumentException("lastId 不能小于 0");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit 必须在 1 到 500 之间");
        }
        long started = System.nanoTime();
        List<ExportDataRow> rows = mapper.queryBusinessProbe(lastId, limit);
        long rtMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new TableProbeResult(
                rows,
                rows.size(),
                rows.isEmpty() ? null : rows.get(0).id(),
                rows.isEmpty() ? null : rows.get(rows.size() - 1).id(),
                rtMillis,
                Instant.now()
        );
    }

    @PostMapping("/diagnostics/thread-dump")
    public DiagnosticEvidence threadDump() {
        return diagnosticService.captureThreadDump();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", error.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("LAB_RESOURCE_LIMIT", error.getMessage(), Instant.now()));
    }

    public record JvmProbeResult(String value, long rtMillis, Instant timestamp) {
    }

    public record MysqlProbeResult(int databaseValue, long rtMillis, Instant timestamp) {
    }

    public record TableProbeResult(
            List<ExportDataRow> rows,
            int rowCount,
            Long firstId,
            Long lastId,
            long rtMillis,
            Instant timestamp
    ) {
    }

    public record ApiError(String code, String message, Instant timestamp) {
    }
}
