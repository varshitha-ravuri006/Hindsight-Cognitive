package com.vishwas.memory;

import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Dimension;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchType;
import com.vishwas.matching.Outcome;
import com.vishwas.memory.hindsight.FileUpload;
import com.vishwas.memory.hindsight.MemoryItem;
import com.vishwas.outcomes.AccountantAction;
import com.vishwas.outcomes.VendorCommunication;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns every reconciliation event into a plain-English memory for Hindsight. Each item carries:
 * <ul>
 *   <li>the REAL time the event happened (history is backdated), a context label and a stable document id;</li>
 *   <li>tags {@code vendor:GSTIN}, {@code dim:DIMENSION}, {@code type:MISMATCH_TYPE}, {@code period:YYYY-MM};</li>
 *   <li>custom observation scopes, so Hindsight forms beliefs per vendor, per vendor+dimension and per
 *       dimension across vendors;</li>
 *   <li>the vendor's legal name and GSTIN as explicit entities with {@code resolve_entities=false}, so
 *       look-alike vendors (Sri Balaji Traders vs Sri Balaji Enterprises) are never merged.</li>
 * </ul>
 * Pure construction: it never calls Hindsight itself (see {@link MemoryPublisher}).
 */
@Component
public class MemoryWriter {

    public static final String CTX_DETECTED = "mismatch detected";
    public static final String CTX_ACTION = "accountant action";
    public static final String CTX_COMMUNICATION = "vendor communication";
    public static final String CTX_OUTCOME = "mismatch outcome";
    public static final String CTX_RECOMMENDATION = "vishwas recommendation";
    public static final String CTX_DECISION = "accountant decision";

    /** A Vishwas recommendation as memory sees it (the advisor module owns the entity). */
    public record RecommendationEvent(Instant at, Mismatch mismatch, String category, String cause, String headline,
                                      String nextStep, String rule) {
    }

    /** The accountant's decision on a recommendation. */
    public record DecisionEvent(Instant at, Instant recommendedAt, Mismatch mismatch, String category, String cause,
                                String decision, String reason, String note, String by) {
    }

    /** A recommendation judged against the actual outcome. */
    public record JudgedEvent(Instant at, Instant recommendedAt, Mismatch mismatch, String category, String predicted,
                              String actual, boolean correct) {
    }

    /** Invoices of one vendor in one month, for the "on time" evidence that reliable behaviour needs. */
    public record VendorMonth(String gstin, String period, Instant at, int booked, int onTime, List<String> mismatches) {
    }

    private final VendorRepository vendors;

    public MemoryWriter(VendorRepository vendors) {
        this.vendors = vendors;
    }

    // ---------------------------------------------------------------- mismatches

