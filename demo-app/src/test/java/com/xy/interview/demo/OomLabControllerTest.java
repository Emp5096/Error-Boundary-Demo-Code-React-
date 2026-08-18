package com.xy.interview.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
class OomLabControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsIdleStatusBeforeAnExperimentStarts() throws Exception {
        mockMvc.perform(get("/api/oom/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"));
    }

    @Test
    void rejectsUnsafeHeapLimit() throws Exception {
        mockMvc.perform(post("/api/oom/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heapMb\":8,\"chunkKb\":1024,\"intervalMs\":80}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void exposesTheGeneralIncidentLabAlias() throws Exception {
        mockMvc.perform(get("/api/lab/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.scenario").value("NONE"));
    }

    @Test
    void rejectsUnknownIncidentScenario() throws Exception {
        mockMvc.perform(post("/api/lab/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"DATABASE_DEADLOCK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
