package com.xy.interview.demo.oom;

public record OomExperimentRequest(
        Integer heapMb,
        Integer chunkKb,
        Integer intervalMs,
        String scenario,
        Integer intensity,
        Integer durationSeconds
) {
}
