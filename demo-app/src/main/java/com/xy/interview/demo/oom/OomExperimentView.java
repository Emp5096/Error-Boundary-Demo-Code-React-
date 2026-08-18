package com.xy.interview.demo.oom;

import java.time.Instant;
import java.util.List;

public record OomExperimentView(
        String id,
        String status,
        String scenario,
        boolean blindMode,
        long pid,
        long heapLimitBytes,
        long chunkBytes,
        long allocatedBytes,
        long usedHeapBytes,
        long maxHeapBytes,
        double cpuPercent,
        long gcCollections,
        long gcTimeMillis,
        int liveThreads,
        int poolActive,
        int poolQueue,
        long probeRtMillis,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        Integer exitCode,
        String errorType,
        String errorMessage,
        List<String> logs,
        List<MemorySample> samples,
        List<String> collectedEvidence,
        boolean diagnosisSubmitted
) {
}
