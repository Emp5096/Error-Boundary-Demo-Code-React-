package com.xy.interview.demo.oom;

public record MemorySample(
        long elapsedMillis,
        long usedHeapBytes,
        long allocatedBytes,
        double cpuPercent,
        long gcCollections,
        long gcTimeMillis,
        int liveThreads,
        int poolActive,
        int poolQueue,
        long probeRtMillis
) {
}
