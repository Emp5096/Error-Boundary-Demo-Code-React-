package com.xy.interview.demo.oom;

import java.time.Instant;

public record EvidenceView(
        String type,
        String title,
        String command,
        Instant collectedAt,
        boolean available,
        String summary,
        String content,
        String caution
) {
}
