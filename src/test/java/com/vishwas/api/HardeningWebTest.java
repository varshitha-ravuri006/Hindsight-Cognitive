package com.vishwas.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Demo hardening at the HTTP edge: the health page, clean JSON errors, and the request id on every response. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:vishwas-web-it;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class HardeningWebTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthPageAndHealthDataWorkWithoutAnyKeys() throws Exception {
        mvc.perform(get("/health")).andExpect(forwardedUrl("/health.html"));
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.database.reachable").value(true))
                .andExpect(jsonPath("$.hindsight.configured").value(false))
                .andExpect(jsonPath("$.groq.configured").value(false));
        mvc.perform(get("/api/health/live")).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void errorsAreCleanJsonWithARequestId() throws Exception {
        mvc.perform(get("/api/cases/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No case 999999"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(header().exists("X-Request-Id"));
        mvc.perform(get("/api/periods/not-a-month/workspace")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        mvc.perform(post("/api/assistant/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }

    @Test
    void reconcilingBeforeTheHistoryIsLoadedIsAClearConflictNotACrash() throws Exception {
        mvc.perform(post("/api/periods/2026-08/reconcile")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Load the history first (Step 1)."));
    }

    @Test
    void snapshotInfoSaysWhetherARecordingExists() throws Exception {
        mvc.perform(get("/api/snapshot/info")).andExpect(jsonPath("$.available").isBoolean());
    }
}
