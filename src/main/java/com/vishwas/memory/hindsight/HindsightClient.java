package com.vishwas.memory.hindsight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.config.VishwasProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin REST client for Hindsight Cloud. There is no Java SDK, so this wraps exactly the endpoints Vishwas
 * uses; every path and field was verified against hindsight-docs/static/openapi.json (API v0.10.1).
 * All bank paths live under {@code /v1/default/banks/{bank_id}}; the bank id is a parameter so the dev and
 * demo banks can be switched by configuration alone.
 *
 * <p>Every call has a timeout. Failures surface as {@link HindsightException}; callers degrade gracefully.
 */
@Component
public class HindsightClient {

    private static final Logger log = LoggerFactory.getLogger(HindsightClient.class);

    /** Reflect runs an agent loop and sync retain runs extraction: both need headroom, but never hang. */
    static final Duration SLOW_CALL = Duration.ofSeconds(90);
    static final Duration FAST_CALL = Duration.ofSeconds(20);

    private final HttpClient http;
    private final ObjectMapper json;
    private final VishwasProperties.Hindsight cfg;

    public HindsightClient(HttpClient http, @Qualifier("apiJson") ObjectMapper json, VishwasProperties props) {
        this.http = http;
        this.json = json;
        this.cfg = props.hindsight();
    }

    public boolean configured() {
        return cfg.configured();
    }

    public String defaultBankId() {
        return cfg.bankId();
    }

    // ---------------------------------------------------------------- service + bank