    public MemoryItem detected(Mismatch m) {
        String what = switch (m.getType()) {
            case MISSING_IN_2B -> "invoice " + m.getInvoiceNoBooks() + " dated " + Fmt.day(m.getInvoiceDateBooks())
                    + " (taxable " + Fmt.inr(m.getTaxableBooks()) + ", ITC " + Fmt.inr(m.getItcBooks())
                    + ") is in the purchase register but MISSING from the " + Fmt.month(m.getPeriod()) + " GSTR-2B";
            case MISSING_IN_BOOKS -> "invoice " + m.getInvoiceNoGstr2b() + " dated " + Fmt.day(m.getInvoiceDateGstr2b())
                    + " (ITC " + Fmt.inr(m.getItcGstr2b()) + ") is in the " + Fmt.month(m.getPeriod())
                    + " GSTR-2B but not yet in the purchase register";
            case AMOUNT_MISMATCH -> "invoice " + m.getInvoiceNoBooks() + " is booked at taxable " + Fmt.inr(m.getTaxableBooks())
                    + " (ITC " + Fmt.inr(m.getItcBooks()) + ") but reported in GSTR-2B at taxable " + Fmt.inr(m.getTaxableGstr2b())
                    + " (ITC " + Fmt.inr(m.getItcGstr2b()) + "), an ITC difference of " + Fmt.inr(m.getExposure());
            case TAX_HEAD_MISMATCH -> "invoice " + m.getInvoiceNoBooks() + " (ITC " + Fmt.inr(m.getItcBooks())
                    + ") carries the wrong tax head in GSTR-2B (IGST vs CGST+SGST); same amounts";
            case GSTIN_MISMATCH -> "invoice " + m.getInvoiceNoBooks() + " (ITC " + Fmt.inr(m.getItcBooks())
                    + ") is reported in GSTR-2B under a different supplier GSTIN than the one booked";
            case INVOICE_NO_FORMAT -> "invoice booked as '" + m.getInvoiceNoBooks() + "' is reported in GSTR-2B as '"
                    + m.getInvoiceNoGstr2b() + "'; same GSTIN, date and amounts (only the way the number is written differs)";
            case DATE_MISMATCH -> "invoice " + m.getInvoiceNoBooks() + " is booked with date " + Fmt.day(m.getInvoiceDateBooks())
                    + " but reported in GSTR-2B with date " + Fmt.day(m.getInvoiceDateGstr2b());
            case POSSIBLE_DUPLICATE -> "invoice " + m.getInvoiceNoBooks() + " (ITC " + Fmt.inr(m.getItcBooks())
                    + ") appears TWICE in the purchase register: a possible duplicate booking";
        };
        String text = Fmt.month(m.getPeriod()) + " GSTR-2B reconciliation, " + vendorRef(m.getVendorGstin()) + ": " + what
                + ". Mismatch type " + m.getType() + ", dimension " + m.getDimension() + ". Potential ITC exposure "
                + Fmt.inr(m.getExposure()) + " (an open exposure, not a loss).";
        return item(text, m.getDetectedAt(), CTX_DETECTED, "mismatch-" + key(m), m.getVendorGstin(), m.getDimension(),
                m.getType(), m.getPeriod(), meta(m));
    }

    public MemoryItem outcome(Mismatch m) {
        Outcome o = m.getVerdict();
        String lead = "Outcome of the " + Fmt.month(m.getPeriod()) + " " + m.getType() + " on invoice " + m.invoiceNo()
                + " from " + vendorRef(m.getVendorGstin()) + ": " + o + " (" + o.label().toLowerCase() + ")";
        String detail = switch (o) {
            case RESOLVED_LATE -> ", " + m.getMonthsLate() + " month" + (m.getMonthsLate() == 1 ? "" : "s") + " late, in the "
                    + Fmt.month(m.getVerdictPeriod()) + " GSTR-2B. Recovered/resolved " + Fmt.inr(m.getRecoveredAmount())
                    + "; no loss.";
            case UNRESOLVED_AT_RISK -> ": still unresolved " + m.getMonthsLate() + " months later (checked against the "
                    + Fmt.month(m.getVerdictPeriod()) + " GSTR-2B). Potential exposure " + Fmt.inr(m.getExposure())
                    + " remains open; nothing recovered, and it is not a confirmed loss.";
            default -> " in " + Fmt.month(m.getVerdictPeriod()) + ". Resolved " + Fmt.inr(m.getRecoveredAmount()) + "; no loss.";
        };
        String note = m.getVerdictNote() == null ? "" : " " + m.getVerdictNote();
        return item(lead + detail + note, m.getVerdictAt(), CTX_OUTCOME, "outcome-" + key(m) + "-" + o.name().toLowerCase(),
                m.getVendorGstin(), m.getDimension(), m.getType(), m.getVerdictPeriod(), withOutcome(meta(m), m));
    }

    // ---------------------------------------------------------------- accountant actions

