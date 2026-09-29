package com.vishwas.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.BankSetup;
import com.vishwas.memory.ConsolidationSignal;
import com.vishwas.memory.MemoryStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Receives Hindsight's {@code consolidation.completed} webhook (registered only when a public URL is
 * configured) and wakes the history loader instead of waiting for the next poll. The body is verified with
 * the {@code X-Hindsight-Signature: sha256=<hex hmac>} header when a secret is set.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ConsolidationSignal signal;
    private final MemoryStats stats;
    private final VishwasProperties props;
    private final ObjectMapper json;

    public WebhookController(ConsolidationSignal signal, MemoryStats stats, VishwasProperties props, ObjectMapper json) {
        this.signal = signal;
        this.stats = stats;
        this.props = props;
        this.json = json;
    }

    @PostMapping(BankSetup.WEBHOOK_PATH)
    public ResponseEntity<Map<String, Object>> receive(@RequestBody byte[] body,
                                                       @RequestHeader(value = "X-Hindsight-Signature", required = false) String signature)
            throws Exception {
        String secret = props.memory() == null ? null : props.memory().webhookSecret();
        if (secret != null && !secret.isBlank() && !valid(body, signature, secret)) {
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        JsonNode event = json.readTree(body);
        if ("consolidation.completed".equals(event.path("event").asText())) {
            signal.completed(Instant.now());
            stats.invalidate();
            log.info("Consolidation completed for bank {} ({} observations updated)", event.path("bank_id").asText(),
                    event.path("data").path("observations_updated").asText("?"));
        }
        return ResponseEntity.ok(Map.of("received", true));
    }

    static boolean valid(byte[] body, String header, String secret) throws Exception {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }
}
