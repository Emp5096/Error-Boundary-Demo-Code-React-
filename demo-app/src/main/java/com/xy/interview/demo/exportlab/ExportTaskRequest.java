package com.xy.interview.demo.exportlab;

public record ExportTaskRequest(
        String mode,
        String queryStrategy,
        Integer pageSize,
        Integer fileRowLimit,
        Integer maxDurationSeconds,
        Boolean saveFiles,
        Integer oomInjectionKbPerOuterLoop,
        String dangerConfirmation
) {
}
