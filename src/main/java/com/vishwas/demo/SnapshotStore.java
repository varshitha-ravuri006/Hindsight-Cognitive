package com.vishwas.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vishwas.config.VishwasProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Stores the recording of a real run (every API response the UI received, in order per endpoint) so the demo can
 * be replayed offline if Hindsight or Groq is unreachable. The file lives next to the database; a recording
 * bundled on the classpath (snapshot/demo-run.json) is used when none has been recorded locally.
 */
@Service
public class SnapshotStore {

    public static final String FORMAT = "vishwas-snapshot/1";
    static final int MAX_BYTES = 12 * 1024 * 1024;
    static final int MAX_PER_ENDPOINT = 400;

    public record Info(boolean available, String source, String recordedAt, String bankId, int endpoints, int responses) {
    }

    private final ObjectMapper json;
    private final Path file;
    private final Clock clock;

    public SnapshotStore(ObjectMapper json, VishwasProperties props, Clock clock) {
        this.json = json;
        this.file = Path.of(props.demo().snapshotFile());
        this.clock = clock;
    }

    /** Validates and saves a recording from the UI. */
    public Info save(JsonNode recording, String bankId) {
        if (recording == null || !recording.path("entries").isObject() || recording.path("entries").isEmpty()) {
            throw new IllegalArgumentException("The recording has no responses.");
        }
        ObjectNode out = json.createObjectNode();
        out.put("format", FORMAT);
        out.put("recordedAt", Instant.now(clock).toString());
        out.put("bankId", bankId);
        ObjectNode entries = out.putObject("entries");
        Iterator<Map.Entry<String, JsonNode>> it = recording.path("entries").fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getKey().matches("(GET|POST) /api/\\S+") || e.getKey().contains("/api/snapshot")) {
                continue;
            }
            JsonNode seq = e.getValue().isArray() ? e.getValue() : json.createArrayNode().add(e.getValue());
            var trimmed = json.createArrayNode();
            int from = Math.max(0, seq.size() - MAX_PER_ENDPOINT);
            for (int i = from; i < seq.size(); i++) {
                trimmed.add(seq.get(i));
            }
            entries.set(e.getKey(), trimmed);
        }
        try {
            byte[] bytes = json.writeValueAsBytes(out);
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("The recording is too large (" + bytes.length / 1024 / 1024 + " MB).");
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the recording", e);
        }
        return info();
    }

    public Optional<JsonNode> load() {
        try {
            if (Files.exists(file)) {
                return Optional.of(json.readTree(file.toFile()));
            }
            ClassPathResource bundled = new ClassPathResource("snapshot/demo-run.json");
            if (bundled.exists()) {
                try (InputStream in = bundled.getInputStream()) {
                    return Optional.of(json.readTree(in));
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Info info() {
        Optional<JsonNode> s = load();
        if (s.isEmpty()) {
            return new Info(false, null, null, null, 0, 0);
        }
        JsonNode n = s.get();
        int responses = 0;
        for (JsonNode seq : n.path("entries")) {
            responses += seq.size();
        }
        return new Info(true, Files.exists(file) ? "local recording" : "bundled recording", n.path("recordedAt").asText(null),
                n.path("bankId").asText(null), n.path("entries").size(), responses);
    }
}
