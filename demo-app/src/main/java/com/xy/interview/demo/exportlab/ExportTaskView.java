package com.xy.interview.demo.exportlab;

import java.time.Instant;

public record ExportTaskView(
        String id,
        String mode,
        String queryStrategy,
        String status,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        long elapsedMillis,
        long snapshotUpperBound,
        long cursorId,
        long rowsWritten,
        long queryCount,
        long emptyQueryCount,
        long outerLoops,
        int filesWritten,
        long outputBytes,
        int retainedChunks,
        long retainedBytes,
        boolean stopRequested,
        String currentPhase,
        String message
) {
}
