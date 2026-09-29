package com.vishwas.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Dimension;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.MemoryWriter;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.RecallHit;
import com.vishwas.memory.hindsight.RecallQuery;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import com.vishwas.workflow.EmailDrafter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The only way the assistant can know anything: structured queries over the reconciliation database and
 * Hindsight recall. Every tool returns its data plus the exact records it used, which the UI shows.
 */
@Component
public class AssistantTools {

    /** A record the answer relied on: a case, a vendor, a vendor message, or a memory. */
    public record Ref(String kind, String id, String label) {
    }

    public record Result(String tool, Map<String, Object> args, Object data, List<Ref> records, String error) {
        static Result error(String tool, Map<String, Object> args, String message) {
            return new Result(tool, args, Map.of("error", message), List.of(), message);
        }
    }

    public static final List<String> NAMES = List.of("search_mismatches", "repeated_patterns", "case_history", "recall_memory",
            "draft_followup_emails");

    private final MismatchRepository mismatches;
    private final VendorRepository vendors;
    private final VendorCommunicationRepository communications;
    private final HindsightClient hindsight;
    private final MemoryPublisher publisher;
    private final EmailDrafter drafter;
    private final Clock clock;
    private final String bankId;

    public AssistantTools(MismatchRepository mismatches, VendorRepository vendors, VendorCommunicationRepository communications,
                          HindsightClient hindsight, MemoryPublisher publisher, EmailDrafter drafter, Clock clock,
                          VishwasProperties props) {
        this.mismatches = mismatches;
        this.vendors = vendors;
        this.communications = communications;
        this.hindsight = hindsight;
        this.publisher = publisher;
        this.drafter = drafter;
        this.clock = clock;
        this.bankId = props.hindsight().bankId();
    }