    /** {@code GET /health} (not bank scoped): true when Hindsight answers and its database is reachable. */
    public boolean serviceHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@code PUT /banks/{id}}: creates the bank (idempotent). Missions go through {@link #updateConfig}. */
    public void upsertBank(String bankId, Map<String, Object> body) {
        send("PUT", bankId, "", body, FAST_CALL);
    }

    /** {@code DELETE /banks/{id}}: removes the bank with all memories, documents, directives and models. */
    public void deleteBank(String bankId) {
        send("DELETE", bankId, "", null, SLOW_CALL);
    }

    /**
     * {@code PATCH /banks/{id}/config} with {@code {"updates": {...}}}: reflect_mission, retain_mission,
     * observations_mission, disposition_skepticism, disposition_literalism, disposition_empathy, ...
     */
    public JsonNode updateConfig(String bankId, Map<String, Object> updates) {
        return send("PATCH", bankId, "/config", Map.of("updates", updates), FAST_CALL);
    }

    public JsonNode getConfig(String bankId) {
        return send("GET", bankId, "/config", null, FAST_CALL);
    }

    public JsonNode stats(String bankId) {
        return send("GET", bankId, "/stats", null, FAST_CALL);
    }

    // ---------------------------------------------------------------- directives

    public List<Map<String, Object>> listDirectives(String bankId) {
        JsonNode node = send("GET", bankId, "/directives?limit=100", null, FAST_CALL);
        return convertList(node.path("items"), new TypeReference<>() { });
    }

    public void createDirective(String bankId, String name, String content, int priority) {
        send("POST", bankId, "/directives", Map.of("name", name, "content", content, "priority", priority), FAST_CALL);
    }

    public void updateDirective(String bankId, String directiveId, String content, int priority) {
        send("PATCH", bankId, "/directives/" + enc(directiveId), Map.of("content", content, "priority", priority), FAST_CALL);
    }

    // ---------------------------------------------------------------- retain

    /**
     * {@code POST /memories}. Live events use {@code async=false} so the next question sees them; the bulk
     * history load uses {@code async=true} and polls operations.
     *
     * @return the operation id for async retains, otherwise null
     */
    public String retain(String bankId, List<MemoryItem> items, boolean async) {
        if (items.isEmpty()) {
            return null;
        }
        JsonNode node = send("POST", bankId, "/memories", Map.of("items", items, "async", async), SLOW_CALL);
        return text(node, "operation_id");
    }

    /**
     * {@code POST /files/retain} (multipart): {@code files} parts plus a {@code request} part holding the
     * FileRetainRequest JSON with one {@code files_metadata} entry per file. Always asynchronous.
     *
     * @return the operation ids to poll
     */
    public List<String> retainFiles(String bankId, List<FileUpload> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> meta = new ArrayList<>();
        for (FileUpload f : files) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document_id", f.documentId());
            m.put("context", f.context());
            m.put("timestamp", f.timestamp());
            m.put("tags", f.tags());
            m.put("metadata", f.metadata());
            m.values().removeIf(java.util.Objects::isNull);
            meta.add(m);
        }
        String boundary = "vishwas-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            writePart(body, boundary, "request", null, "application/json",
                    json.writeValueAsBytes(Map.of("files_metadata", meta)));
            for (FileUpload f : files) {
                writePart(body, boundary, "files", f.filename(), f.contentType(), f.bytes());
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HindsightException("Could not build file upload: " + e.getMessage(), 0, e);
        }
        HttpRequest.Builder req = request(bankId, "/files/retain", SLOW_CALL)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        JsonNode node = execute("POST", "/files/retain", req.build());
        return convertList(node.path("operation_ids"), new TypeReference<>() { });
    }

    // ---------------------------------------------------------------- recall / reflect

    public RecallResult recall(String bankId, RecallQuery query) {
        return convert(send("POST", bankId, "/memories/recall", query, FAST_CALL.multipliedBy(2)), new TypeReference<>() { });
    }

    public ReflectAnswer reflect(String bankId, ReflectQuery query) {
        return convert(send("POST", bankId, "/reflect", query, SLOW_CALL), new TypeReference<>() { });
    }

    // ---------------------------------------------------------------- memory units + curation

    /**
     * {@code GET /memories/list}. {@code type} is world/experience/observation (null = all); tags are repeated
     * query params; {@code tagsMatch} "exact" selects exactly one observation scope.
     */
    public MemoryPage listMemories(String bankId, String type, List<String> tags, String tagsMatch, String q,
                                   int limit, int offset) {
        StringBuilder path = new StringBuilder("/memories/list?limit=").append(limit).append("&offset=").append(offset);
        if (type != null) {
            path.append("&type=").append(enc(type));
        }
        if (q != null && !q.isBlank()) {
            path.append("&q=").append(enc(q));
        }
        if (tags != null) {
            tags.forEach(t -> path.append("&tags=").append(enc(t)));
            if (tagsMatch != null) {
                path.append("&tags_match=").append(enc(tagsMatch));
            }
        }
        return convert(send("GET", bankId, path.toString(), null, FAST_CALL), new TypeReference<>() { });
    }

    /** Exact count of memory units, optionally of one type ("observation"). /stats can lag recent writes. */
    public int countMemories(String bankId, String type) {
        return listMemories(bankId, type, null, null, null, 1, 0).total();
    }

    public MemoryUnit getMemory(String bankId, String memoryId) {
        return convert(send("GET", bankId, "/memories/" + enc(memoryId), null, FAST_CALL), new TypeReference<>() { });
    }

    /** {@code PATCH /memories/{id}}: edit text and/or invalidate with a reason (reversible). */
    public JsonNode updateMemory(String bankId, String memoryId, UpdateMemoryRequest body) {
        return send("PATCH", bankId, "/memories/" + enc(memoryId), body, FAST_CALL);
    }

    /** {@code GET /memories/{id}/history}: how an observation changed as new facts arrived. */
    public List<ObservationChange> observationHistory(String bankId, String memoryId) {
        JsonNode node = send("GET", bankId, "/memories/" + enc(memoryId) + "/history", null, FAST_CALL);
        return convertList(node, new TypeReference<>() { });
    }

    /** {@code POST /consolidate}: re-derive observations now, optionally for specific tag scopes. */
    public String consolidate(String bankId, List<List<String>> scopes) {
        Map<String, Object> body = scopes == null ? Map.of() : Map.of("observation_scopes", scopes);
        return text(send("POST", bankId, "/consolidate", body, FAST_CALL), "operation_id");
    }

    public void deleteAllMemories(String bankId) {
        send("DELETE", bankId, "/memories", null, SLOW_CALL);
    }

    // ---------------------------------------------------------------- mental models

    public Optional<MentalModel> getMentalModel(String bankId, String modelId) {
        try {
            return Optional.of(convert(send("GET", bankId, "/mental-models/" + enc(modelId) + "?detail=content", null, FAST_CALL),
                    new TypeReference<>() { }));
        } catch (HindsightException e) {
            if (e.notFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void createMentalModel(String bankId, Map<String, Object> body) {
        send("POST", bankId, "/mental-models", body, FAST_CALL);
    }

    public void updateMentalModel(String bankId, String modelId, Map<String, Object> body) {
        send("PATCH", bankId, "/mental-models/" + enc(modelId), body, FAST_CALL);
    }

    /** Asynchronous: returns the operation id of the refresh job. */
    public String refreshMentalModel(String bankId, String modelId) {
        return text(send("POST", bankId, "/mental-models/" + enc(modelId) + "/refresh", Map.of(), FAST_CALL), "operation_id");
    }

    // ---------------------------------------------------------------- knowledge base

    public List<KnowledgeNode> knowledgeTree(String bankId) {
        return convertList(send("GET", bankId, "/knowledge-base/tree", null, FAST_CALL).path("roots"), new TypeReference<>() { });
    }

    public KnowledgeNode createFolder(String bankId, String name, String parentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (parentId != null) {
            body.put("parent_id", parentId);
        }
        return convert(send("POST", bankId, "/knowledge-base/folders", body, FAST_CALL), new TypeReference<>() { });
    }

    /** Creates a page (a mental model + tree node); its content is generated asynchronously. */
    public JsonNode createPage(String bankId, String name, String sourceQuery, String parentId, List<String> tags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("source_query", sourceQuery);
        if (parentId != null) {
            body.put("parent_id", parentId);
        }
        body.put("tags", tags);
        body.put("max_tokens", 2048);
        return send("POST", bankId, "/knowledge-base/pages", body, FAST_CALL);
    }

    public KnowledgePage getPage(String bankId, String pageId) {
        return convert(send("GET", bankId, "/knowledge-base/pages/" + enc(pageId), null, FAST_CALL), new TypeReference<>() { });
    }

    // ---------------------------------------------------------------- operations + webhooks

    public List<Operation> listOperations(String bankId, int limit) {
        return convertList(send("GET", bankId, "/operations?limit=" + limit, null, FAST_CALL).path("operations"),
                new TypeReference<>() { });
    }

    public Operation getOperation(String bankId, String operationId) {
        return convert(send("GET", bankId, "/operations/" + enc(operationId), null, FAST_CALL), new TypeReference<>() { });
    }

    public List<Map<String, Object>> listWebhooks(String bankId) {
        JsonNode node = send("GET", bankId, "/webhooks", null, FAST_CALL);
        JsonNode items = node.isArray() ? node : node.path("items").isArray() ? node.path("items") : node.path("webhooks");
        return convertList(items, new TypeReference<>() { });
    }

    public void registerWebhook(String bankId, String url, String secret, List<String> eventTypes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        if (secret != null && !secret.isBlank()) {
            body.put("secret", secret);
        }
        body.put("event_types", eventTypes);
        body.put("enabled", true);
        send("POST", bankId, "/webhooks", body, FAST_CALL);
    }

    // ---------------------------------------------------------------- plumbing

    private JsonNode send(String method, String bankId, String path, Object body, Duration timeout) {
        HttpRequest.Builder req = request(bankId, path, timeout);
        try {
            if (body != null) {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            } else {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            }
        } catch (IOException e) {
            throw new HindsightException("Could not serialise request: " + e.getMessage(), 0, e);
        }
        return execute(method, path, req.build());
    }

    private HttpRequest.Builder request(String bankId, String path, Duration timeout) {
        if (!configured()) {
            throw new HindsightException("HINDSIGHT_API_KEY is not set", 0, null);
        }
        URI uri = URI.create(cfg.baseUrl() + "/v1/default/banks/" + enc(bankId) + path);
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Accept", "application/json");
    }

    private JsonNode execute(String method, String path, HttpRequest request) {
        long started = System.nanoTime();
        try {
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("hindsight {} {} -> {} in {} ms", method, path, res.statusCode(), (System.nanoTime() - started) / 1_000_000);
            if (res.statusCode() >= 300) {
                throw new HindsightException("Hindsight " + method + " " + stripQuery(path) + " -> HTTP " + res.statusCode()
                        + ": " + abbreviate(res.body()), res.statusCode(), null);
            }
            return res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
        } catch (IOException e) {
            throw new HindsightException("Hindsight unreachable (" + method + " " + stripQuery(path) + "): " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HindsightException("Interrupted calling Hindsight", 0, e);
        }
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String filename,
                                  String contentType, byte[] bytes) throws IOException {
        StringBuilder head = new StringBuilder("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append('"');
        if (filename != null) {
            head.append("; filename=\"").append(filename.replace("\"", "")).append('"');
        }
        head.append("\r\nContent-Type: ").append(contentType).append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private <T> T convert(JsonNode node, TypeReference<T> type) {
        return json.convertValue(node, type);
    }

    /** Missing or null arrays become empty lists, so callers never null-check. */
    private <T> List<T> convertList(JsonNode node, TypeReference<List<T>> type) {
        return node == null || !node.isArray() ? List.of() : json.convertValue(node, type);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
