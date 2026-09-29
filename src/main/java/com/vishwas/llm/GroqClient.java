package com.vishwas.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.config.VishwasProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Groq's OpenAI-compatible chat endpoint (model openai/gpt-oss-120b). Used for the no-memory baseline,
 * vendor e-mail drafts and the assistant's tool routing. Every entry point validates what comes back,
 * retries once with the validation error fed back to the model, then returns empty so the caller can
 * fall back to deterministic behaviour. Nothing here ever throws into a request.
 */
@Component
public class GroqClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    /** A tool call the model asked for; {@code arguments} is already parsed and is a JSON object. */
    public record ToolCall(String id, String name, JsonNode arguments) {
    }

    /** One assistant turn: either text content or tool calls. */
    public record ChatTurn(String content, List<ToolCall> toolCalls, JsonNode rawMessage) {
        public boolean wantsTools() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    private final HttpClient http;
    private final ObjectMapper json;
    private final VishwasProperties.Groq cfg;

    public GroqClient(HttpClient http, @Qualifier("apiJson") ObjectMapper json, VishwasProperties props) {
        this.http = http;
        this.json = json;
        this.cfg = props.groq();
    }

    public boolean configured() {
        return cfg.configured();
    }

    /** Cheap reachability probe for /health: lists models. */
    public boolean healthy() {
        if (!configured()) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/models"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + cfg.apiKey()).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Free-text completion: one retry, then empty. */
    public Optional<String> complete(String system, String user) {
        if (!configured()) {
            return Optional.empty();
        }
        List<Map<String, Object>> messages = List.of(msg("system", system), msg("user", user));
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String text = call(body(messages, false, null)).path("content").asText("").trim();
                if (!text.isBlank()) {
                    return Optional.of(text);
                }
                log.warn("Groq attempt {} returned an empty answer", attempt);
            } catch (GroqCallException e) {
                log.warn("Groq attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * JSON-mode completion. {@code validator} returns an error message (or empty when the JSON is acceptable).
     * Invalid JSON or a validation error triggers exactly one retry with the error shown to the model.
     */
    public Optional<JsonNode> completeJson(String system, String user, Function<JsonNode, Optional<String>> validator) {
        if (!configured()) {
            return Optional.empty();
        }
        List<Map<String, Object>> messages = new ArrayList<>(List.of(msg("system", system), msg("user", user)));
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = null;
            try {
                raw = call(body(messages, true, null)).path("content").asText("");
                JsonNode parsed = json.readTree(stripFences(raw));
                Optional<String> problem = validator.apply(parsed);
                if (problem.isEmpty()) {
                    return Optional.of(parsed);
                }
                log.warn("Groq JSON attempt {} failed validation: {}", attempt, problem.get());
                messages.add(msg("assistant", raw));
                messages.add(msg("user", "That JSON was not valid for the task: " + problem.get()
                        + ". Reply again with corrected JSON only."));
            } catch (IOException e) {
                log.warn("Groq JSON attempt {} was not parseable: {}", attempt, e.getMessage());
                if (raw != null) {
                    messages.add(msg("assistant", raw));
                }
                messages.add(msg("user", "That was not valid JSON. Reply with one JSON object only."));
            } catch (GroqCallException e) {
                log.warn("Groq JSON attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * One chat turn with function calling. Tool-call arguments are parsed and must be JSON objects; Groq's
     * {@code tool_use_failed} errors and malformed arguments are retried once, then empty.
     *
     * @param messages full conversation so far (OpenAI message format)
     * @param tools    OpenAI tool definitions
     */
    public Optional<ChatTurn> chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (!configured()) {
            return Optional.empty();
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                JsonNode message = call(body(messages, false, tools));
                List<ToolCall> calls = new ArrayList<>();
                for (JsonNode tc : message.path("tool_calls")) {
                    String rawArgs = tc.path("function").path("arguments").asText("{}");
                    JsonNode args = json.readTree(rawArgs.isBlank() ? "{}" : rawArgs);
                    if (!args.isObject()) {
                        throw new IOException("tool arguments are not a JSON object");
                    }
                    calls.add(new ToolCall(tc.path("id").asText(), tc.path("function").path("name").asText(), args));
                }
                return Optional.of(new ChatTurn(message.path("content").asText(""), calls, message));
            } catch (IOException e) {
                log.warn("Groq tool call attempt {} had malformed arguments: {}", attempt, e.getMessage());
            } catch (GroqCallException e) {
                log.warn("Groq tool call attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- plumbing

    private Map<String, Object> body(List<Map<String, Object>> messages, boolean jsonMode, List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", 0.2);
        body.put("max_completion_tokens", 4096);
        body.put("messages", messages);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /** Returns {@code choices[0].message}. */
    private JsonNode call(Map<String, Object> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/chat/completions"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                String b = res.body() == null ? "" : res.body();
                throw new GroqCallException("HTTP " + res.statusCode() + ": " + b.substring(0, Math.min(300, b.length())));
            }
            JsonNode message = json.readTree(res.body()).path("choices").path(0).path("message");
            if (message.isMissingNode()) {
                throw new GroqCallException("response had no message");
            }
            return message;
        } catch (IOException e) {
            throw new GroqCallException(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GroqCallException("interrupted");
        }
    }

    public static Map<String, Object> msg(String role, String content) {
        return Map.of("role", role, "content", content == null ? "" : content);
    }

    /** Models sometimes wrap JSON in markdown fences despite JSON mode. */
    static String stripFences(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                return s.substring(firstNl + 1, lastFence).trim();
            }
        }
        return s;
    }

    private static final class GroqCallException extends RuntimeException {
        GroqCallException(String message) {
            super(message);
        }
    }
}
