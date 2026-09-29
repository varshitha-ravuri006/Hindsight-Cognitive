package com.vishwas.outcomes;

import com.vishwas.ingest.InvoiceRow;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;
import com.vishwas.matching.Outcome;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The core idea of Vishwas: when a later GSTR-2B arrives, judge every OPEN mismatch against it. Plain Java,
 * deterministic, no AI.
 *
 * <table>
 *   <tr><th>Open case</th><th>Evidence in the later data</th><th>Verdict</th></tr>
 *   <tr><td>MISSING_IN_2B</td><td>same invoice now in GSTR-2B (B2B)</td><td>RESOLVED_LATE, n months late</td></tr>
 *   <tr><td>MISSING_IN_2B</td><td>amendment (B2BA) or different amounts</td><td>AMENDED</td></tr>
 *   <tr><td>AMOUNT_MISMATCH</td><td>credit note against the invoice (CDNR)</td><td>CREDIT_NOTE</td></tr>
 *   <tr><td>AMOUNT / TAX_HEAD / GSTIN / format / date</td><td>amendment of the invoice (B2BA)</td><td>AMENDED</td></tr>
 *   <tr><td>GSTIN_MISMATCH</td><td>invoice now reported under the booked GSTIN</td><td>AMENDED</td></tr>
 *   <tr><td>INVOICE_NO_FORMAT, DATE_MISMATCH</td><td>no amendment and no conflicting invoice</td><td>CONFIRMED_TYPO</td></tr>
 *   <tr><td>POSSIBLE_DUPLICATE</td><td>no second invoice with that number from the vendor</td><td>DUPLICATE</td></tr>
 *   <tr><td>MISSING_IN_BOOKS</td><td>invoice now in the books</td><td>RESOLVED_LATE</td></tr>
 *   <tr><td>any ITC-carrying case</td><td>nothing, and N months have passed</td><td>UNRESOLVED_AT_RISK (still open)</td></tr>
 * </table>
 * Each later row resolves at most one case; consumed rows are reported so the matcher does not also report
 * a late-filed invoice as MISSING_IN_BOOKS.
 */
public class OutcomeDetector {

    public record Settings(BigDecimal tolerance, int atRiskAfterMonths) {
    }

    /** One judgement. {@code evidenceRowId} is the later row that proves it (0 when none). */
    public record Verdict(long caseId, Outcome outcome, long evidenceRowId, int monthsLate, BigDecimal recovered,
                          String explanation) {
    }

    public record Judgement(List<Verdict> verdicts, Set<Long> consumedRowIds) {
    }

    private final Settings settings;

    public OutcomeDetector(Settings settings) {
        this.settings = settings;
    }

    /**
     * @param open            open mismatches from earlier periods (OPEN or AT_RISK)
     * @param later2b         the newly arrived GSTR-2B rows (invoices, amendments, credit notes)
     * @param laterBooks      the purchase register of the same later period (may be empty)
     * @param judgementPeriod the period of the later GSTR-2B
     */
    public Judgement judge(List<OpenCase> open, List<InvoiceRow> later2b, List<InvoiceRow> laterBooks, String judgementPeriod) {
        YearMonth now = YearMonth.parse(judgementPeriod);
        Set<Long> consumed = new HashSet<>();
        List<Verdict> verdicts = new ArrayList<>();
        List<OpenCase> ordered = open.stream()
                .filter(c -> c.status().open())
                .filter(c -> YearMonth.parse(c.period()).isBefore(now))
                .sorted(Comparator.comparing(OpenCase::period).thenComparingLong(OpenCase::id))
                .toList();
        for (OpenCase c : ordered) {
            int months = (int) ChronoUnit.MONTHS.between(YearMonth.parse(c.period()), now);
            Optional<Verdict> v = decide(c, months, later2b, laterBooks, consumed, judgementPeriod);
            if (v.isPresent()) {
                verdicts.add(v.get());
                if (v.get().evidenceRowId() != 0) {
                    consumed.add(v.get().evidenceRowId());
                }
            } else if (c.type().carriesItcRisk() && c.status() == MismatchStatus.OPEN && months >= settings.atRiskAfterMonths()) {
                verdicts.add(new Verdict(c.id(), Outcome.UNRESOLVED_AT_RISK, 0, months, BigDecimal.ZERO,
                        "Still unresolved " + months + " months after " + label(c.period()) + "; nothing in the "
                                + label(judgementPeriod) + " GSTR-2B."));
            }
        }
        return new Judgement(verdicts, consumed);
    }

