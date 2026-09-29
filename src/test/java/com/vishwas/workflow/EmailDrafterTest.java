package com.vishwas.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.ingest.Vendor;
import com.vishwas.llm.GroqClient;
import com.vishwas.matching.Finding;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchType;
import com.vishwas.support.Rows;
import com.vishwas.support.StubApis;
import com.vishwas.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDrafterTest {

    private StubApis stub;
    private EmailDrafter drafter;
    private final Vendor kaveri = new Vendor("29AAHCK5512D1ZO", "Kaveri Packaging Pvt Ltd", "Bengaluru", "29",
            "finance@kaveripackaging.example", "R. Manjunath", null, "Cartons");

    static Mismatch missing(String invoice) {
        var row = Rows.booksInter(1, "29AAHCK5512D1ZO", invoice, "2026-08-18", "60000", "10800");
        return Mismatch.detected("2026-08", new Finding(MismatchType.MISSING_IN_2B, row, null, List.of(), List.of(),
                new BigDecimal("10800"), null, null), "Kaveri Packaging Pvt Ltd", Instant.now(), "[]", "[]");
    }

    @BeforeEach
    void start() throws Exception {
        stub = new StubApis();
        var props = TestProps.withApis(stub.baseUrl());
        drafter = new EmailDrafter(new GroqClient(TestProps.http(), TestProps.apiJson(), props), props);
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void usesTheModelDraftWhenItNamesEveryInvoice() {
        stub.on("POST", "/chat/completions", c -> StubApis.Reply.ok(StubApis.groqText(
                "{\"subject\":\"Pending GSTR-1 filing\",\"body\":\"Dear Mr Manjunath, invoice KPL/0631 is not in our GSTR-2B.\"}")));
        var d = drafter.draft(kaveri, List.of(missing("KPL/0631")));
        assertThat(d.source()).isEqualTo("GROQ");
        assertThat(d.to()).isEqualTo("finance@kaveripackaging.example");
    }

    @Test
    void rejectsDraftsThatThreatenPaymentAndFallsBackToTheTemplate() {
        stub.on("POST", "/chat/completions", c -> StubApis.Reply.ok(StubApis.groqText(
                "{\"subject\":\"Final notice\",\"body\":\"Invoice KPL/0631 is missing; we will withhold payment until you file.\"}")));
        var d = drafter.draft(kaveri, List.of(missing("KPL/0631")));
        assertThat(d.source()).isEqualTo("TEMPLATE");
        assertThat(d.body()).contains("KPL/0631").contains("R. Manjunath").doesNotContainIgnoringCase("payment");
        assertThat(stub.calls("POST", "/chat/completions")).hasSize(2);
    }

    @Test
    void validationRequiresEveryInvoice() {
        var json = new ObjectMapper().createObjectNode().put("subject", "s").put("body", "about KPL/0631 only");
        assertThat(EmailDrafter.validate(json, List.of(missing("KPL/0631"), missing("KPL/0589")))).get().asString().contains("KPL/0589");
    }
}
