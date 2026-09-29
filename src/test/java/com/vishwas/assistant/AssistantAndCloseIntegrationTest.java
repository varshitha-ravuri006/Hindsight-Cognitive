package com.vishwas.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.advisor.Recommendation;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.demo.HistoryLoader;
import com.vishwas.demo.SeedCatalog;
import com.vishwas.matching.Mismatch;
import com.vishwas.support.StubApis;
import com.vishwas.workflow.ActionCenterService;
import com.vishwas.workflow.CloseChecklistService;
import com.vishwas.workflow.DecisionService;
import com.vishwas.workflow.ReconcileService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The assistant's tool routing and grounding, and the month-end close, on the replayed seed plus August. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:vishwas-assistant-it;DB_CLOSE_DELAY=-1")
@Import(AssistantAndCloseIntegrationTest.FixedClock.class)
class AssistantAndCloseIntegrationTest {

    static final StubApis STUB = start();
    static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-29T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
        }
    }

    static StubApis start() {
        try {
            return new StubApis();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void groqOnly(DynamicPropertyRegistry r) {
        r.add("vishwas.groq.base-url", STUB::baseUrl);
        r.add("vishwas.groq.api-key", () -> "test-key");
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    @Autowired AssistantService assistant;
    @Autowired AssistantTools tools;
    @Autowired HistoryLoader history;
    @Autowired ReconcileService reconcile;
    @Autowired SeedCatalog seed;
    @Autowired CloseChecklistService close;
    @Autowired RecommendationRepository recommendations;
    @Autowired DecisionService decisions;
    @Autowired ActionCenterService actions;

    static boolean augustDone;

    @BeforeEach
    void data() {
        history.replayDatabaseNow();
        if (!augustDone) {
            STUB.on("POST", "/chat/completions", c -> new StubApis.Reply(503, "{}"));   // baseline off for the reconcile
            var r = reconcile.reconcile("2026-08", new ReconcileService.FileInput("pr.csv", seed.books("2026-08")),
                    new ReconcileService.FileInput("2b.json", seed.gstr2b("2026-08")));
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline && recommendations.findByRunId(r.adviceRunId()).size() < 15) {
                sleep(100);
            }
            sleep(500);
            augustDone = true;
        }
        STUB.reset();
    }

    @Test
    void theModelMustCallToolsAndTheirResultsAreSentBackAndCited() {
        AtomicInteger n = new AtomicInteger();
        STUB.on("POST", "/chat/completions", c -> StubApis.Reply.ok(n.incrementAndGet() == 1
                ? StubApis.groqToolCall("search_mismatches", "{\"status\":\"UNRESOLVED\",\"min_exposure_inr\":25000}")
                : StubApis.groqText("Two unresolved cases above Rs 25,000: SBE-2331 (Rs 55,800) and DCW/2026/1187 (Rs 38,700).")));

        var a = assistant.ask("Show unresolved discrepancies above ₹25,000");

        assertThat(a.mode()).isEqualTo("MODEL_WITH_TOOLS");
        assertThat(a.toolCalls()).singleElement().satisfies(t -> assertThat(t.tool()).isEqualTo("search_mismatches"));
        assertThat(a.records()).extracting(AssistantTools.Ref::label).anyMatch(l -> l.contains("SBE-2331")).anyMatch(l -> l.contains("DCW/2026/1187"));
        var second = STUB.calls("POST", "/chat/completions").get(1).body().path("messages");
        assertThat(second.get(second.size() - 1).path("role").asText()).isEqualTo("tool");
        assertThat(second.get(second.size() - 1).path("content").asText()).contains("SBE-2331");
        assertThat(STUB.calls("POST", "/chat/completions").get(0).body().path("messages")).hasSize(2);   // stateless: system + question only
    }

    @Test
    void anAnswerWithoutToolsIsRefusedAndTheRouterAnswersFromRecords() {
        STUB.on("POST", "/chat/completions", c -> StubApis.Reply.ok(StubApis.groqText("I think Kaveri is fine.")));
        var a = assistant.ask("What happened to the Kaveri mismatch last month?");
        assertThat(a.mode()).isEqualTo("ROUTER");
        assertThat(a.toolCalls()).extracting(AssistantService.ToolTrace::tool).containsExactly("case_history", "recall_memory");
        assertThat(a.answer()).contains("Kaveri Packaging Pvt Ltd").contains("KPL/0631").contains("unresolved at risk")
                .doesNotContain("I think");
        assertThat(STUB.calls("POST", "/chat/completions")).hasSize(2);   // one nudge, then the router
    }

    @Test
    void groqDownStillAnswersFromTheDatabase() {
        STUB.on("POST", "/chat/completions", c -> new StubApis.Reply(503, "{}"));
        var a = assistant.ask("Which vendors had repeated invoice-number mismatches this quarter?");
        assertThat(a.mode()).isEqualTo("ROUTER");
        assertThat(a.answer()).contains("Godavari Steel Industries Ltd").contains("2 cases");
        assertThat(a.records()).anyMatch(r -> r.kind().equals("vendor") && r.label().startsWith("Godavari"));
    }

    @Test
    void unknownToolsAreReportedBackToTheModel() {
        AtomicInteger n = new AtomicInteger();
        STUB.on("POST", "/chat/completions", c -> StubApis.Reply.ok(n.incrementAndGet() == 1
                ? StubApis.groqToolCall("delete_everything", "{}") : StubApis.groqText("I could not find that.")));
        var a = assistant.ask("Delete all cases");
        assertThat(a.toolCalls()).singleElement().satisfies(t -> assertThat(t.error()).contains("Unknown tool"));
    }

    @Test
    void draftsGoOnlyToVendorsWithMissingDocuments() {
        var r = tools.run("draft_followup_emails", JSON.valueToTree(Map.of("missing_documents_only", true)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> drafts = (List<Map<String, Object>>) ((Map<String, Object>) r.data()).get("drafts");
        assertThat(drafts).extracting(d -> d.get("vendor")).contains("Kaveri Packaging Pvt Ltd", "Sri Balaji Traders")
                .doesNotContain("Metro Logistics", "Nandi Electricals Pvt Ltd");
        assertThat(drafts).allSatisfy(d -> assertThat(String.valueOf(d.get("body"))).doesNotContainIgnoringCase("withhold"));
    }

    @Test
    void closeIsBlockedUntilCasesAreReviewedAssignedAndApproved() {
        var c = close.checklist("2026-08");
        assertThat(c.readyToClose()).isFalse();
        assertThat(c.items()).filteredOn(i -> i.key().equals("IMPORTS")).singleElement().satisfies(i -> assertThat(i.done()).isTrue());
        assertThat(c.items()).filteredOn(i -> i.key().equals("REVIEWED")).singleElement()
                .satisfies(i -> assertThat(i.blockers()).isNotEmpty());
        assertThat(c.items()).filteredOn(i -> i.key().equals("HIGH_VALUE")).singleElement()
                .satisfies(i -> assertThat(i.owner()).isEqualTo("Srinivas Reddy"));
        assertThatThrownBy(() -> close.signOff("2026-08", "Srinivas Reddy", null)).hasMessageContaining("still block");

        for (Mismatch m : reconcile.casesInView("2026-08").stream().filter(x -> x.getStatus().open()).toList()) {
            Recommendation r = recommendations.findFirstByMismatchIdOrderByCreatedAtDescIdDesc(m.getId()).orElseThrow();
            if (r.getDecision() == null) {
                decisions.decide(r.getId(), Recommendation.Decision.ACCEPTED, Recommendation.Reason.OTHER, null, null, null);
            }
            if (m.getStatus().open()) {
                actions.assign(m.getId(), "Lakshmi Prasad", LocalDate.of(2026, 10, 5), null);
            }
        }
        var ready = close.checklist("2026-08");
        assertThat(ready.items()).filteredOn(i -> !i.key().equals(CloseChecklistService.FINAL_REVIEW))
                .allSatisfy(i -> assertThat(i.done()).as(i.key() + " " + i.blockers()).isTrue());
        var signed = close.signOff("2026-08", "Srinivas Reddy", "Reviewed with Lakshmi");
        assertThat(signed.readyToClose()).isTrue();
        assertThat(signed.items()).last().satisfies(i -> assertThat(i.signedBy()).isEqualTo("Srinivas Reddy"));
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
