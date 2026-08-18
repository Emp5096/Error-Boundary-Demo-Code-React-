package com.xy.interview.demo.exportlab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExportLabProbeControllerTest {

    private MockMvc mockMvc;
    private ExportDataMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(ExportDataMapper.class);
        ExportLabController controller = new ExportLabController(
                mock(ExportLabService.class),
                mock(ExportLabMetricsService.class),
                mock(ExportDataAdminService.class),
                mapper,
                mock(ExportLabDiagnosticService.class)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void jvmProbeDoesNotNeedTheDatabase() throws Exception {
        mockMvc.perform(get("/api/export-lab/probe/jvm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("pong"));
    }

    @Test
    void mysqlProbeUsesSelectOne() throws Exception {
        when(mapper.probe()).thenReturn(1);

        mockMvc.perform(get("/api/export-lab/probe/mysql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseValue").value(1));
    }

    @Test
    void tableProbeReturnsMappedBusinessRows() throws Exception {
        ExportDataRow row = new ExportDataRow(
                11L,
                "ORD-0000000011",
                "测试客户-11",
                "华东",
                new BigDecimal("18.80"),
                "PAID",
                "probe",
                LocalDateTime.of(2026, 8, 16, 20, 0)
        );
        when(mapper.queryBusinessProbe(10L, 100)).thenReturn(List.of(row));

        mockMvc.perform(get("/api/export-lab/probe/table")
                        .param("lastId", "10")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.firstId").value(11))
                .andExpect(jsonPath("$.rows[0].orderNo").value("ORD-0000000011"));
    }

    @Test
    void tableProbeRejectsAnUnsafeLimit() throws Exception {
        mockMvc.perform(get("/api/export-lab/probe/table").param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