    public MemoryItem action(AccountantAction a, Mismatch m) {
        String text;
        if (AccountantAction.WRITE_OFF.equals(a.getAction()) && m != null) {
            text = "On " + Fmt.day(a.getOccurredAt()) + " " + a.getActor() + " reversed the ITC of " + Fmt.inr(m.getConfirmedLoss())
                    + " on invoice " + m.invoiceNo() + " from " + vendorRef(a.getVendorGstin()) + " (" + Fmt.month(m.getPeriod())
                    + ") in GSTR-3B because it stayed unresolved: CONFIRMED LOSS " + Fmt.inr(m.getConfirmedLoss()) + "."
                    + (a.getApprovedBy() == null ? "" : " Approved by " + a.getApprovedBy() + ".")
                    + (a.getNote() == null ? "" : " " + a.getNote());
        } else {
            text = "On " + Fmt.day(a.getOccurredAt()) + " " + a.getActor() + " (" + a.getAction().toLowerCase().replace('_', ' ')
                    + ") about " + vendorRef(a.getVendorGstin()) + (m == null ? "" : ", invoice " + m.invoiceNo() + " ("
                    + Fmt.month(m.getPeriod()) + " " + m.getType() + ")") + ": " + Objects.toString(a.getNote(), "") ;
        }
        Dimension dim = m != null ? m.getDimension() : Dimension.RESPONSIVENESS;
        Map<String, String> meta = m != null ? meta(m) : new LinkedHashMap<>();
        meta.put("action", a.getAction());
        meta.put("actor", a.getActor());
        return item(text, a.getOccurredAt(), CTX_ACTION, "action-" + a.getVendorGstin().toLowerCase() + "-" + stamp(a.getOccurredAt())
                        + "-" + a.getAction().toLowerCase(), a.getVendorGstin(), dim, m == null ? null : m.getType(),
                Fmt.period(a.getOccurredAt()), meta);
    }

    // ---------------------------------------------------------------- vendor communication

    /**
     * One message appended to the vendor's single communication thread document (update_mode "append"), so
     * the thread reads as one conversation. Period tags are left off because they would change per message.
     */
    public MemoryItem communication(VendorCommunication c) {
        String who = c.getDirection() == VendorCommunication.Direction.OUT
                ? "Deccan Home Appliances (" + Objects.toString(c.getAuthor(), "accounts") + ") -> vendor, " + channel(c)
                : "Vendor (" + Objects.toString(c.getAuthor(), "vendor") + ") -> Deccan Home Appliances, " + channel(c);
        String promise = c.getPromiseBy() == null ? "" : " PROMISE: the vendor committed to act by " + Fmt.day(c.getPromiseBy())
                + (c.invoices().isEmpty() ? "" : " for invoices " + String.join(", ", c.invoices())) + ".";
        String text = Fmt.day(c.getOccurredAt()) + ", " + vendorRef(c.getVendorGstin()) + " thread. " + who + ": " + c.getSummary() + promise;
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("direction", c.getDirection().name());
        meta.put("channel", c.getChannel().name());
        if (!c.invoices().isEmpty()) {
            meta.put("invoices", String.join(",", c.invoices()));
        }
        if (c.getPromiseBy() != null) {
            meta.put("promise_by", c.getPromiseBy().toString());
        }
        return withMode(item(text, c.getOccurredAt(), CTX_COMMUNICATION, "thread-" + c.getVendorGstin().toLowerCase(),
                c.getVendorGstin(), Dimension.RESPONSIVENESS, null, null, meta), "append");
    }

    /** Whether a promise was kept, as its own fact (not appended: it must stand out as evidence). */
    public MemoryItem promiseResult(VendorCommunication c, String detail) {
        boolean kept = c.getPromiseStatus() == VendorCommunication.PromiseStatus.KEPT;
        String text = "Promise " + (kept ? "KEPT" : "BROKEN") + " by " + vendorRef(c.getVendorGstin()) + ": on "
                + Fmt.day(c.getOccurredAt()) + " they promised to act by " + Fmt.day(c.getPromiseBy())
                + (c.invoices().isEmpty() ? "" : " on invoices " + String.join(", ", c.invoices())) + ". "
                + (kept ? "They did." : "They did not.") + (detail == null ? "" : " " + detail);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("promise_status", kept ? "KEPT" : "BROKEN");
        meta.put("promise_by", c.getPromiseBy().toString());
        return item(text, c.getPromiseCheckedAt(), CTX_COMMUNICATION, "promise-" + c.getVendorGstin().toLowerCase() + "-"
                + stamp(c.getOccurredAt()), c.getVendorGstin(), Dimension.RESPONSIVENESS, null, Fmt.period(c.getPromiseCheckedAt()), meta);
    }

