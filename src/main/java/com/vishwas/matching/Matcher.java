package com.vishwas.matching;

import com.vishwas.ingest.InvoiceNumbers;
import com.vishwas.ingest.InvoiceRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The deterministic matcher: plain Java, no AI, no I/O.
 * <ol>
 *   <li><b>Duplicates</b>: two books rows with the same GSTIN and normalised number: the later one is
 *       POSSIBLE_DUPLICATE.</li>
 *   <li><b>Exact key</b>: GSTIN + normalised invoice number. A keyed pair is compared field by field with a
 *       rupee tolerance and reported as AMOUNT_MISMATCH, TAX_HEAD_MISMATCH, DATE_MISMATCH or
 *       INVOICE_NO_FORMAT (in that priority), or counted as an exact match.</li>
 *   <li><b>GSTIN_MISMATCH</b>: same normalised number and amounts, but under a different supplier GSTIN.</li>
 *   <li><b>MISSING_IN_2B</b> for the rest of the books, each with fuzzy CANDIDATES (same GSTIN, date within the
 *       window, and amount within tolerance or a similar number). Candidates are shown, never matched.</li>
 *   <li><b>MISSING_IN_BOOKS</b> for unconsumed GSTR-2B invoices, linked to the books row they are a candidate
 *       for.</li>
 * </ol>
 * Only INVOICE rows are matched here; GSTR-2B amendments and credit notes are judged by the outcome detector.
 */
public class Matcher {

    public record Settings(BigDecimal tolerance, int dateWindowDays, int maxEditDistance) {
    }

    private final Settings settings;

    public Matcher(Settings settings) {
        this.settings = settings;
    }

    public MatchResult match(List<InvoiceRow> booksIn, List<InvoiceRow> gstr2bIn) {
        List<InvoiceRow> books = booksIn.stream().filter(r -> r.kind() == InvoiceRow.Kind.INVOICE).toList();
        List<InvoiceRow> g2b = gstr2bIn.stream().filter(r -> r.kind() == InvoiceRow.Kind.INVOICE).toList();
        List<Finding> findings = new ArrayList<>();

        // 1. duplicates inside the books
        Map<String, InvoiceRow> firstByKey = new LinkedHashMap<>();
        List<InvoiceRow> primaries = new ArrayList<>();
        List<InvoiceRow> duplicates = new ArrayList<>();
        for (InvoiceRow b : books) {
            String key = key(b.gstin(), b.normalisedNo());
            if (firstByKey.putIfAbsent(key, b) == null) {
                primaries.add(b);
            } else {
                duplicates.add(b);
            }
        }

        // 2. exact key
        Map<String, List<InvoiceRow>> g2bByKey = new HashMap<>();
        g2b.forEach(r -> g2bByKey.computeIfAbsent(key(r.gstin(), r.normalisedNo()), k -> new ArrayList<>()).add(r));
        Set<InvoiceRow> consumed = new HashSet<>();
        Map<String, InvoiceRow> matchedByKey = new HashMap<>();
        List<InvoiceRow> unmatchedBooks = new ArrayList<>();
        int exact = 0;
        for (InvoiceRow b : primaries) {
            InvoiceRow g = g2bByKey.getOrDefault(key(b.gstin(), b.normalisedNo()), List.of()).stream()
                    .filter(r -> !consumed.contains(r)).findFirst().orElse(null);
            if (g == null) {
                unmatchedBooks.add(b);
                continue;
            }
            consumed.add(g);
            matchedByKey.put(key(b.gstin(), b.normalisedNo()), g);
            Finding f = compare(b, g);
            if (f == null) {
                exact++;
            } else {
                findings.add(f);
            }
        }

        for (InvoiceRow d : duplicates) {
            InvoiceRow first = firstByKey.get(key(d.gstin(), d.normalisedNo()));
            findings.add(new Finding(MismatchType.POSSIBLE_DUPLICATE, d, matchedByKey.get(key(d.gstin(), d.normalisedNo())),
                    List.of(new Finding.Difference("booked", describe(first), describe(d))), List.of(), d.itc(), first.id(),
                    "Invoice " + d.invoiceNo() + " appears more than once in the purchase register."));
        }

        // 3. same number and amounts under a different GSTIN
        List<InvoiceRow> stillMissing = new ArrayList<>();
        for (InvoiceRow b : unmatchedBooks) {
            InvoiceRow other = g2b.stream()
                    .filter(r -> !consumed.contains(r) && !r.gstin().equals(b.gstin()))
                    .filter(r -> sameLegalEntityOrTypo(b.gstin(), r.gstin()))
                    .filter(r -> r.normalisedNo().equals(b.normalisedNo()) && amountsEqual(b, r))
                    .filter(r -> withinWindow(b.invoiceDate(), r.invoiceDate()))
                    .findFirst().orElse(null);
            if (other == null) {
                stillMissing.add(b);
            } else {
                consumed.add(other);
                findings.add(new Finding(MismatchType.GSTIN_MISMATCH, b, other,
                        List.of(new Finding.Difference("supplier GSTIN", b.gstin(), other.gstin())), List.of(), b.itc(), null,
                        "The same invoice is reported under a different supplier GSTIN."));
            }
        }

        // 4. missing in 2B, with candidates
        Map<Long, Long> candidateOf = new HashMap<>();
        for (InvoiceRow b : stillMissing) {
            List<Finding.Candidate> candidates = g2b.stream()
                    .filter(r -> !consumed.contains(r) && r.gstin().equals(b.gstin()))
                    .map(r -> candidate(b, r))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt((Finding.Candidate c) -> -c.reasons().size())
                            .thenComparingLong(Finding.Candidate::dateGapDays))
                    .toList();
            candidates.forEach(c -> candidateOf.putIfAbsent(c.rowId(), b.id()));
            findings.add(new Finding(MismatchType.MISSING_IN_2B, b, null, List.of(), candidates, b.itc(), null,
                    candidates.isEmpty() ? null : "Possible match found in GSTR-2B; confirm before treating it as the same invoice."));
        }

