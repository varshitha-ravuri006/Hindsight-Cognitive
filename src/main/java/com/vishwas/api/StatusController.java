package com.vishwas.api;

import com.vishwas.advisor.BaselineAdvisor;
import com.vishwas.config.VishwasProperties;
import com.vishwas.demo.HistoryLoader;
import com.vishwas.demo.SeedCatalog;
import com.vishwas.llm.GroqClient;
import com.vishwas.memory.MemoryHealth;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.MemoryStats;
import com.vishwas.memory.hindsight.HindsightClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Header chips (/api/status), the /health page data (/api/health) and a liveness probe. */
@RestController
@RequestMapping("/api")
public class StatusController {

    private final MemoryHealth memoryHealth;
    private final MemoryStats stats;
    private final MemoryPublisher publisher;
    private final BaselineAdvisor baseline;
    private final HistoryLoader history;
    private final HindsightClient hindsight;
    private final GroqClient groq;
    private final JdbcTemplate jdbc;
    private final VishwasProperties props;
    private final ExecutorService io;

    public StatusController(MemoryHealth memoryHealth, MemoryStats stats, MemoryPublisher publisher, BaselineAdvisor baseline,
                            HistoryLoader history, HindsightClient hindsight, GroqClient groq, JdbcTemplate jdbc,
                            VishwasProperties props, ExecutorService ioExecutor) {
        this.memoryHealth = memoryHealth;
        this.stats = stats;
        this.publisher = publisher;
        this.baseline = baseline;
        this.history = history;
        this.hindsight = hindsight;
        this.groq = groq;
        this.jdbc = jdbc;
        this.props = props;
        this.io = ioExecutor;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        MemoryStats.Counts counts = stats.counts();
        s.put("memory", memoryHealth.state().name());
        s.put("memoryError", memoryHealth.lastError() != null ? memoryHealth.lastError() : publisher.lastError());
        s.put("baseline", baseline.available() ? "READY" : "NOT_CONFIGURED");
        s.put("bankId", props.hindsight().bankId());
        s.put("memories", counts.memories());
        s.put("observations", counts.observations());
        s.put("history", history.status());
        s.put("company", props.company());
        s.put("livePeriod", SeedCatalog.LIVE_PERIOD);
        s.put("nextPeriod", SeedCatalog.NEXT_PERIOD);
        s.put("resetEnabled", props.demo().resetEnabled());
        return s;
    }

    @GetMapping("/health/live")
    public Map<String, Object> live() {
        return Map.of("status", "UP");
    }

    /** Checks every dependency in parallel with short timeouts, so the health page itself can never hang. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        CompletableFuture<Boolean> hs = CompletableFuture.supplyAsync(() -> hindsight.configured() && hindsight.serviceHealthy(), io);
        CompletableFuture<Boolean> gq = CompletableFuture.supplyAsync(groq::healthy, io);
        CompletableFuture<MemoryStats.Counts> counts = CompletableFuture.supplyAsync(stats::counts, io);
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("database", database());
        h.put("hindsight", Map.of(
                "configured", hindsight.configured(),
                "reachable", get(hs, false),
                "bankSetup", memoryHealth.state().name(),
                "error", String.valueOf(memoryHealth.lastError()),
                "bankId", props.hindsight().bankId(),
                "baseUrl", props.hindsight().baseUrl()));
        MemoryStats.Counts c = get(counts, new MemoryStats.Counts(null, null, null, "timeout"));
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("memories", c.memories());
        mem.put("observations", c.observations());
        mem.put("error", c.error());
        h.put("memoryCounts", mem);
        h.put("groq", Map.of("configured", groq.configured(), "reachable", get(gq, false), "model", props.groq().model()));
        h.put("history", history.status());
        return h;
    }

    private Map<String, Object> database() {
        Map<String, Object> db = new LinkedHashMap<>();
        try {
            db.put("reachable", true);
            for (String t : List.of("vendor", "invoice_record", "mismatch", "recommendation", "vendor_communication")) {
                db.put(t, jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class));
            }
        } catch (RuntimeException e) {
            db.put("reachable", false);
            db.put("error", e.getMessage());
        }
        return db;
    }

    private static <T> T get(CompletableFuture<T> f, T fallback) {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }
}
