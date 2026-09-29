package com.vishwas.memory;

import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.MentalModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configures the Hindsight bank on startup: missions and disposition (bank config API), directives, the two
 * mental models, and the consolidation webhook when a public URL is configured. Runs on a background thread
 * and swallows every failure: the app must boot and serve the UI even when Hindsight is down.
 */
@Component
public class BankSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BankSetup.class);
    public static final String WEBHOOK_PATH = "/api/hooks/hindsight";

    private final HindsightClient hindsight;
    private final VishwasProperties props;
    private final MemoryHealth health;

    public BankSetup(HindsightClient hindsight, VishwasProperties props, MemoryHealth health) {
        this.hindsight = hindsight;
        this.props = props;
        this.health = health;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!hindsight.configured()) {
            health.set(MemoryHealth.State.NOT_CONFIGURED, "HINDSIGHT_API_KEY is not set");
            log.warn("HINDSIGHT_API_KEY not set - Vishwas runs without memory (textbook rules for every mismatch)");
            return;
        }
        Thread.ofVirtual().name("hindsight-bank-setup").start(this::setupQuietly);
    }

    public void setupQuietly() {
        try {
            setup(props.hindsight().bankId());
            health.set(MemoryHealth.State.READY, null);
            log.info("Hindsight bank '{}' ready", props.hindsight().bankId());
        } catch (RuntimeException e) {
            health.set(MemoryHealth.State.UNREACHABLE, e.getMessage());
            log.warn("Hindsight setup failed - continuing without memory: {}", e.getMessage());
        }
    }

    /** Idempotent: safe on every boot, against an existing bank, and right after a reset. */
    public void setup(String bankId) {
        Map<String, Object> bank = new LinkedHashMap<>();
        bank.put("reflect_mission", MemoryDesign.REFLECT_MISSION);
        bank.put("retain_mission", MemoryDesign.RETAIN_MISSION);
        hindsight.upsertBank(bankId, bank);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("reflect_mission", MemoryDesign.REFLECT_MISSION);
        config.put("retain_mission", MemoryDesign.RETAIN_MISSION);
        config.put("observations_mission", MemoryDesign.OBSERVATIONS_MISSION);
        config.put("enable_observations", true);
        config.putAll(MemoryDesign.DISPOSITION);
        try {
            hindsight.updateConfig(bankId, config);
        } catch (HindsightException e) {
            // The config write API can be disabled on a deployment; fall back to the (deprecated) bank fields.
            log.warn("Bank config API refused ({}); applying missions and disposition via PUT /banks", e.getMessage());
            Map<String, Object> legacy = new LinkedHashMap<>(bank);
            legacy.put("observations_mission", MemoryDesign.OBSERVATIONS_MISSION);
            legacy.put("enable_observations", true);
            legacy.putAll(MemoryDesign.DISPOSITION);
            hindsight.upsertBank(bankId, legacy);
        }

        syncDirectives(bankId);
        MemoryDesign.MENTAL_MODELS.forEach(m -> ensureMentalModel(bankId, m));
        registerWebhookIfConfigured(bankId);
    }

    /** Create missing directives by name; update the content when the wording was tuned since last boot. */
    private void syncDirectives(String bankId) {
        List<Map<String, Object>> existing = hindsight.listDirectives(bankId);
        for (MemoryDesign.Directive d : MemoryDesign.DIRECTIVES) {
            Optional<Map<String, Object>> match = existing.stream().filter(e -> d.name().equals(e.get("name"))).findFirst();
            if (match.isEmpty()) {
                hindsight.createDirective(bankId, d.name(), d.content(), d.priority());
            } else if (!Objects.equals(match.get().get("content"), d.content())
                    || !Objects.equals(String.valueOf(match.get().get("priority")), String.valueOf(d.priority()))) {
                hindsight.updateDirective(bankId, String.valueOf(match.get().get("id")), d.content(), d.priority());
            }
        }
    }

    private void ensureMentalModel(String bankId, MemoryDesign.ModelSpec spec) {
        Optional<MentalModel> existing = hindsight.getMentalModel(bankId, spec.id());
        if (existing.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", spec.id());
            body.put("name", spec.name());
            body.put("source_query", spec.sourceQuery());
            body.put("max_tokens", 2048);
            hindsight.createMentalModel(bankId, body);
        } else if (!spec.sourceQuery().equals(existing.get().sourceQuery())) {
            hindsight.updateMentalModel(bankId, spec.id(), Map.of("source_query", spec.sourceQuery()));
        }
    }

    /**
     * Hindsight can call us when consolidation completes, so progress updates without polling. That needs a
     * publicly reachable URL; without one (local dev) the history loader polls /operations instead.
     */
    private void registerWebhookIfConfigured(String bankId) {
        VishwasProperties.Memory memory = props.memory();
        if (memory == null || !memory.webhookEnabled()) {
            return;
        }
        String url = memory.publicBaseUrl().replaceAll("/+$", "") + WEBHOOK_PATH;
        try {
            boolean exists = hindsight.listWebhooks(bankId).stream().anyMatch(w -> url.equals(w.get("url")));
            if (!exists) {
                hindsight.registerWebhook(bankId, url, memory.webhookSecret(), List.of("consolidation.completed"));
                log.info("Registered consolidation webhook {}", url);
            }
        } catch (HindsightException e) {
            log.warn("Webhook registration failed ({}); falling back to polling", e.getMessage());
        }
    }
}
