package com.vishwas.demo;

import com.vishwas.ingest.Gstr2bParser;
import com.vishwas.ingest.InvoiceRow;
import com.vishwas.ingest.ParsedLine;
import com.vishwas.ingest.PurchaseRegisterParser;
import com.vishwas.matching.Finding;
import com.vishwas.matching.Matcher;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;
import com.vishwas.matching.Outcome;
import com.vishwas.outcomes.OpenCase;
import com.vishwas.outcomes.OutcomeDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the seed story (April to September 2026) through the real parsers, outcome detector and matcher,
 * with no Spring and no memory, and checks that every vendor behaves exactly as the demo needs.
 */
class SeedScenarioTest {

    static final List<String> MONTHS = List.of("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09");
    static final Map<String, String> GSTIN = Map.of(
            "SBT", "36ABKFS2231Q1ZP", "SBE", "36ADSFS7710L1ZD", "KPL", "29AAHCK5512D1ZO", "GSI", "37AACCG3309R1Z8",
            "MLS", "36AAPFM8841E1ZX", "NEL", "29AAJCN6620H1Z8");

    /** A tiny in-memory stand-in for the mismatch table. */
    static final class Case {
        long id;
        String period;
        Finding finding;
        MismatchStatus status = MismatchStatus.OPEN;
        Outcome verdict;
        String verdictPeriod;
        int monthsLate;

        String gstin() {
            return finding.primary().gstin();
        }

        OpenCase open() {
            var b = finding.books();
            return new OpenCase(id, finding.type(), period, status, finding.books() != null ? b.gstin() : finding.gstr2b().gstin(),
                    finding.primary().normalisedNo(), finding.primary().invoiceNo(), finding.exposure(), b == null ? null : b.itc());
        }
    }

    static final List<Case> cases = new ArrayList<>();
    static final Map<String, List<Case>> detectedIn = new java.util.LinkedHashMap<>();

    @BeforeAll
    static void replay() throws IOException {
        var matcher = new Matcher(new Matcher.Settings(new BigDecimal("1.00"), 10, 2));
        var detector = new OutcomeDetector(new OutcomeDetector.Settings(new BigDecimal("1.00"), 2));
        AtomicLong rowIds = new AtomicLong(1);
        AtomicLong caseIds = new AtomicLong(1);
        for (String p : MONTHS) {
            List<InvoiceRow> books = rows(new PurchaseRegisterParser().parse(read(p + "/purchase-register.csv"), p, "36").lines(), rowIds);
            List<InvoiceRow> g2b = rows(new Gstr2bParser().parse(read(p + "/gstr2b.json"), p, "36AAGCD4821M1ZG").lines(), rowIds);

            var judgement = detector.judge(cases.stream().filter(c -> c.status.open()).map(Case::open).toList(), g2b, books, p);
            for (var v : judgement.verdicts()) {
                Case c = cases.stream().filter(x -> x.id == v.caseId()).findFirst().orElseThrow();
                c.verdict = v.outcome();
                c.verdictPeriod = p;
                c.monthsLate = v.monthsLate();
                c.status = v.outcome().terminal() ? MismatchStatus.RESOLVED : MismatchStatus.AT_RISK;
            }
            var remainingG2b = g2b.stream().filter(r -> !judgement.consumedRowIds().contains(r.id())).toList();
            var remainingBooks = books.stream().filter(r -> !judgement.consumedRowIds().contains(r.id())).toList();
            List<Case> found = new ArrayList<>();
            for (Finding f : matcher.match(remainingBooks, remainingG2b).findings()) {
                Case c = new Case();
                c.id = caseIds.getAndIncrement();
                c.period = p;
                c.finding = f;
                cases.add(c);
                found.add(c);
            }
            detectedIn.put(p, found);
        }
    }

    @Test
    void balajiTradersMissingInvoicesAppearedInTheNextGstr2bFourOfFourTimes() {
        var history = cases.stream().filter(c -> c.gstin().equals(GSTIN.get("SBT")) && c.period.compareTo("2026-08") < 0).toList();
        assertThat(history).hasSize(4).allSatisfy(c -> {
            assertThat(c.finding.type()).isEqualTo(MismatchType.MISSING_IN_2B);
            assertThat(c.verdict).isEqualTo(Outcome.RESOLVED_LATE);
            assertThat(c.monthsLate).isEqualTo(1);
        });
    }

