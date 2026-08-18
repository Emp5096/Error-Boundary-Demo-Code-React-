package com.xy.interview.demo.exportlab;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExportDataRow(
        long id,
        String orderNo,
        String customerName,
        String region,
        BigDecimal amount,
        String status,
        String description,
        LocalDateTime createdAt
) {
}
