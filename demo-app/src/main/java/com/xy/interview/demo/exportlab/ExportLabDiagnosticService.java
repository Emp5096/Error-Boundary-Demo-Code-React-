package com.xy.interview.demo.exportlab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class ExportLabDiagnosticService {

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final Path evidenceDirectory;

    public ExportLabDiagnosticService(@Value("${export-lab.evidence-dir}") String evidenceDirectory) {
        this.evidenceDirectory = Path.of(evidenceDirectory).toAbsolutePath().normalize();
    }

    public DiagnosticEvidence captureThreadDump() {
        Instant now = Instant.now();
        String content = formatThreadDump(now);
        try {
            Files.createDirectories(evidenceDirectory);
            Path path = evidenceDirectory.resolve("thread-dump-" + FILE_TIME.format(now) + ".txt");
            Files.writeString(path, content, StandardCharsets.UTF_8);
            String preview = content.length() > 12_000
                    ? content.substring(0, 12_000) + "\n... preview truncated ..."
                    : content;
            return new DiagnosticEvidence(
                    "THREAD_DUMP",
                    now,
                    path.toString(),
                    "jcmd " + ProcessHandle.current().pid() + " Thread.print -l",
                    preview
            );
        } catch (IOException error) {
            throw new IllegalStateException("无法写入线程快照：" + error.getMessage(), error);
        }
    }

    private String formatThreadDump(Instant capturedAt) {
        StringBuilder output = new StringBuilder(64_000);
        output.append(capturedAt).append(" Full thread dump captured by ThreadMXBean\n\n");
        ThreadInfo[] infos = threadMXBean.dumpAllThreads(true, true);
        for (ThreadInfo info : infos) {
            output.append('"').append(info.getThreadName()).append('"')
                    .append(" #").append(info.getThreadId())
                    .append(" state=").append(info.getThreadState());
            if (info.getLockName() != null) {
                output.append(" on ").append(info.getLockName());
            }
            if (info.getLockOwnerName() != null) {
                output.append(" owned by \"").append(info.getLockOwnerName()).append("\"");
            }
            output.append('\n');
            StackTraceElement[] trace = info.getStackTrace();
            for (int index = 0; index < trace.length; index++) {
                output.append("\tat ").append(trace[index]).append('\n');
                for (MonitorInfo monitor : info.getLockedMonitors()) {
                    if (monitor.getLockedStackDepth() == index) {
                        output.append("\t- locked ").append(monitor).append('\n');
                    }
                }
            }
            for (LockInfo synchronizer : info.getLockedSynchronizers()) {
                output.append("\t- locked synchronizer ").append(synchronizer).append('\n');
            }
            output.append('\n');
        }
        return output.toString();
    }
}
