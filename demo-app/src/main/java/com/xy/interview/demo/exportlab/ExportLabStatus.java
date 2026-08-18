package com.xy.interview.demo.exportlab;

import java.util.List;

public record ExportLabStatus(
        long pid,
        long uptimeMillis,
        String javaVersion,
        String tempDirectory,
        String outputDirectory,
        long databaseRows,
        long databaseMaxId,
        RuntimeSample runtime,
        List<RuntimeSample> samples,
        List<ExportTaskView> tasks,
        String onlineCommandHint,
        String warning
) {
}