        // 5. missing in books
        for (InvoiceRow g : g2b) {
            if (!consumed.contains(g)) {
                Long linked = candidateOf.get(g.id());
                findings.add(new Finding(MismatchType.MISSING_IN_BOOKS, null, g, List.of(), List.of(), BigDecimal.ZERO.setScale(2),
                        linked, linked == null ? null : "Listed as a possible match for an invoice missing from GSTR-2B."));
            }
        }
        return new MatchResult(exact, books.size(), g2b.size(), findings);
    }

    /** Field-by-field comparison of a keyed pair; null when they agree. */
    Finding compare(InvoiceRow b, InvoiceRow g) {
        List<Finding.Difference> diffs = new ArrayList<>();
        boolean amount = false;
        boolean taxHead = false;
        boolean date = false;
        boolean number = InvoiceNumbers.formatDiffers(b.invoiceNo(), g.invoiceNo());
        if (number) {
            diffs.add(new Finding.Difference("invoice number", b.invoiceNo(), g.invoiceNo()));
        }
        if (!Objects.equals(b.invoiceDate(), g.invoiceDate())) {
            date = true;
            diffs.add(new Finding.Difference("invoice date", String.valueOf(b.invoiceDate()), String.valueOf(g.invoiceDate())));
        }
        if (b.intraState() != g.intraState() && b.itc().signum() != 0 && g.itc().signum() != 0) {
            taxHead = true;
            diffs.add(new Finding.Difference("tax head", heads(b), heads(g)));
        }
        if (!close(b.taxableValue(), g.taxableValue())) {
            amount = true;
            diffs.add(new Finding.Difference("taxable value", money(b.taxableValue()), money(g.taxableValue())));
        }
        if (!close(b.itc(), g.itc())) {
            amount = true;
            diffs.add(new Finding.Difference("total tax (ITC)", money(b.itc()), money(g.itc())));
        }
        MismatchType type = amount ? MismatchType.AMOUNT_MISMATCH
                : taxHead ? MismatchType.TAX_HEAD_MISMATCH
                : date ? MismatchType.DATE_MISMATCH
                : number ? MismatchType.INVOICE_NO_FORMAT
                : null;
        if (type == null) {
            return null;
        }
        return new Finding(type, b, g, diffs, List.of(), exposure(type, b, g), null, null);
    }

    /**
     * Potential ITC exposure of a mismatch at detection. It is never a loss: it is the credit that could be
     * denied if the difference is not resolved.
     * <ul>
     *   <li>MISSING_IN_2B, TAX_HEAD_MISMATCH, GSTIN_MISMATCH: the full ITC claimed in the books.</li>
     *   <li>AMOUNT_MISMATCH: the absolute ITC difference (the credit in dispute).</li>
     *   <li>POSSIBLE_DUPLICATE: the ITC of the duplicate booking (an over-claim).</li>
     *   <li>INVOICE_NO_FORMAT, DATE_MISMATCH, MISSING_IN_BOOKS: zero (the credit is visible in GSTR-2B).</li>
     * </ul>
     */
    public static BigDecimal exposure(MismatchType type, InvoiceRow books, InvoiceRow g2b) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        return switch (type) {
            case MISSING_IN_2B, TAX_HEAD_MISMATCH, GSTIN_MISMATCH, POSSIBLE_DUPLICATE -> books == null ? zero : books.itc();
            case AMOUNT_MISMATCH -> books == null || g2b == null ? zero : books.itc().subtract(g2b.itc()).abs();
            case INVOICE_NO_FORMAT, DATE_MISMATCH, MISSING_IN_BOOKS -> zero;
        };
    }

    private Finding.Candidate candidate(InvoiceRow b, InvoiceRow r) {
        if (b.invoiceDate() == null || r.invoiceDate() == null) {
            return null;
        }
        long gap = Math.abs(ChronoUnit.DAYS.between(b.invoiceDate(), r.invoiceDate()));
        if (gap > settings.dateWindowDays()) {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        reasons.add(gap == 0 ? "same invoice date" : "invoice date " + gap + " day" + (gap == 1 ? "" : "s") + " apart");
        boolean sameAmount = amountsEqual(b, r);
        if (sameAmount) {
            reasons.add("same amounts");
        }
        int edits = InvoiceNumbers.editDistance(b.normalisedNo(), r.normalisedNo());
        boolean similar = InvoiceNumbers.similar(b.invoiceNo(), r.invoiceNo(), settings.maxEditDistance());
        if (similar) {
            reasons.add("invoice number " + edits + " character" + (edits == 1 ? "" : "s") + " different");
        }
        if (!sameAmount && !similar) {
            return null;
        }
        return new Finding.Candidate(r.id(), r.invoiceNo(), r.normalisedNo(), r.invoiceDate(), r.taxableValue(), r.itc(),
                gap, b.itc().subtract(r.itc()).abs(), edits, reasons);
    }

    /**
     * A GSTIN_MISMATCH is only plausible between registrations of the same legal entity (same PAN, e.g. a
     * branch in another state) or when one GSTIN is a typo of the other. Two look-alike vendors (different
     * PANs) are never paired, even if an invoice number and amount happen to coincide.
     */
    static boolean sameLegalEntityOrTypo(String a, String b) {
        if (a == null || b == null || a.length() != 15 || b.length() != 15) {
            return false;
        }
        return a.substring(2, 12).equals(b.substring(2, 12)) || InvoiceNumbers.editDistance(a, b) <= 2;
    }

    private boolean amountsEqual(InvoiceRow a, InvoiceRow b) {
        return close(a.taxableValue(), b.taxableValue()) && close(a.itc(), b.itc());
    }

    private boolean close(BigDecimal a, BigDecimal b) {
        BigDecimal x = a == null ? BigDecimal.ZERO : a;
        BigDecimal y = b == null ? BigDecimal.ZERO : b;
        return x.subtract(y).abs().compareTo(settings.tolerance()) <= 0;
    }

    private boolean withinWindow(LocalDate a, LocalDate b) {
        return a == null || b == null || Math.abs(ChronoUnit.DAYS.between(a, b)) <= settings.dateWindowDays();
    }

    private static String key(String gstin, String normalised) {
        return gstin + "|" + normalised;
    }

    private static String heads(InvoiceRow r) {
        return r.intraState() ? "CGST " + money(r.cgst()) + " + SGST " + money(r.sgst()) : "IGST " + money(r.igst());
    }

    private static String describe(InvoiceRow r) {
        return r.invoiceNo() + " dated " + r.invoiceDate() + ", ITC " + money(r.itc());
    }

    static String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