    private Optional<Verdict> decide(OpenCase c, int months, List<InvoiceRow> g2b, List<InvoiceRow> books,
                                     Set<Long> consumed, String period) {
        String in = " in the " + label(period) + " GSTR-2B";
        Predicate<InvoiceRow> free = r -> !consumed.contains(r.id());
        Predicate<InvoiceRow> sameVendor = r -> r.gstin().equals(c.gstin());
        Predicate<InvoiceRow> sameInvoice = r -> c.invoiceNoNorm().equals(r.normalisedNo());
        Predicate<InvoiceRow> refersToInvoice = r -> c.invoiceNoNorm().equals(r.normalisedOriginalNo())
                || (r.originalInvoiceNo() == null && c.invoiceNoNorm().equals(r.normalisedNo()));

        Optional<InvoiceRow> amendment = g2b.stream()
                .filter(free).filter(sameVendor).filter(r -> r.kind() == InvoiceRow.Kind.AMENDMENT).filter(refersToInvoice)
                .findFirst();
        Optional<InvoiceRow> invoice = g2b.stream()
                .filter(free).filter(sameVendor).filter(r -> r.kind() == InvoiceRow.Kind.INVOICE).filter(sameInvoice)
                .findFirst();

        return switch (c.type()) {
            case MISSING_IN_2B -> {
                if (invoice.isPresent()) {
                    InvoiceRow r = invoice.get();
                    boolean same = c.itcBooks() == null || close(c.itcBooks(), r.itc());
                    yield Optional.of(new Verdict(c.id(), same ? Outcome.RESOLVED_LATE : Outcome.AMENDED, r.id(), months,
                            c.exposure(), (same ? "Appeared " : "Appeared with different amounts ") + months + " month"
                            + (months == 1 ? "" : "s") + " late" + in + "."));
                }
                yield amendment.map(r -> new Verdict(c.id(), Outcome.AMENDED, r.id(), months, c.exposure(),
                        "Reported through an amendment" + in + "."));
            }
            case AMOUNT_MISMATCH -> {
                Optional<InvoiceRow> note = g2b.stream().filter(free).filter(sameVendor)
                        .filter(r -> r.kind() == InvoiceRow.Kind.CREDIT_NOTE)
                        .filter(r -> c.invoiceNoNorm().equals(r.normalisedOriginalNo())
                                || (r.originalInvoiceNo() == null && close(r.itc(), c.exposure())))
                        .findFirst();
                if (note.isPresent()) {
                    yield Optional.of(new Verdict(c.id(), Outcome.CREDIT_NOTE, note.get().id(), months, c.exposure(),
                            "Credit note " + note.get().invoiceNo() + " for Rs " + note.get().itc().toPlainString()
                                    + " ITC settled the difference" + in + "."));
                }
                yield amendment.map(r -> new Verdict(c.id(), Outcome.AMENDED, r.id(), months, c.exposure(),
                        "Vendor amended the invoice amounts" + in + "."));
            }
            case TAX_HEAD_MISMATCH -> amendment.map(r -> new Verdict(c.id(), Outcome.AMENDED, r.id(), months, c.exposure(),
                    "Vendor amended the tax head to " + (r.intraState() ? "CGST+SGST" : "IGST") + in + "."));
            case GSTIN_MISMATCH -> {
                if (amendment.isPresent()) {
                    yield Optional.of(new Verdict(c.id(), Outcome.AMENDED, amendment.get().id(), months, c.exposure(),
                            "Now reported under the booked GSTIN through an amendment" + in + "."));
                }
                yield invoice.map(r -> new Verdict(c.id(), Outcome.AMENDED, r.id(), months, c.exposure(),
                        "Now reported under the booked GSTIN" + in + "."));
            }
            case INVOICE_NO_FORMAT, DATE_MISMATCH -> {
                if (amendment.isPresent()) {
                    yield Optional.of(new Verdict(c.id(), Outcome.AMENDED, amendment.get().id(), months, c.exposure(),
                            "Vendor amended the invoice details" + in + "."));
                }
                if (invoice.isPresent()) {
                    yield Optional.empty(); // a second invoice with the same number: conflicting records, leave open
                }
                yield Optional.of(new Verdict(c.id(), Outcome.CONFIRMED_TYPO, 0, months, c.exposure(),
                        "No amendment and no conflicting invoice" + in + ": the difference was only how it was written."));
            }
            case POSSIBLE_DUPLICATE -> {
                if (invoice.isPresent()) {
                    yield Optional.empty(); // the vendor did report another invoice with this number
                }
                yield Optional.of(new Verdict(c.id(), Outcome.DUPLICATE, 0, months, c.exposure(),
                        "No second invoice " + c.invoiceNo() + " from the vendor" + in + ": the second booking is a duplicate."));
            }
            case MISSING_IN_BOOKS -> books.stream().filter(free).filter(sameVendor).filter(sameInvoice)
                    .filter(r -> r.kind() == InvoiceRow.Kind.INVOICE).findFirst()
                    .map(r -> new Verdict(c.id(), Outcome.RESOLVED_LATE, r.id(), months, c.exposure(),
                            "Booked " + months + " month" + (months == 1 ? "" : "s") + " later in the purchase register."));
        };
    }

    private boolean close(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(settings.tolerance()) <= 0;
    }

    static String label(String period) {
        YearMonth ym = YearMonth.parse(period);
        return ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + ym.getYear();
    }
}
