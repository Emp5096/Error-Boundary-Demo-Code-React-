package com.xy.interview.demo.exportlab;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ExportDataAdminService {

    private static final int BATCH_SIZE = 500;
    private static final String[] REGIONS = {"华东", "华南", "华北", "西南", "东北"};
    private static final String[] STATUSES = {"CREATED", "PAID", "SHIPPED", "COMPLETED"};

    private final ExportDataMapper mapper;
    private volatile DataSummary cachedSummary = new DataSummary(-1, -1);

    public ExportDataAdminService(ExportDataMapper mapper) {
        this.mapper = mapper;
    }

    public DataSummary summary() {
        DataSummary refreshed = new DataSummary(mapper.countRows(), mapper.maxId());
        cachedSummary = refreshed;
        return refreshed;
    }

    public DataSummary cachedSummary() {
        DataSummary current = cachedSummary;
        if (current.rows() >= 0) {
            return current;
        }
        synchronized (this) {
            return cachedSummary.rows() >= 0 ? cachedSummary : summary();
        }
    }

    @Transactional
    public DataSummary seedTo(long targetRows) {
        if (targetRows < 1 || targetRows > 500_000) {
            throw new IllegalArgumentException("targetRows 必须在 1 到 500000 之间");
        }

        long existing = mapper.countRows();
        long sequence = mapper.maxId() + 1;
        while (existing < targetRows) {
            int size = (int) Math.min(BATCH_SIZE, targetRows - existing);
            List<ExportSeedRow> rows = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                long number = sequence + index;
                rows.add(new ExportSeedRow(
                        "ORD-" + String.format("%010d", number),
                        "测试客户-" + number,
                        REGIONS[(int) (number % REGIONS.length)],
                        BigDecimal.valueOf((number % 100_000) + 100, 2),
                        STATUSES[(int) (number % STATUSES.length)],
                        "用于 MyBatis + SXSSFWorkbook 导出故障实验的数据行 " + number,
                        LocalDateTime.now().minusSeconds(number % 2_592_000)
                ));
            }
            mapper.insertBatch(rows);
            existing += size;
            sequence += size;
        }
        return summary();
    }

    public record DataSummary(long rows, long maxId) {
    }
}
