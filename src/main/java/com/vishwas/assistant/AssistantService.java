package com.vishwas.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.llm.GroqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The natural-language assistant. It answers ONLY from tool calls: structured queries over the reconciliation
 * database and Hindsight recall. Every question is stateless (no earlier conversation is sent), the model must
 * call a tool before answering, and the answer lists every record it used. When the model will not call tools,
 * or Groq is unavailable, a deterministic router picks the tools and the answer is composed from their results.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final int MAX_TURNS = 5;

    public record ToolTrace(String tool, Map<String, Object> args, int records, String error, long ms) {
    }

    public record Answer(String question, String answer, List<AssistantTools.Ref> records, List<ToolTrace> toolCalls,
                         String mode, long ms) {
    }

    static final String SYSTEM = """
            You are the assistant inside Vishwas, a GST reconciliation workspace for an Indian finance team. You know \
            NOTHING except what the tools return in this conversation, and you have no memory of earlier questions. \
            Always call one or more tools before answering. Then answer concisely in markdown, citing invoice numbers, \
            months and rupee amounts exactly as the tools returned them. Keep potential exposure, confirmed loss and \
            recovered amounts separate and never call an open discrepancy a loss. Never give tax advice and never \
            suggest a payment action. If the tools return nothing relevant, say so plainly.
            Choosing tools: for "what happened" or the status of a vendor's case, call case_history for that vendor (its \
            outcomes, months late, promises and messages) AND recall_memory with the date window; for lists and amounts \
            call search_mismatches; for "repeated" or "recurring" call repeated_patterns; to draft e-mails call \
            draft_followup_emails. Periods in tools are yyyy-mm; dates in recall_memory are yyyy-mm-dd.""";

    private final GroqClient groq;
    private final AssistantTools tools;
    private final VendorRepository vendors;
    private final ObjectMapper json;
    private final Clock clock;

    public AssistantService(GroqClient groq, AssistantTools tools, VendorRepository vendors,
                            @Qualifier("apiJson") ObjectMapper json, Clock clock) {
        this.groq = groq;
        this.tools = tools;
        this.vendors = vendors;
        this.json = json;
        this.clock = clock;
    }

    public Answer ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Ask a question.");
        }
        long started = System.currentTimeMillis();
        String q = question.trim();
        if (groq.configured()) {
            Optional<Answer> viaModel = withModel(q, started);
            if (viaModel.isPresent()) {
                return viaModel.get();
            }
        }
        return withRouter(q, started);
    }

    /** The model plans the tool calls; answers without any tool call are refused. */
    Optional<Answer> withModel(String question, long started) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(GroqClient.msg("system", SYSTEM + "\n\n" + context()));
        messages.add(GroqClient.msg("user", question));
        List<ToolTrace> trace = new ArrayList<>();
        LinkedHashSet<AssistantTools.Ref> refs = new LinkedHashSet<>();
        boolean nudged = false;
        for (int turn = 0; turn < MAX_TURNS; turn++) {
            Optional<GroqClient.ChatTurn> reply = groq.chat(messages, AssistantTools.definitions());
            if (reply.isEmpty()) {
                return Optional.empty();
            }
            GroqClient.ChatTurn t = reply.get();
            if (t.wantsTools()) {
                messages.add(assistantMessage(t));
                for (GroqClient.ToolCall call : t.toolCalls()) {
                    long s = System.currentTimeMillis();
                    AssistantTools.Result r = AssistantTools.NAMES.contains(call.name()) ? tools.run(call.name(), call.arguments())
                            : AssistantTools.Result.error(call.name(), Map.of(), "Unknown tool. Use one of " + AssistantTools.NAMES);
                    trace.add(new ToolTrace(call.name(), r.args(), r.records().size(), r.error(), System.currentTimeMillis() - s));
                    refs.addAll(r.records());
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", call.id());
                    toolMsg.put("content", clip(write(r.data()), 7000));
                    messages.add(toolMsg);
                }
                continue;
            }
            if (trace.isEmpty()) {
                if (nudged) {
                    log.info("Assistant model answered without tools twice; using the router");
                    return Optional.empty();
                }
                nudged = true;
                messages.add(GroqClient.msg("assistant", t.content()));
                messages.add(GroqClient.msg("user", "Do not answer from general knowledge. Call the tools first and answer only from their results."));
                continue;
            }
            if (t.content() == null || t.content().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Answer(question, t.content().trim(), List.copyOf(refs), trace, "MODEL_WITH_TOOLS",
                    System.currentTimeMillis() - started));
        }
        return Optional.empty();
    }

    /** Deterministic fallback: route, run the tools, and compose the answer from their data. */
    Answer withRouter(String question, long started) {
        List<String> names = vendors.findAll().stream().map(Vendor::getLegalName).toList();
        List<ToolTrace> trace = new ArrayList<>();
        LinkedHashSet<AssistantTools.Ref> refs = new LinkedHashSet<>();
        StringBuilder answer = new StringBuilder();
        for (IntentRouter.Planned p : IntentRouter.route(question, LocalDate.now(clock), names)) {
            long s = System.currentTimeMillis();
            AssistantTools.Result r = tools.run(p.tool(), json.valueToTree(p.args()));
            trace.add(new ToolTrace(p.tool(), r.args(), r.records().size(), r.error(), System.currentTimeMillis() - s));
            refs.addAll(r.records());
            answer.append(AnswerFormatter.format(r)).append("\n\n");
        }
        return new Answer(question, answer.toString().trim(), List.copyOf(refs), trace, "ROUTER", System.currentTimeMillis() - started);
    }

    String context() {
        LocalDate today = LocalDate.now(clock);
        YearMonth now = YearMonth.from(today);
        YearMonth quarterStart = now.minusMonths((now.getMonthValue() - 1) % 3);
        return "Today is " + Fmt.day(today) + ". Return periods are written yyyy-mm. 'Last month' means " + now.minusMonths(1)
                + ", 'this quarter' means " + quarterStart + " to " + now + ". The company is the recipient; vendors are suppliers.";
    }

    private Map<String, Object> assistantMessage(GroqClient.ChatTurn t) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (JsonNode c : t.rawMessage().path("tool_calls")) {
            calls.add(Map.of("id", c.path("id").asText(), "type", "function", "function", Map.of(
                    "name", c.path("function").path("name").asText(),
                    "arguments", c.path("function").path("arguments").asText("{}"))));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", t.content() == null ? "" : t.content());
        m.put("tool_calls", calls);
        return m;
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…(truncated)";
    }
}
