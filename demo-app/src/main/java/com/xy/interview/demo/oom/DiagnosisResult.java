package com.xy.interview.demo.oom;

import java.util.List;

public record DiagnosisResult(
        boolean correct,
        String selectedCause,
        String actualCause,
        String title,
        String reasoning,
        List<String> decisiveEvidence,
        List<String> immediateActions,
        List<String> longTermFixes
) {
}