    @Test
    void balajiEnterprisesIsNeverLateBeforeAugustButHadTwoAmountMismatches() {
        var history = cases.stream().filter(c -> c.gstin().equals(GSTIN.get("SBE")) && c.period.compareTo("2026-08") < 0).toList();
        assertThat(history).hasSize(2).allSatisfy(c -> assertThat(c.finding.type()).isEqualTo(MismatchType.AMOUNT_MISMATCH));
        assertThat(history).extracting(c -> c.verdict).containsExactly(Outcome.CREDIT_NOTE, Outcome.AMENDED);
    }

    @Test
    void kaveriHasFortyTwoThousandOpenExposureFromMayToJulyWhenAugustIsReconciled() {
        var kaveriOpenBeforeAugust = cases.stream()
                .filter(c -> c.gstin().equals(GSTIN.get("KPL")))
                .filter(c -> List.of("2026-05", "2026-06", "2026-07").contains(c.period))
                .toList();
        assertThat(kaveriOpenBeforeAugust).extracting(c -> c.finding.books().invoiceNo())
                .containsExactly("KPL/0502", "KPL/0547", "KPL/0589");
        BigDecimal exposure = kaveriOpenBeforeAugust.stream().map(c -> c.finding.exposure()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(exposure).isEqualByComparingTo("42000");
        var april = cases.stream().filter(c -> c.gstin().equals(GSTIN.get("KPL")) && c.period.equals("2026-04")).findFirst().orElseThrow();
        assertThat(april.finding.exposure()).isEqualByComparingTo("8640");
        assertThat(april.verdict).isEqualTo(Outcome.UNRESOLVED_AT_RISK);
    }

    @Test
    void kaveriJuneInvoiceFinallyAppearsInSeptemberThreeMonthsLate() {
        var june = cases.stream().filter(c -> c.gstin().equals(GSTIN.get("KPL")) && c.period.equals("2026-06")).findFirst().orElseThrow();
        assertThat(june.verdict).isEqualTo(Outcome.RESOLVED_LATE);
        assertThat(june.verdictPeriod).isEqualTo("2026-09");
        assertThat(june.monthsLate).isEqualTo(3);
    }

    @Test
    void godavariIsOnlyEverAFormatDifferenceAndAlwaysAConfirmedTypo() {
        var godavari = cases.stream().filter(c -> c.gstin().equals(GSTIN.get("GSI"))).toList();
        assertThat(godavari).hasSize(6).allSatisfy(c -> assertThat(c.finding.type()).isEqualTo(MismatchType.INVOICE_NO_FORMAT));
        assertThat(godavari.stream().filter(c -> c.period.compareTo("2026-09") < 0))
                .allSatisfy(c -> assertThat(c.verdict).isEqualTo(Outcome.CONFIRMED_TYPO));
    }

    @Test
    void metroAmendsTheTaxHeadEveryTime() {
        var metro = cases.stream().filter(c -> c.gstin().equals(GSTIN.get("MLS"))).toList();
        assertThat(metro).extracting(c -> c.finding.type()).containsOnly(MismatchType.TAX_HEAD_MISMATCH);
        assertThat(metro).extracting(c -> c.period).containsExactly("2026-05", "2026-06", "2026-08");
        assertThat(metro).allSatisfy(c -> assertThat(c.verdict).isEqualTo(Outcome.AMENDED));
    }

    @Test
    void nandiSettlesEveryAmountDifferenceByCreditNote() {
        var nandi = cases.stream().filter(c -> c.gstin().equals(GSTIN.get("NEL"))).toList();
        assertThat(nandi).extracting(c -> c.period).containsExactly("2026-04", "2026-06", "2026-08");
        assertThat(nandi).allSatisfy(c -> {
            assertThat(c.finding.type()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
            assertThat(c.verdict).isEqualTo(Outcome.CREDIT_NOTE);
        });
    }

    @Test
    void augustContainsEveryMismatchTypeExactlyAsDesigned() {
        Map<MismatchType, List<String>> august = detectedIn.get("2026-08").stream().collect(Collectors.groupingBy(
                c -> c.finding.type(), Collectors.mapping(c -> c.finding.primary().invoiceNo(), Collectors.toList())));
        assertThat(august.get(MismatchType.MISSING_IN_2B)).containsExactlyInAnyOrder(
                "SBT/2026/0118", "SBE-2331", "KPL/0631", "DCW/2026/1187");
        assertThat(august.get(MismatchType.MISSING_IN_BOOKS)).containsExactlyInAnyOrder("GIS/26-27/088", "DCW/2026/1178");
        assertThat(august.get(MismatchType.AMOUNT_MISMATCH)).containsExactly("NEL/7851");
        assertThat(august.get(MismatchType.TAX_HEAD_MISMATCH)).containsExactly("MLS/1131");
        assertThat(august.get(MismatchType.GSTIN_MISMATCH)).containsExactly("TMP/5520");
        assertThat(august.get(MismatchType.INVOICE_NO_FORMAT)).containsExactly("142");
        assertThat(august.get(MismatchType.DATE_MISMATCH)).containsExactly("CPL/4471");
        assertThat(august.get(MismatchType.POSSIBLE_DUPLICATE)).containsExactly("CFP/3318");
        var dcw = detectedIn.get("2026-08").stream().filter(c -> "DCW/2026/1187".equals(c.finding.primary().invoiceNo())).findFirst().orElseThrow();
        assertThat(dcw.finding.candidates()).singleElement().satisfies(c -> assertThat(c.invoiceNo()).isEqualTo("DCW/2026/1178"));
    }

    @Test
    void noMismatchesAppearForTheReliableVendorsOutsideTheDesignedOnes() {
        var expectedVendors = java.util.Set.of("36ABKFS2231Q1ZP", "36ADSFS7710L1ZD", "29AAHCK5512D1ZO", "37AACCG3309R1Z8",
                "36AAPFM8841E1ZX", "29AAJCN6620H1Z8", "36AAKFC1914B1ZY", "36AAECD7305N1ZF", "29AADCT2256K1Z1",
                "33AACCC9087F1ZM", "36AAKFG7719P1ZM");
        assertThat(cases).allSatisfy(c -> assertThat(expectedVendors).contains(c.gstin()));
        assertThat(cases.stream().filter(c -> !List.of("36ABKFS2231Q1ZP", "36ADSFS7710L1ZD", "29AAHCK5512D1ZO",
                "37AACCG3309R1Z8", "36AAPFM8841E1ZX", "29AAJCN6620H1Z8").contains(c.gstin())))
                .allSatisfy(c -> assertThat(c.period).isIn("2026-08", "2026-09"));
    }

    @Test
    void septemberJudgesAugustTheWayTheLiveDemoNeeds() {
        Map<String, Outcome> verdicts = cases.stream().filter(c -> "2026-09".equals(c.verdictPeriod))
                .collect(Collectors.toMap(c -> c.finding.primary().invoiceNo(), c -> c.verdict, (a, b) -> a));
        assertThat(verdicts).containsEntry("SBT/2026/0118", Outcome.RESOLVED_LATE)
                .containsEntry("SBE-2331", Outcome.RESOLVED_LATE)
                .containsEntry("KPL/0547", Outcome.RESOLVED_LATE)
                .containsEntry("KPL/0589", Outcome.UNRESOLVED_AT_RISK)
                .containsEntry("MLS/1131", Outcome.AMENDED)
                .containsEntry("NEL/7851", Outcome.CREDIT_NOTE)
                .containsEntry("142", Outcome.CONFIRMED_TYPO)
                .containsEntry("CPL/4471", Outcome.CONFIRMED_TYPO)
                .containsEntry("CFP/3318", Outcome.DUPLICATE)
                .containsEntry("GIS/26-27/088", Outcome.RESOLVED_LATE);
        assertThat(verdicts).doesNotContainKeys("KPL/0631", "DCW/2026/1187", "TMP/5520");
    }

    static List<InvoiceRow> rows(List<ParsedLine> lines, AtomicLong ids) {
        return lines.stream().map(l -> l.row().withId(ids.getAndIncrement())).toList();
    }

    static byte[] read(String path) throws IOException {
        try (InputStream in = SeedScenarioTest.class.getResourceAsStream("/seed/" + path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }
}
