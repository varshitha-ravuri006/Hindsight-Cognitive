package com.vishwas.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * An in-process fake of Hindsight Cloud and Groq. Records every request so tests can assert on exactly what
 * Vishwas sent (tags, scopes, entities, timestamps, directives...). Tests override any route with
 * {@link #on(String, String, Function)}; sensible defaults cover the rest.
 */
public class StubApis implements AutoCloseable {

    public record Call(String method, String path, String query, String contentType, byte[] raw, JsonNode body) {
        public String rawText() {
            return new String(raw, StandardCharsets.UTF_8);
        }
    }

    public record Reply(int status, String body) {
        public static Reply ok(String body) {
            return new Reply(200, body);
        }
    }

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Call, Reply>> routes = new ConcurrentHashMap<>();

    public StubApis() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Route by method and path suffix (e.g. "POST", "/reflect"). Later registrations win. */
    public StubApis on(String method, String pathSuffix, Function<Call, Reply> reply) {
        routes.put(method + " " + pathSuffix, reply);
        return this;
    }

    public List<Call> calls(String method, String pathSuffix) {
        return calls.stream().filter(c -> c.method.equals(method) && c.path.endsWith(pathSuffix)).toList();
    }

    public void reset() {
        calls.clear();
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        byte[] raw = ex.getRequestBody().readAllBytes();
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        JsonNode body = null;
        if (raw.length > 0 && ct != null && ct.startsWith("application/json")) {
            body = json.readTree(raw);
        }
        Call call = new Call(method, path, query, ct, raw, body);
        calls.add(call);

        Reply reply = routes.entrySet().stream()
                .filter(e -> e.getKey().startsWith(method + " ") && path.endsWith(e.getKey().substring(method.length() + 1)))
                .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                .map(e -> e.getValue().apply(call))
                .orElseGet(() -> defaults(call));
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private Reply defaults(Call c) {
        String p = c.path;
        String m = c.method;
        if (p.endsWith("/chat/completions")) {
            return Reply.ok(groqText("ok"));
        }
        if (p.endsWith("/models") || p.endsWith("/health")) {
            return Reply.ok("{\"status\":\"healthy\"}");
        }
        if (p.endsWith("/directives") && m.equals("GET")) {
            return Reply.ok("{\"items\":[],\"total\":0,\"limit\":100,\"offset\":0}");
        }
        if (p.contains("/mental-models/") && m.equals("GET")) {
            return new Reply(404, "{\"detail\":\"not found\"}");
        }
        if (p.endsWith("/memories") && m.equals("POST")) {
            boolean async = c.body != null && c.body.path("async").asBoolean(false);
            int n = c.body == null ? 0 : c.body.path("items").size();
            return Reply.ok("{\"success\":true,\"bank_id\":\"b\",\"items_count\":" + n + ",\"async\":" + async
                    + (async ? ",\"operation_id\":\"op-" + calls.size() + "\"" : "") + "}");
        }
        if (p.endsWith("/files/retain")) {
            return Reply.ok("{\"operation_ids\":[\"op-file-1\"]}");
        }
        if (p.endsWith("/memories/recall")) {
            return Reply.ok("{\"results\":[]}");
        }
        if (p.endsWith("/reflect")) {
            return Reply.ok("{\"text\":\"No relevant memory.\",\"based_on\":{\"memories\":[],\"directives\":[],\"mental_models\":[]}}");
        }
        if (p.endsWith("/memories/list")) {
            return Reply.ok("{\"items\":[],\"total\":0,\"limit\":1,\"offset\":0}");
        }
        if (p.contains("/operations/")) {
            return Reply.ok("{\"operation_id\":\"op\",\"status\":\"completed\"}");
        }
        if (p.endsWith("/operations")) {
            return Reply.ok("{\"bank_id\":\"b\",\"total\":0,\"limit\":50,\"offset\":0,\"operations\":[]}");
        }
        if (p.endsWith("/knowledge-base/tree")) {
            return Reply.ok("{\"roots\":[]}");
        }
        if (p.endsWith("/knowledge-base/folders")) {
            return new Reply(201, "{\"id\":\"folder-1\",\"kind\":\"folder\",\"name\":\"Vendors\"}");
        }
        if (p.endsWith("/knowledge-base/pages") && m.equals("POST")) {
            return new Reply(201, "{\"page_id\":\"page-" + calls.size() + "\",\"mental_model_id\":\"mm\",\"operation_id\":\"op\"}");
        }
        if (p.endsWith("/stats")) {
            return Reply.ok("{\"bank_id\":\"b\",\"total_nodes\":0,\"total_observations\":0,\"pending_operations\":0}");
        }
        if (p.endsWith("/webhooks") && m.equals("GET")) {
            return Reply.ok("{\"items\":[]}");
        }
        if (p.endsWith("/consolidate")) {
            return Reply.ok("{\"operation_id\":\"op-consolidate\"}");
        }
        return Reply.ok("{}");
    }

    /** A Groq chat completion whose assistant message is {@code content}. */
    public static String groqText(String content) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of("choices",
                    List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Groq chat completion that asks for one tool call. */
    public static String groqToolCall(String name, String argumentsJson) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of(
                    "role", "assistant", "content", "",
                    "tool_calls", List.of(Map.of("id", "call_1", "type", "function",
                            "function", Map.of("name", name, "arguments", argumentsJson))))))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
