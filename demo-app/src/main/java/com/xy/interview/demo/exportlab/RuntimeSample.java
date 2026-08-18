package com.xy.interview.demo.exportlab;

import java.time.Instant;

public record RuntimeSample(
        Instant timestamp,
        double processCpuPercent,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long gcCount,
        long gcTimeMillis,
        int liveThreads,
        int poolActive,
        int poolQueue,
        int hikariActive,
        int hikariIdle,
        long tempBytes,
        long outputBytes,
        long totalQueries
) {
}