    /** A letter or e-mail file, retained through /files/retain with the vendor's tags. */
    public FileUpload letter(VendorCommunication c, byte[] pdf) {
        return new FileUpload(c.getAttachment(), "application/pdf", pdf,
                "letter-" + c.getVendorGstin().toLowerCase() + "-" + stamp(c.getOccurredAt()), CTX_COMMUNICATION,
                iso(c.getOccurredAt()), List.of(vendorTag(c.getVendorGstin()), Dimension.RESPONSIVENESS.tag(),
                "period:" + Fmt.period(c.getOccurredAt())),
                Map.of("vendor", legalName(c.getVendorGstin()), "gstin", c.getVendorGstin(), "kind", "letter"));
    }

    // ---------------------------------------------------------------- the learning loop

    public MemoryItem recommendation(RecommendationEvent r) {
        Mismatch m = r.mismatch();
        String text = "Vishwas recommendation on " + Fmt.day(r.at()) + " for invoice " + m.invoiceNo() + " from "
                + vendorRef(m.getVendorGstin()) + " (" + Fmt.month(m.getPeriod()) + " " + m.getType() + ", exposure "
                + Fmt.inr(m.getExposure()) + "): category " + r.category() + ", most likely cause " + r.cause()
                + (r.rule() == null ? "" : ", approved rule " + r.rule()) + ". " + Objects.toString(r.headline(), "")
                + " Suggested next step: " + Objects.toString(r.nextStep(), "none") + ".";
        Map<String, String> meta = meta(m);
        meta.put("category", r.category());
        meta.put("cause", r.cause());
        return item(text, r.at(), CTX_RECOMMENDATION, "rec-" + key(m) + "-" + stamp(r.at()), m.getVendorGstin(), m.getDimension(),
                m.getType(), Fmt.period(r.at()), meta);
    }

    public MemoryItem decision(DecisionEvent d) {
        Mismatch m = d.mismatch();
        String text = d.by() + " " + d.decision() + " Vishwas's recommendation (" + d.category() + ", cause " + d.cause()
                + ") for invoice " + m.invoiceNo() + " from " + vendorRef(m.getVendorGstin()) + " on " + Fmt.day(d.at())
                + "; reason: " + d.reason() + (d.note() == null || d.note().isBlank() ? "." : ". Note: " + d.note());
        Map<String, String> meta = meta(m);
        meta.put("decision", d.decision());
        meta.put("reason", d.reason());
        return item(text, d.at(), CTX_DECISION, "decision-" + key(m) + "-" + stamp(d.recommendedAt()), m.getVendorGstin(),
                m.getDimension(), m.getType(), Fmt.period(d.at()), meta);
    }

    public MemoryItem judged(JudgedEvent j) {
        Mismatch m = j.mismatch();
        String text = "Vishwas's recommendation of " + Fmt.day(j.recommendedAt()) + " on invoice " + m.invoiceNo() + " from "
                + vendorRef(m.getVendorGstin()) + " (" + j.category() + ", expected " + j.predicted() + ") proved "
                + (j.correct() ? "RIGHT" : "WRONG") + ": the actual outcome was " + j.actual() + " (judged "
                + Fmt.day(j.at()) + ").";
        Map<String, String> meta = meta(m);
        meta.put("recommendation_correct", String.valueOf(j.correct()));
        return item(text, j.at(), CTX_RECOMMENDATION, "rec-judged-" + key(m) + "-" + stamp(j.recommendedAt()), m.getVendorGstin(),
                m.getDimension(), m.getType(), Fmt.period(j.at()), meta);
    }

    // ---------------------------------------------------------------- reliable behaviour