    /** OpenAI-style tool definitions sent to Groq. */
    public static List<Map<String, Object>> definitions() {
        Map<String, Object> period = Map.of("type", "string", "description", "return period yyyy-mm");
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("search_mismatches", "Find reconciliation cases (mismatches) in the database by vendor, type, "
                + "dimension, status, minimum ITC exposure and return period range.", props(
                "vendor", Map.of("type", "string", "description", "vendor name or GSTIN, partial allowed"),
                "type", Map.of("type", "string", "enum", enumNames(MismatchType.values())),
                "dimension", Map.of("type", "string", "enum", enumNames(Dimension.values())),
                "status", Map.of("type", "string", "enum", List.of("UNRESOLVED", "OPEN", "AT_RISK", "RESOLVED", "WRITTEN_OFF", "ANY")),
                "min_exposure_inr", Map.of("type", "number"),
                "period_from", period, "period_to", period)));
        tools.add(tool("repeated_patterns", "Vendors with repeated cases of the same kind in a period range (for example "
                + "repeated invoice-number mismatches this quarter).", props(
                "type", Map.of("type", "string", "enum", enumNames(MismatchType.values())),
                "dimension", Map.of("type", "string", "enum", enumNames(Dimension.values())),
                "period_from", period, "period_to", period,
                "min_cases", Map.of("type", "integer", "description", "default 2"))));
        tools.add(tool("case_history", "Everything the database knows about one vendor's cases: detection, outcome, months "
                + "late, amounts, communications and promises. Optionally narrowed to an invoice or a period.", props(
                "vendor", Map.of("type", "string"), "invoice", Map.of("type", "string"), "period", period), List.of("vendor")));
        tools.add(tool("recall_memory", "Ask Hindsight's memory (facts and observations), optionally for one vendor and a date "
                + "window, e.g. what happened last month.", props(
                "question", Map.of("type", "string"), "vendor", Map.of("type", "string"),
                "from", Map.of("type", "string", "description", "yyyy-mm-dd"), "to", Map.of("type", "string", "description", "yyyy-mm-dd")),
                List.of("question")));
        tools.add(tool("draft_followup_emails", "Draft follow-up e-mails, one per vendor with unresolved cases; optionally only "
                + "vendors whose documents are missing (invoices missing from GSTR-2B or from the books).", props(
                "vendor", Map.of("type", "string"), "missing_documents_only", Map.of("type", "boolean"))));
        return tools;
    }

    public Result run(String name, JsonNode args) {
        Map<String, Object> a = new LinkedHashMap<>();
        args.fields().forEachRemaining(e -> a.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue()
                : e.getValue().isBoolean() ? e.getValue().booleanValue() : e.getValue().asText()));
        try {
            return switch (name) {
                case "search_mismatches" -> search(a);
                case "repeated_patterns" -> patterns(a);
                case "case_history" -> history(a);
                case "recall_memory" -> recall(a);
                case "draft_followup_emails" -> drafts(a);
                default -> Result.error(name, a, "Unknown tool " + name + ". Use one of " + NAMES);
            };
        } catch (IllegalArgumentException e) {
            return Result.error(name, a, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- tools

    @Transactional(readOnly = true)
    Result search(Map<String, Object> a) {
        List<Mismatch> found = filter(a).stream()
                .sorted(Comparator.comparing(Mismatch::getExposure, Comparator.reverseOrder())).limit(50).toList();
        BigDecimal total = found.stream().map(Mismatch::getExposure).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", found.size());
        data.put("total_exposure_inr", total);
        data.put("cases", found.stream().map(AssistantTools::caseLine).toList());
        return new Result("search_mismatches", a, data, found.stream().map(AssistantTools::ref).toList(), null);
    }

    @Transactional(readOnly = true)
    Result patterns(Map<String, Object> a) {
        int min = a.get("min_cases") instanceof Number n ? Math.max(1, n.intValue()) : 2;
        Map<String, Object> scoped = new LinkedHashMap<>(a);
        scoped.put("status", "ANY");
        Map<String, List<Mismatch>> byVendor = filter(scoped).stream()
                .collect(Collectors.groupingBy(Mismatch::getVendorGstin, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> vendorsOut = new ArrayList<>();
        List<Ref> refs = new ArrayList<>();
        byVendor.forEach((gstin, list) -> {
            if (list.size() >= min) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("vendor", list.get(0).getVendorName());
                v.put("gstin", gstin);
                v.put("cases", list.size());
                v.put("details", list.stream().map(AssistantTools::caseLine).toList());
                vendorsOut.add(v);
                refs.add(new Ref("vendor", gstin, list.get(0).getVendorName()));
                list.forEach(m -> refs.add(ref(m)));
            }
        });
        vendorsOut.sort(Comparator.comparing(v -> -((Integer) v.get("cases"))));
        return new Result("repeated_patterns", a, Map.of("min_cases", min, "vendors", vendorsOut), refs, null);
    }

    @Transactional(readOnly = true)
    Result history(Map<String, Object> a) {
        Vendor v = vendor(str(a, "vendor")).orElseThrow(() -> new IllegalArgumentException("No vendor matches '" + str(a, "vendor") + "'"));
        String invoice = str(a, "invoice");
        String period = str(a, "period");
        List<Mismatch> cases = mismatches.findByVendorGstinOrderByDetectedAtAscIdAsc(v.getGstin()).stream()
                .filter(m -> invoice == null || m.invoiceNo().equalsIgnoreCase(invoice))
                .filter(m -> period == null || period.equals(m.getPeriod()) || period.equals(m.getVerdictPeriod()))
                .toList();
        List<VendorCommunication> thread = communications.findByVendorGstinOrderByOccurredAtAsc(v.getGstin());
        List<Ref> refs = new ArrayList<>(cases.stream().map(AssistantTools::ref).toList());
        refs.add(new Ref("vendor", v.getGstin(), v.getLegalName()));
        List<Map<String, Object>> msgs = thread.stream().map(c -> {
            refs.add(new Ref("communication", String.valueOf(c.getId()), Fmt.day(c.getOccurredAt()) + " " + c.getChannel().name().toLowerCase()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", Fmt.day(c.getOccurredAt()));
            m.put("direction", c.getDirection().name());
            m.put("summary", c.getSummary());
            if (c.getPromiseBy() != null) {
                m.put("promise_by", Fmt.day(c.getPromiseBy()));
                m.put("promise_status", c.getPromiseStatus() == null ? "PENDING" : c.getPromiseStatus().name());
            }
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vendor", v.getLegalName());
        data.put("gstin", v.getGstin());
        data.put("cases", cases.stream().map(AssistantTools::caseLine).toList());
        data.put("communications", msgs);
        return new Result("case_history", a, data, refs, null);
    }

    Result recall(Map<String, Object> a) {
        String question = str(a, "question");
        if (question == null) {
            throw new IllegalArgumentException("recall_memory needs a question");
        }
        if (!publisher.enabled()) {
            return new Result("recall_memory", a, Map.of("available", false, "note", "Memory is off."), List.of(), null);
        }
        Optional<Vendor> v = str(a, "vendor") == null ? Optional.empty() : vendor(str(a, "vendor"));
        RecallQuery q = RecallQuery.of(question, null, v.map(x -> List.of(MemoryWriter.vendorTag(x.getGstin()))).orElse(null),
                v.isPresent() ? "any_strict" : "any");
        String from = str(a, "from");
        String to = str(a, "to");
        q = q.withTime(OffsetDateTime.now(clock).toString(), from != null && to != null ? new RecallQuery.TemporalWindow(from, to) : null);
        try {
            List<RecallHit> hits = hindsight.recall(bankId, q).hits().stream().limit(12).toList();
            List<Map<String, Object>> facts = hits.stream().map(h -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("text", h.text());
                m.put("type", h.type());
                m.put("when", h.occurredStart() != null ? h.occurredStart() : h.mentionedAt());
                return m;
            }).toList();
            List<Ref> refs = hits.stream().map(h -> new Ref("memory", h.id(), h.text().length() > 90 ? h.text().substring(0, 90) + "…" : h.text())).toList();
            return new Result("recall_memory", a, Map.of("facts", facts), refs, null);
        } catch (HindsightException e) {
            return Result.error("recall_memory", a, "Memory did not answer: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    Result drafts(Map<String, Object> a) {
        boolean missingOnly = Boolean.TRUE.equals(a.get("missing_documents_only"));
        Map<String, Object> scoped = new LinkedHashMap<>();
        scoped.put("status", "UNRESOLVED");
        if (a.get("vendor") != null) {
            scoped.put("vendor", a.get("vendor"));
        }
        Map<String, List<Mismatch>> byVendor = filter(scoped).stream()
                .filter(m -> !missingOnly || m.getType() == MismatchType.MISSING_IN_2B || m.getType() == MismatchType.MISSING_IN_BOOKS)
                .collect(Collectors.groupingBy(Mismatch::getVendorGstin, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        List<Ref> refs = new ArrayList<>();
        byVendor.forEach((gstin, list) -> vendors.findById(gstin).ifPresent(v -> {
            EmailDrafter.Draft d = drafter.template(v, list);
            out.add(Map.of("vendor", v.getLegalName(), "to", String.valueOf(v.getEmail()), "subject", d.subject(), "body", d.body()));
            refs.add(new Ref("vendor", gstin, v.getLegalName()));
            list.forEach(m -> refs.add(ref(m)));
        }));
        return new Result("draft_followup_emails", a, Map.of("drafts", out, "note",
                "Standard drafts; open a case in the Action center for a tailored draft. Vishwas never sends e-mail."), refs, null);
    }

    // ---------------------------------------------------------------- helpers

    List<Mismatch> filter(Map<String, Object> a) {
        String vendorArg = str(a, "vendor");
        Optional<Vendor> v = vendorArg == null ? Optional.empty() : vendor(vendorArg);
        if (vendorArg != null && v.isEmpty()) {
            throw new IllegalArgumentException("No vendor matches '" + vendorArg + "'");
        }
        MismatchType type = parse(MismatchType.class, str(a, "type"));
        Dimension dim = parse(Dimension.class, str(a, "dimension"));
        String status = Optional.ofNullable(str(a, "status")).orElse("ANY").toUpperCase(Locale.ROOT);
        BigDecimal min = a.get("min_exposure_inr") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
        String from = str(a, "period_from");
        String to = str(a, "period_to");
        return mismatches.findAllByOrderByDetectedAtAscIdAsc().stream()
                .filter(m -> v.isEmpty() || m.getVendorGstin().equals(v.get().getGstin()))
                .filter(m -> type == null || m.getType() == type)
                .filter(m -> dim == null || m.getDimension() == dim)
                .filter(m -> switch (status) {
                    case "UNRESOLVED" -> m.getStatus().open();
                    case "ANY" -> true;
                    default -> m.getStatus().name().equals(status);
                })
                .filter(m -> min == null || m.getExposure().compareTo(min) >= 0)
                .filter(m -> from == null || m.getPeriod().compareTo(from) >= 0)
                .filter(m -> to == null || m.getPeriod().compareTo(to) <= 0)
                .toList();
    }

    /** Vendor by GSTIN, or by a name fragment (most specific match wins; look-alikes need the distinguishing word). */
    public Optional<Vendor> vendor(String q) {
        if (q == null || q.isBlank()) {
            return Optional.empty();
        }
        String s = q.trim().toLowerCase(Locale.ROOT);
        List<Vendor> all = vendors.findAll();
        Optional<Vendor> exact = all.stream().filter(v -> v.getGstin().equalsIgnoreCase(q.trim())
                || v.getLegalName().equalsIgnoreCase(q.trim())).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<Vendor> hits = all.stream().filter(v -> v.getLegalName().toLowerCase(Locale.ROOT).contains(s)
                || s.contains(v.getLegalName().toLowerCase(Locale.ROOT))).toList();
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    static Map<String, Object> caseLine(Mismatch m) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("case_id", m.getId());
        c.put("vendor", m.getVendorName());
        c.put("invoice", m.invoiceNo());
        c.put("period", m.getPeriod());
        c.put("type", m.getType().name());
        c.put("exposure_inr", m.getExposure());
        c.put("status", m.getStatus().name());
        if (m.getVerdict() != null) {
            c.put("outcome", m.getVerdict().name());
            c.put("outcome_period", m.getVerdictPeriod());
            if (m.getMonthsLate() != null) {
                c.put("months_late", m.getMonthsLate());
            }
            c.put("outcome_note", m.getVerdictNote());
        }
        if (m.getConfirmedLoss().signum() > 0) {
            c.put("confirmed_loss_inr", m.getConfirmedLoss());
        }
        return c;
    }

    static Ref ref(Mismatch m) {
        return new Ref("case", String.valueOf(m.getId()), m.getVendorName() + " · " + m.invoiceNo() + " · " + Fmt.month(m.getPeriod()));
    }

    private static String str(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " '" + raw + "'");
        }
    }

    private static List<String> enumNames(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        return tool(name, description, properties, List.of());
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", properties, "required", required)));
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock);
    }
}
