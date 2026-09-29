package com.vishwas.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.vishwas.ingest.Fmt;
import com.vishwas.llm.GroqClient;
import com.vishwas.matching.Mismatch;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The contrast: the same mismatches sent to a strong model (Groq, openai/gpt-oss-120b) with NO history at all.
 * One call for the whole period; the JSON is validated (every case present, valid categories) with one retry,
 * and falls back to the textbook rules when Groq is unavailable.
 */
@Component
public class BaselineAdvisor {

    public record BaselineAdvice(Category category, String action, String source) {
    }

    static final String SYSTEM = """
            You are a GST reconciliation assistant for an Indian company. You have NO history about any vendor: \
            you only see this month's mismatches between the purchase register and GSTR-2B. For each case choose a \
            category (AUTO_RESOLVE, RECOMMEND, REQUIRE_REVIEW or ESCALATE) and give a one-sentence action. \
            Reply with JSON only: {"cases":[{"case_id":123,"category":"REQUIRE_REVIEW","action":"..."}]}""";

    private final GroqClient groq;

    public BaselineAdvisor(GroqClient groq) {
        this.groq = groq;
    }

    public boolean available() {
        return groq.configured();
    }

    public Map<Long, BaselineAdvice> advise(String period, List<Mismatch> cases) {
        Map<Long, BaselineAdvice> out = new LinkedHashMap<>();
        if (!cases.isEmpty() && groq.configured()) {
            StringBuilder user = new StringBuilder("Mismatches found while reconciling the " + Fmt.month(period) + " GSTR-2B:\n");
            for (Mismatch m : cases) {
                user.append("- case_id ").append(m.getId()).append(": ").append(m.getVendorName()).append(" (GSTIN ")
                        .append(m.getVendorGstin()).append("), ").append(MemoryAdvisor.describe(m)).append('\n');
            }
            Set<Long> expected = new HashSet<>();
            cases.forEach(m -> expected.add(m.getId()));
            Optional<JsonNode> reply = groq.completeJson(SYSTEM, user.toString(), j -> validate(j, expected));
            reply.ifPresent(j -> j.path("cases").forEach(c -> out.put(c.path("case_id").asLong(), new BaselineAdvice(
                    Category.valueOf(c.path("category").asText().trim().toUpperCase()), c.path("action").asText(), "GROQ"))));
        }
        for (Mismatch m : cases) {
            out.putIfAbsent(m.getId(), new BaselineAdvice(Category.REQUIRE_REVIEW, TextbookRules.action(m.getType()), "TEXTBOOK"));
        }
        return out;
    }

    static Optional<String> validate(JsonNode j, Set<Long> expected) {
        if (!j.path("cases").isArray()) {
            return Optional.of("\"cases\" must be an array");
        }
        Set<Long> seen = new HashSet<>();
        for (JsonNode c : j.path("cases")) {
            try {
                Category.valueOf(c.path("category").asText().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Optional.of("category must be one of AUTO_RESOLVE, RECOMMEND, REQUIRE_REVIEW, ESCALATE");
            }
            if (c.path("action").asText("").isBlank()) {
                return Optional.of("every case needs an action");
            }
            seen.add(c.path("case_id").asLong());
        }
        if (!seen.containsAll(expected)) {
            return Optional.of("include every case_id: " + expected);
        }
        return Optional.empty();
    }
}