    public MemoryItem vendorMonth(VendorMonth v) {
        String text = Fmt.month(v.period()) + " reconciliation summary for " + vendorRef(v.gstin()) + ": " + v.booked()
                + " invoice" + (v.booked() == 1 ? "" : "s") + " booked, " + v.onTime() + " appeared in the " + Fmt.month(v.period())
                + " GSTR-2B on time" + (v.mismatches().isEmpty() ? " with no mismatches." : "; mismatches: "
                + String.join(", ", v.mismatches()) + ".");
        return item(text, v.at(), CTX_DETECTED, "summary-" + v.gstin().toLowerCase() + "-" + v.period(), v.gstin(),
                Dimension.TIMING, null, v.period(), new LinkedHashMap<>(Map.of("period", v.period(), "summary", "true")));
    }

    // ---------------------------------------------------------------- plumbing

    MemoryItem item(String text, Instant at, String context, String documentId, String gstin, Dimension dim, MismatchType type,
                    String period, Map<String, String> metadata) {
        List<String> tags = new ArrayList<>();
        tags.add(vendorTag(gstin));
        tags.add(dim.tag());
        if (type != null) {
            tags.add(type.tag());
        }
        if (period != null) {
            tags.add("period:" + period);
        }
        Map<String, String> meta = new LinkedHashMap<>(metadata);
        meta.put("vendor", legalName(gstin));
        meta.put("gstin", gstin);
        meta.put("dimension", dim.name());
        return new MemoryItem(text, iso(at), context, documentId, tags, meta, scopes(gstin, dim),
                List.of(new MemoryItem.EntityInput(legalName(gstin), "ORGANIZATION"), new MemoryItem.EntityInput(gstin, "GSTIN")),
                false, null);
    }

    /** Beliefs per vendor, per vendor+dimension, and per dimension across all vendors. */
    public static List<List<String>> scopes(String gstin, Dimension dim) {
        return List.of(List.of(vendorTag(gstin)), List.of(vendorTag(gstin), dim.tag()), List.of(dim.tag()));
    }

    public static String vendorTag(String gstin) {
        return "vendor:" + gstin;
    }

    /** Natural key of a mismatch, stable across resets and re-runs. */
    public static String key(Mismatch m) {
        return (m.getVendorGstin() + "-" + m.getInvoiceNoNorm() + "-" + m.getType() + "-" + m.getPeriod()).toLowerCase()
                .replaceAll("[^a-z0-9-]", "");
    }

    private static MemoryItem withMode(MemoryItem i, String mode) {
        return new MemoryItem(i.content(), i.timestamp(), i.context(), i.documentId(), i.tags(), i.metadata(),
                i.observationScopes(), i.entities(), i.resolveEntities(), mode);
    }

    private Map<String, String> meta(Mismatch m) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("period", m.getPeriod());
        meta.put("invoice", m.invoiceNo());
        meta.put("type", m.getType().name());
        meta.put("exposure", plain(m.getExposure()));
        if (m.getId() != null) {
            meta.put("mismatch_id", String.valueOf(m.getId()));
        }
        return meta;
    }

    private static Map<String, String> withOutcome(Map<String, String> meta, Mismatch m) {
        meta.put("outcome", m.getVerdict().name());
        if (m.getMonthsLate() != null) {
            meta.put("months_late", String.valueOf(m.getMonthsLate()));
        }
        meta.put("recovered", plain(m.getRecoveredAmount()));
        return meta;
    }

    private String vendorRef(String gstin) {
        return legalName(gstin) + " (GSTIN " + gstin + ")";
    }

    String legalName(String gstin) {
        return vendors.findById(gstin).map(Vendor::getLegalName).orElse(gstin);
    }

    private static String channel(VendorCommunication c) {
        return switch (c.getChannel()) {
            case EMAIL -> "e-mail";
            case PHONE -> "phone call";
            case LETTER -> "letter";
            case MEETING -> "meeting";
        };
    }

    static String iso(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, Fmt.IST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String stamp(Instant at) {
        return at == null ? "0" : DateTimeFormatter.ofPattern("yyyyMMddHHmm").format(at.atZone(Fmt.IST));
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
