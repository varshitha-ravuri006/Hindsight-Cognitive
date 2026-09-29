package com.vishwas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.advisor.Category;
import com.vishwas.advisor.LearningLoop;
import com.vishwas.advisor.Recommendation;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.advisor.VendorHistoryService;
import com.vishwas.demo.HistoryLoader;
import com.vishwas.demo.SeedCatalog;
import com.vishwas.matching.Dimension;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.Outcome;
import com.vishwas.memory.MemoryHealth;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import com.vishwas.support.StubApis;
import com.vishwas.workflow.ReconcileService;
import com.vishwas.workflow.WorkspaceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole story end to end with Hindsight and Groq stubbed: load the history, reconcile August with memory,
 * check the guardrailed categories, money and look-alike isolation, then let September's GSTR-2B judge August.
 */
@SpringBootTest(properties = {"vishwas.advice.parallelism=4", "spring.datasource.url=jdbc:h2:mem:vishwas-seed-it;DB_CLOSE_DELAY=-1"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedLoadIntegrationTest {

    static final String SBT = "36ABKFS2231Q1ZP";
    static final String SBE = "36ADSFS7710L1ZD";
    static final String KPL = "29AAHCK5512D1ZO";
    static final String GSI = "37AACCG3309R1Z8";
    static final String CFP = "36AAKFC1914B1ZY";
    static final String TMP = "29AADCT2256K1Z1";
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern CASE_ID = Pattern.compile("case_id (\\d+)");
    static final StubApis STUB = startStub();

    @DynamicPropertySource
    static void apis(DynamicPropertyRegistry r) {
        r.add("vishwas.hindsight.base-url", STUB::baseUrl);
        r.add("vishwas.hindsight.api-key", () -> "test-key");
        r.add("vishwas.hindsight.bank-id", () -> "vishwas-test");
        r.add("vishwas.groq.base-url", STUB::baseUrl);
        r.add("vishwas.groq.api-key", () -> "test-key");
    }

    static StubApis startStub() {
        try {
            StubApis s = new StubApis();
            s.on("POST", "/reflect", SeedLoadIntegrationTest::reflect);
            s.on("POST", "/chat/completions", SeedLoadIntegrationTest::baseline);
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A memory that answers per vendor, as a well-tuned bank would. Deliberately proposes RECOMMEND for the duplicate. */
    static StubApis.Reply reflect(StubApis.Call c) {
        String gstin = c.body().path("tags").get(0).asText().replace("vendor:", "");
        String category = switch (gstin) {
            case SBT -> "RECOMMEND";
            case KPL -> "REQUIRE_REVIEW";
            case GSI -> "RECOMMEND";
            case SBE -> "RECOMMEND";
            default -> "RECOMMEND";
        };
        String cause = switch (gstin) {
            case SBT -> "TIMING_DIFFERENCE";
            case KPL -> "VENDOR_NOT_FILING";
            case GSI -> "DATA_ENTRY_TYPO";
            case SBE -> "TIMING_DIFFERENCE";
            default -> "AMENDMENT_EXPECTED";
        };
        List<Map<String, Object>> perInvoice = new ArrayList<>();
        var m = CASE_ID.matcher(c.body().path("query").asText());
        while (m.find()) {
            perInvoice.add(Map.of("case_id", Long.parseLong(m.group(1)), "invoice", "x", "category", category,
                    "headline", "memory says " + cause,
                    "cause_hypotheses", List.of(Map.of("cause", cause, "likelihood", "HIGH", "evidence", "past cases of " + gstin)),
                    "next_step", "Check the next GSTR-2B", "confidence", "HIGH", "evidence_refs", List.of("ref " + gstin)));
        }
        try {
            return StubApis.Reply.ok(JSON.writeValueAsString(Map.of("text", "ok",
                    "structured_output", Map.of("vendor", gstin, "per_invoice", perInvoice, "vendor_summary", "summary " + gstin, "exposure_inr", 0),
                    "based_on", Map.of("memories", List.of(Map.of("id", "fact-" + gstin, "text", "fact about " + gstin, "type", "world",
                            "tags", List.of("vendor:" + gstin))), "directives", List.of(), "mental_models", List.of()))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The no-memory baseline treats everything alike. */
    static StubApis.Reply baseline(StubApis.Call c) {
        List<Map<String, Object>> cases = new ArrayList<>();
        var m = CASE_ID.matcher(c.body().path("messages").toString());
        while (m.find()) {
            cases.add(Map.of("case_id", Long.parseLong(m.group(1)), "category", "REQUIRE_REVIEW", "action", "Send the vendor a reminder."));
        }
        try {
            return StubApis.Reply.ok(StubApis.groqText(JSON.writeValueAsString(Map.of("cases", cases))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    @Autowired HistoryLoader history;
    @Autowired ReconcileService reconcile;
    @Autowired WorkspaceService workspace;
    @Autowired MismatchRepository mismatches;
    @Autowired RecommendationRepository recommendations;
    @Autowired VendorCommunicationRepository communications;
    @Autowired VendorHistoryService vendorHistory;
    @Autowired LearningLoop learning;
    @Autowired MemoryHealth health;
    @Autowired SeedCatalog seed;

    @Test
    @Order(1)
    void loadsHistoryIntoDatabaseAndMemoryMonthByMonth() throws Exception {
        waitFor(health::ready);
        history.start();
        waitFor(() -> history.status().memoryLoaded() && !history.status().memory().busy());

        // database: the story as designed
        Mismatch kpl0456 = find(KPL, "KPL/0456");
        assertThat(kpl0456.getStatus()).isEqualTo(MismatchStatus.WRITTEN_OFF);
        assertThat(kpl0456.getConfirmedLoss()).isEqualByComparingTo("8640");
        assertThat(find(KPL, "KPL/0502").getStatus()).isEqualTo(MismatchStatus.AT_RISK);
        assertThat(promise(KPL).getPromiseStatus()).isEqualTo(VendorCommunication.PromiseStatus.BROKEN);
        assertThat(promise("36AAPFM8841E1ZX").getPromiseStatus()).isEqualTo(VendorCommunication.PromiseStatus.KEPT);
        assertThat(rec(KPL, "KPL/0502").getWasCorrect()).isFalse();
        assertThat(rec(SBT, "SBT/2026/0057").getWasCorrect()).isTrue();
        assertThat(rec("36ADSFS7710L1ZD", "SBE-2211").getWasCorrect()).isFalse();
        assertThat(history.summary().line()).contains("Apr to Jul 2026").contains("Rs 8,640 confirmed loss");

        // memory: four async monthly batches, backdated, tagged, scoped, with explicit entities
        List<StubApis.Call> retains = STUB.calls("POST", "/banks/vishwas-test/memories");
        assertThat(retains).hasSizeGreaterThanOrEqualTo(4);
        List<JsonNode> items = new ArrayList<>();
        retains.forEach(r -> r.body().path("items").forEach(items::add));
        assertThat(retains.get(0).body().path("async").asBoolean()).isTrue();
        assertThat(items).allSatisfy(i -> {
            assertThat(i.path("timestamp").asText()).startsWith("2026-0").doesNotStartWith("2026-09-2");
            assertThat(i.path("document_id").asText()).isNotBlank();
            assertThat(i.path("context").asText()).isIn("mismatch detected", "accountant action", "vendor communication",
                    "mismatch outcome", "vishwas recommendation", "accountant decision");
            assertThat(i.path("tags").toString()).contains("vendor:").contains("dim:");
            boolean learningLoop = i.path("context").asText().equals("vishwas recommendation")
                    || i.path("context").asText().equals("accountant decision")
                    || i.path("document_id").asText().startsWith("summary-");
            assertThat(i.path("observation_scopes")).hasSize(learningLoop ? 1 : 3);
            assertThat(i.path("resolve_entities").asBoolean(true)).isFalse();
            assertThat(i.path("entities").get(1).path("type").asText()).isEqualTo("GSTIN");
        });
        assertThat(items).anyMatch(i -> "append".equals(i.path("update_mode").asText())
                && i.path("document_id").asText().equals("thread-" + KPL.toLowerCase()));
        assertThat(items).anyMatch(i -> i.path("content").asText().contains("Promise BROKEN by Kaveri Packaging"));
        assertThat(items).anyMatch(i -> i.path("tags").toString().contains("period:2026-05"));

        // letters through /files/retain with the vendor's tags
        String uploads = String.join(" ", STUB.calls("POST", "/files/retain").stream().map(StubApis.Call::rawText).toList());
        assertThat(uploads).contains("filename=\"kaveri-packaging-2026-07-06.pdf\"").contains("vendor:" + KPL)
                .contains("filename=\"metro-logistics-2026-06-19.pdf\"").contains("vendor:36AAPFM8841E1ZX")
                .contains("%PDF-1.4");
    }

    @Test
    @Order(2)
    void lookAlikeVendorsNeverShareHistory() {
        List<JsonNode> items = new ArrayList<>();
        STUB.calls("POST", "/memories").forEach(r -> r.body().path("items").forEach(items::add));
        for (JsonNode i : items) {
            String tags = i.path("tags").toString();
            String content = i.path("content").asText();
            if (tags.contains("vendor:" + SBT)) {
                assertThat(tags).doesNotContain(SBE);
                assertThat(content).doesNotContain("Sri Balaji Enterprises");
                assertThat(i.path("entities").get(0).path("text").asText()).isEqualTo("Sri Balaji Traders");
            }
            if (tags.contains("vendor:" + SBE)) {
                assertThat(tags).doesNotContain(SBT);
                assertThat(content).doesNotContain("Sri Balaji Traders");
                assertThat(i.path("entities").get(0).path("text").asText()).isEqualTo("Sri Balaji Enterprises");
            }
        }
        assertThat(vendorHistory.historyFor(SBT, Dimension.TIMING, null, "2026-08").judgedCount()).isEqualTo(4);
        assertThat(vendorHistory.historyFor(SBE, Dimension.TIMING, null, "2026-08").judgedCount()).isZero();
    }

    @Test
    @Order(3)
    void reconcilingAugustGivesGuardrailedMemoryAdviceNextToTheBaseline() throws Exception {
        var r = reconcile.reconcile(SeedCatalog.LIVE_PERIOD, new ReconcileService.FileInput("pr.csv", seed.books("2026-08")),
                new ReconcileService.FileInput("2b.json", seed.gstr2b("2026-08")));
        assertThat(r.adviceMode()).isEqualTo("MEMORY");
        assertThat(r.newMismatches()).isEqualTo(12);
        waitFor(() -> "DONE".equals(workspace.adviceStatus(r.adviceRunId()).status()));

        assertThat(category(SBT, "SBT/2026/0118")).isEqualTo(Category.RECOMMEND);
        assertThat(category(KPL, "KPL/0631")).isEqualTo(Category.REQUIRE_REVIEW);
        assertThat(category(GSI, "142")).isEqualTo(Category.AUTO_RESOLVE);               // approved FORMAT_ONLY rule + 4/4 typos
        assertThat(category(CFP, "CFP/3318")).isEqualTo(Category.ESCALATE);              // memory said RECOMMEND; guardrail wins
        assertThat(category(TMP, "TMP/5520")).isEqualTo(Category.ESCALATE);
        assertThat(category(SBE, "SBE-2331")).isEqualTo(Category.REQUIRE_REVIEW);        // Rs 55,800 with no TIMING history

        Recommendation sbe = latest(SBE, "SBE-2331");
        assertThat(sbe.getConfidenceLevel()).isEqualTo("NONE");
        assertThat(sbe.getMemoryFactsJson()).contains("fact-" + SBE).doesNotContain("fact-" + SBT);
        assertThat(latest(SBT, "SBT/2026/0118").getConfidenceLevel()).isEqualTo("HIGH");
        assertThat(latest(SBT, "SBT/2026/0118").getBaselineAction()).isEqualTo("Send the vendor a reminder.");

        // every reflect was scoped to exactly one vendor
        assertThat(STUB.calls("POST", "/reflect")).allSatisfy(c -> {
            assertThat(c.body().path("tags")).hasSize(1);
            assertThat(c.body().path("tags_match").asText()).isEqualTo("any_strict");
            assertThat(c.body().path("response_schema").path("properties").has("per_invoice")).isTrue();
        });

        var ws = workspace.workspace(SeedCatalog.LIVE_PERIOD);
        assertThat(ws.money().confirmedLossToDate()).isEqualByComparingTo("8640");
        assertThat(ws.money().likelyTiming()).isEqualByComparingTo("16740");
        assertThat(ws.money().needsReview()).isGreaterThan(new java.math.BigDecimal("42000"));
        assertThat(ws.vendors()).anySatisfy(g -> {
            assertThat(g.gstin()).isEqualTo(KPL);
            assertThat(g.rows()).filteredOn(WorkspaceService.CaseRow::carriedForward).extracting(WorkspaceService.CaseRow::invoice)
                    .containsExactlyInAnyOrder("KPL/0502", "KPL/0547", "KPL/0589");
        });
        assertThat(find(KPL, "KPL/0547").getStatus()).isEqualTo(MismatchStatus.AT_RISK);   // judged by the August GSTR-2B
    }

    @Test
    @Order(4)
    void septemberJudgesAugustAndVishwasLearns() {
        var before = learning.accuracy();
        var result = reconcile.nextMonth(SeedCatalog.NEXT_PERIOD, new ReconcileService.FileInput("2b.json", seed.gstr2b("2026-09")),
                new ReconcileService.FileInput("pr.csv", seed.books("2026-09")));

        assertThat(result.verdicts()).extracting(ReconcileService.VerdictView::invoice)
                .contains("SBT/2026/0118", "KPL/0547", "MLS/1131", "NEL/7851", "142", "CFP/3318");
        assertThat(find(SBT, "SBT/2026/0118").getVerdict()).isEqualTo(Outcome.RESOLVED_LATE);
        assertThat(latest(SBT, "SBT/2026/0118").getWasCorrect()).isTrue();
        assertThat(result.recommendationsJudged()).isGreaterThan(0);
        assertThat(result.vendors()).anySatisfy(v -> {
            assertThat(v.gstin()).isEqualTo(SBT);
            assertThat(v.changedDimensions()).contains("TIMING");
        });
        long judgedBefore = before.stream().mapToLong(LearningLoop.Accuracy::judged).sum();
        long judgedAfter = result.accuracyAfter().stream().mapToLong(LearningLoop.Accuracy::judged).sum();
        assertThat(judgedAfter).isGreaterThan(judgedBefore);
        List<JsonNode> last = new ArrayList<>();
        STUB.calls("POST", "/memories").get(STUB.calls("POST", "/memories").size() - 1).body().path("items").forEach(last::add);
        assertThat(last).anyMatch(i -> i.path("context").asText().equals("mismatch outcome")
                && i.path("content").asText().contains("SBT/2026/0118"));
    }

    @Test
    @Order(5)
    void whenMemoryFailsEveryCaseGetsTheTextbookActionAndTheRunStillCompletes() throws Exception {
        STUB.on("POST", "/reflect", c -> new StubApis.Reply(503, "{\"detail\":\"overloaded\"}"));
        STUB.on("POST", "/chat/completions", c -> new StubApis.Reply(503, "{\"error\":\"down\"}"));
        try {
            var run = reconcile.readvise(SeedCatalog.LIVE_PERIOD);
            waitFor(() -> "DONE".equals(workspace.adviceStatus(run.getId()).status()));
            assertThat(workspace.adviceStatus(run.getId()).message()).contains("memory unavailable");
            Recommendation r = latest(KPL, "KPL/0631");
            assertThat(r.getRunId()).isEqualTo(run.getId());
            assertThat(r.getSource()).isEqualTo(Recommendation.Source.TEXTBOOK);
            assertThat(r.getCategory()).isEqualTo(Category.REQUIRE_REVIEW);
            assertThat(r.getBaselineCategory()).isEqualTo("REQUIRE_REVIEW (textbook)");
            assertThat(r.getBaselineAction()).startsWith("Send the vendor a reminder to file GSTR-1");
        } finally {
            STUB.on("POST", "/reflect", SeedLoadIntegrationTest::reflect);
            STUB.on("POST", "/chat/completions", SeedLoadIntegrationTest::baseline);
        }
    }

    // ---------------------------------------------------------------- helpers

    Mismatch find(String gstin, String invoice) {
        return mismatches.findByVendorGstinOrderByDetectedAtAscIdAsc(gstin).stream()
                .filter(m -> invoice.equals(m.invoiceNo())).findFirst().orElseThrow();
    }

    Recommendation rec(String gstin, String invoice) {
        return recommendations.findByMismatchIdOrderByCreatedAtAsc(find(gstin, invoice).getId()).get(0);
    }

    Recommendation latest(String gstin, String invoice) {
        return recommendations.findFirstByMismatchIdOrderByCreatedAtDescIdDesc(find(gstin, invoice).getId()).orElseThrow();
    }

    Category category(String gstin, String invoice) {
        return latest(gstin, invoice).getCategory();
    }

    VendorCommunication promise(String gstin) {
        return communications.findByVendorGstinOrderByOccurredAtAsc(gstin).stream().filter(c -> c.getPromiseBy() != null)
                .reduce((a, b) -> b).orElseThrow();
    }

    static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting");
            }
            Thread.sleep(50);
        }
    }
}
