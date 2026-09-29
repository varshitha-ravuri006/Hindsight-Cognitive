package com.vishwas.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Fmt;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.memory.MemoryWriter;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.RecallHit;
import com.vishwas.memory.hindsight.ReflectAnswer;
import com.vishwas.memory.hindsight.ReflectQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Asks Hindsight to reflect on ONE vendor's memory (tags scoped to that vendor's GSTIN) about that vendor's
 * open cases, and returns a structured proposal per case plus the facts the answer relied on. Deterministic
 * guardrails ({@link CategoryPolicy}) and database confidence are applied afterwards by {@link AdviceService}.
 */
@Component
public class MemoryAdvisor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemoryAdvisor.class);

    /** One ranked cause hypothesis with its evidence as the model stated it. */
    public record Hypothesis(Cause cause, String likelihood, String evidence) {
    }

    public record CaseProposal(long caseId, String invoice, Category category, String headline, List<Hypothesis> hypotheses,
                               String nextStep, String confidence, List<String> evidenceRefs) {
        public Cause topCause() {
            return hypotheses.isEmpty() ? Cause.UNKNOWN : hypotheses.get(0).cause();
        }
    }

    public record VendorAdvice(String gstin, Map<Long, CaseProposal> proposals, String vendorSummary, Double exposureInr,
                               List<RecallHit> facts, String error) {
        public static VendorAdvice failed(String gstin, String error) {
            return new VendorAdvice(gstin, Map.of(), null, null, List.of(), error);
        }
    }

    static final Map<String, Object> RESPONSE_SCHEMA = responseSchema();

    private final HindsightClient hindsight;
    private final ObjectMapper json;
    private final VishwasProperties props;

    public MemoryAdvisor(HindsightClient hindsight, @Qualifier("apiJson") ObjectMapper json, VishwasProperties props) {
        this.hindsight = hindsight;
        this.json = json;
        this.props = props;
    }

    /** One reflect, validated; missing or incomplete structured output is retried once, then the caller falls back. */
    public VendorAdvice advise(String gstin, String vendorName, String period, List<Mismatch> cases, Map<Long, String> ledger) {
        VendorAdvice first = adviseOnce(gstin, vendorName, period, cases, ledger);
        if (first.error() == null) {
            return first;
        }
        log.warn("Reflect for {} was incomplete ({}); retrying once", vendorName, first.error());
        VendorAdvice second = adviseOnce(gstin, vendorName, period, cases, ledger);
        if (second.error() != null) {
            log.warn("Reflect for {} still incomplete ({})", vendorName, second.error());
        }
        return second.proposals().size() >= first.proposals().size() ? second : first;
    }

    VendorAdvice adviseOnce(String gstin, String vendorName, String period, List<Mismatch> cases, Map<Long, String> ledger) {
        String query = query(gstin, vendorName, period, cases, ledger);
        ReflectAnswer answer = hindsight.reflect(props.hindsight().bankId(), ReflectQuery.scoped(query,
                List.of(MemoryWriter.vendorTag(gstin)), props.advice().reflectBudget(), RESPONSE_SCHEMA,
                props.advice().reflectMaxTokens()));
        JsonNode structured = answer.structuredOutput();
        if (structured == null || !structured.isObject()) {
            structured = tryParse(answer.text()).orElse(null);
        }
        if (structured == null) {
            return new VendorAdvice(gstin, Map.of(), answer.text(), null, answer.sourceMemories(),
                    "Memory answered without structured output" + (answer.structuredOutputError() == null ? ""
                            : ": " + answer.structuredOutputError()));
        }
        Map<Long, CaseProposal> proposals = parse(structured, cases);
        String error = proposals.size() < cases.size() ? "Memory proposed for " + proposals.size() + " of " + cases.size() + " cases" : null;
        JsonNode exposure = structured.path("exposure_inr");
        return new VendorAdvice(gstin, proposals, text(structured, "vendor_summary"),
                exposure.isNumber() ? exposure.asDouble() : null, answer.sourceMemories(), error);
    }

    /** The question for one vendor: its open cases, described from today's data only (history comes from memory). */
    static String query(String gstin, String vendorName, String period, List<Mismatch> cases, Map<Long, String> ledger) {
        StringBuilder q = new StringBuilder();
        q.append("Vendor: ").append(vendorName).append(" (GSTIN ").append(gstin).append("). We are reconciling the ")
                .append(Fmt.month(period)).append(" GSTR-2B. Using ONLY this vendor's memories (past mismatches and what they ")
                .append("turned out to be, communications and promises, Vishwas's own past recommendations and the accountant's ")
                .append("decisions), advise on each open case below.\n")
                .append("A CASE is one invoice number: count distinct past invoices, never facts about them.\n")
                .append("For each case give:\n")
                .append("- category: AUTO_RESOLVE only for a pure invoice-number format difference; RECOMMEND when this vendor's past ")
                .append("cases on the same dimension show a consistent benign outcome; REQUIRE_REVIEW when ITC may be at risk, history ")
                .append("is thin (fewer than 3 past cases on the dimension) or contradictory, or the vendor broke a promise; ESCALATE ")
                .append("for suspected duplicates, conflicting records or missing evidence.\n")
                .append("- headline: ONE sentence with this vendor's real numbers, in this style: \"Possible timing difference: this ")
                .append("vendor's missing invoices appeared in the next GSTR-2B in 4 of 4 past cases.\" or \"Potential payment risk: ")
                .append("this vendor has 3 past unresolved cases (Rs 42,000 exposure) and broke a written promise to file by 20 Jul 2026.\" ")
                .append("When money is at risk, always state the rupee total of the vendor's past unresolved cases.\n")
                .append("- cause_hypotheses ranked most likely first, each with evidence citing month, invoice, amount and outcome of PAST cases.\n")
                .append("- next_step, never a payment action. For a likely timing difference: \"Check the next data refresh before sending ")
                .append("a reminder; review if still unmatched.\" When money may be at risk: \"Review invoice, contract and payment status ")
                .append("before deciding.\"\n")
                .append("- confidence, and evidence_refs: short strings naming PAST cases and events (month, invoice, amount, outcome, and ")
                .append("kept or broken promises with dates), never the case being judged, e.g. \"Jun 2026 SBT/2026/0079 Rs 12,402 ")
                .append("appeared 1 month late\" or \"6 Jul 2026 letter: promised to file by 20 Jul 2026, broken\".\n")
                .append("Never use another vendor's history, even one with a similar name.\n\nOpen cases:\n");
        for (Mismatch m : cases) {
            q.append("- case_id ").append(m.getId()).append(": ").append(describe(m)).append('\n');
        }
        return q.toString();
    }

    /**
     * The verified ledger for one case: this vendor's past cases on the same dimension with month, invoice,
     * amount and outcome, and what is still open. Exact numbers from the database, so memory never miscounts.
     */
    static String ledger(HistoryStats stats, com.vishwas.matching.Dimension dim) {
        List<HistoryStats.PastCase> past = stats.chronological();
        if (past.isEmpty()) {
            return dim + ": no past cases for this vendor (history is empty).";
        }
        StringBuilder b = new StringBuilder(dim.name()).append(": ").append(past.size()).append(" past case")
                .append(past.size() == 1 ? "" : "s").append(" - ");
        List<String> items = new java.util.ArrayList<>();
        for (HistoryStats.PastCase p : past.subList(Math.max(0, past.size() - 8), past.size())) {
            String outcome = p.status() == com.vishwas.matching.MismatchStatus.WRITTEN_OFF
                    ? "ITC reversed, confirmed loss " + Fmt.inr(p.confirmedLoss())
                    : p.outcome() == null ? "still open"
                    : p.outcome() == com.vishwas.matching.Outcome.RESOLVED_LATE ? "appeared " + p.monthsLate() + " month"
                            + (p.monthsLate() != null && p.monthsLate() == 1 ? "" : "s") + " late"
                    : p.outcome() == com.vishwas.matching.Outcome.UNRESOLVED_AT_RISK ? "unresolved, at risk"
                    : p.outcome().label().toLowerCase();
            items.add(Fmt.month(p.period()) + " " + p.invoiceNo() + " " + Fmt.inr(p.exposure()) + " " + outcome);
        }
        b.append(String.join("; ", items));
        long open = past.stream().filter(p -> p.status().open()).count();
        b.append(". Still unresolved: ").append(open).append(open == 1 ? " case" : " cases");
        if (open > 0) {
            b.append(" (").append(Fmt.inr(stats.openExposure())).append(" potential exposure)");
        }
        return b.append('.').toString();
    }

    static String describe(Mismatch m) {
        String age = m.getStatus() != MismatchStatus.AT_RISK ? ""
                : " Still unresolved " + m.getMonthsLate() + " months later (UNRESOLVED_AT_RISK).";
        String base = "invoice " + m.invoiceNo() + " (" + Fmt.month(m.getPeriod()) + "), " + m.getType() + ", dimension "
                + m.getDimension() + ", potential ITC exposure " + Fmt.inr(m.getExposure()) + ".";
        String detail = switch (m.getType()) {
            case MISSING_IN_2B -> " Dated " + Fmt.day(m.getInvoiceDateBooks()) + ", ITC " + Fmt.inr(m.getItcBooks())
                    + " booked but not in GSTR-2B." + (m.getCandidatesJson() != null && m.getCandidatesJson().length() > 2
                    ? " GSTR-2B has a possible match with a slightly different number and the same date and amounts (not "
                    + "confirmed), so a data-entry difference in our books is plausible." : "");
            case MISSING_IN_BOOKS -> " In GSTR-2B (ITC " + Fmt.inr(m.getItcGstr2b()) + ") but not booked.";
            case AMOUNT_MISMATCH -> " Books ITC " + Fmt.inr(m.getItcBooks()) + " vs GSTR-2B ITC " + Fmt.inr(m.getItcGstr2b()) + ".";
            case TAX_HEAD_MISMATCH -> " Booked as CGST+SGST vs IGST in GSTR-2B (or the reverse), same total.";
            case GSTIN_MISMATCH -> " Same invoice and amounts reported under a different GSTIN of the supplier.";
            case INVOICE_NO_FORMAT -> " Booked as '" + m.getInvoiceNoBooks() + "', reported as '" + m.getInvoiceNoGstr2b()
                    + "'; GSTIN, date and amounts identical.";
            case DATE_MISMATCH -> " Booked date " + Fmt.day(m.getInvoiceDateBooks()) + " vs GSTR-2B date "
                    + Fmt.day(m.getInvoiceDateGstr2b()) + ".";
            case POSSIBLE_DUPLICATE -> " The same invoice number appears twice in the purchase register.";
        };
        return base + detail + age;
    }

    Map<Long, CaseProposal> parse(JsonNode root, List<Mismatch> cases) {
        Map<Long, CaseProposal> out = new LinkedHashMap<>();
        Map<String, Long> byInvoice = new HashMap<>();
        cases.forEach(m -> byInvoice.put(m.invoiceNo().toUpperCase(), m.getId()));
        for (JsonNode p : root.path("per_invoice")) {
            long given = p.path("case_id").asLong(0);
            if (given == 0 && p.path("case_id").isTextual()) {
                given = parseLong(p.path("case_id").asText().replaceAll("[^0-9]", ""));
            }
            Long id = given > 0 ? Long.valueOf(given) : byInvoice.get(p.path("invoice").asText("").trim().toUpperCase());
            if (id == null && cases.size() == 1 && root.path("per_invoice").size() == 1) {
                id = cases.get(0).getId();
            }
            Long caseId = id;
            if (caseId == null || cases.stream().noneMatch(m -> m.getId().equals(caseId))) {
                continue;
            }
            Category category;
            try {
                category = Category.valueOf(p.path("category").asText("").trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<Hypothesis> hypotheses = new ArrayList<>();
            for (JsonNode h : p.path("cause_hypotheses")) {
                hypotheses.add(new Hypothesis(Cause.parse(h.path("cause").asText()), h.path("likelihood").asText("MEDIUM"),
                        h.path("evidence").asText("")));
            }
            List<String> refs = new ArrayList<>();
            p.path("evidence_refs").forEach(r -> refs.add(r.asText()));
            out.put(id, new CaseProposal(id, p.path("invoice").asText(), category, text(p, "headline"), hypotheses,
                    text(p, "next_step"), p.path("confidence").asText(null), refs));
        }
        return out;
    }

    private static long parseLong(String digits) {
        try {
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Optional<JsonNode> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode n = json.readTree(text.substring(start, end + 1));
            return n.has("per_invoice") ? Optional.of(n) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Map<String, Object> responseSchema() {
        List<String> causes = java.util.Arrays.stream(Cause.values()).map(Enum::name).toList();
        Map<String, Object> hypothesis = Map.of("type", "object",
                "properties", Map.of(
                        "cause", Map.of("type", "string", "enum", causes),
                        "likelihood", Map.of("type", "string", "enum", List.of("HIGH", "MEDIUM", "LOW")),
                        "evidence", Map.of("type", "string", "description", "month, invoice, amount and outcome of past cases")),
                "required", List.of("cause", "likelihood", "evidence"));
        Map<String, Object> perInvoice = Map.of("type", "object",
                "properties", Map.of(
                        "case_id", Map.of("type", "integer"),
                        "invoice", Map.of("type", "string"),
                        "category", Map.of("type", "string", "enum", List.of("AUTO_RESOLVE", "RECOMMEND", "REQUIRE_REVIEW", "ESCALATE")),
                        "headline", Map.of("type", "string", "description", "one line for the accountant"),
                        "cause_hypotheses", Map.of("type", "array", "items", hypothesis),
                        "next_step", Map.of("type", "string"),
                        "confidence", Map.of("type", "string", "enum", List.of("HIGH", "MEDIUM", "LOW", "NONE")),
                        "evidence_refs", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("case_id", "invoice", "category", "cause_hypotheses", "next_step", "confidence", "evidence_refs"));
        return Map.of("type", "object",
                "properties", Map.of(
                        "vendor", Map.of("type", "string"),
                        "per_invoice", Map.of("type", "array", "items", perInvoice),
                        "vendor_summary", Map.of("type", "string"),
                        "exposure_inr", Map.of("type", "number")),
                "required", List.of("vendor", "per_invoice", "vendor_summary", "exposure_inr"));
    }
}
