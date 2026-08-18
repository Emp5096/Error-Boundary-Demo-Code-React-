package com.xy.interview.demo.exportlab;

import java.time.Instant;

public record DiagnosticEvidence(
        String type,
        Instant capturedAt,
        String path,
        String commandEquivalent,
        String preview
) {
}
