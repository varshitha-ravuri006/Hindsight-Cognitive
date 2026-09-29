package com.vishwas.assistant;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    static final List<String> VENDORS = List.of("Sri Balaji Traders", "Sri Balaji Enterprises", "Kaveri Packaging Pvt Ltd",
            "Godavari Steel Industries Ltd", "Metro Logistics", "Nandi Electricals Pvt Ltd");

    @Test
    void repeatedInvoiceNumberMismatchesThisQuarter() {
        var plan = IntentRouter.route("Which vendors had repeated invoice-number mismatches this quarter?", TODAY, VENDORS);
        assertThat(plan).singleElement().satisfies(p -> {
            assertThat(p.tool()).isEqualTo("repeated_patterns");
            assertThat(p.args()).containsEntry("type", "INVOICE_NO_FORMAT").containsEntry("period_from", "2026-07")
                    .containsEntry("period_to", "2026-09").containsEntry("min_cases", 2);
        });
    }

    @Test
    void unresolvedDiscrepanciesAboveAnAmount() {
        var plan = IntentRouter.route("Show unresolved discrepancies above ₹25,000", TODAY, VENDORS);
        assertThat(plan).singleElement().satisfies(p -> {
            assertThat(p.tool()).isEqualTo("search_mismatches");
            assertThat(p.args()).containsEntry("status", "UNRESOLVED").containsEntry("min_exposure_inr", 25000.0);
        });
        assertThat(IntentRouter.route("open cases over 1.5 lakh", TODAY, VENDORS).get(0).args())
                .containsEntry("min_exposure_inr", 150000.0);
    }

    @Test
    void whatHappenedToAVendorsMismatchLastMonthUsesTheLedgerAndMemory() {
        var plan = IntentRouter.route("What happened to the Kaveri mismatch last month?", TODAY, VENDORS);
        assertThat(plan).extracting(IntentRouter.Planned::tool).containsExactly("case_history", "recall_memory");
        assertThat(plan.get(0).args()).containsEntry("vendor", "Kaveri Packaging Pvt Ltd").containsEntry("period", "2026-08");
        assertThat(plan.get(1).args()).containsEntry("from", "2026-08-01").containsEntry("to", "2026-08-31");
    }

    @Test
    void draftsForVendorsWithMissingDocuments() {
        var plan = IntentRouter.route("Draft follow-up emails for vendors with missing documents", TODAY, VENDORS);
        assertThat(plan).singleElement().satisfies(p -> {
            assertThat(p.tool()).isEqualTo("draft_followup_emails");
            assertThat(p.args()).containsEntry("missing_documents_only", true);
        });
    }

    @Test
    void lookAlikeVendorsNeedTheDistinguishingWord() {
        assertThat(IntentRouter.vendor("what happened with balaji?", VENDORS)).isNull();
        assertThat(IntentRouter.vendor("what happened with balaji traders?", VENDORS)).isEqualTo("Sri Balaji Traders");
        assertThat(IntentRouter.vendor("status of balaji enterprises", VENDORS)).isEqualTo("Sri Balaji Enterprises");
    }

    @Test
    void anythingElseAsksMemory() {
        assertThat(IntentRouter.route("Which vendor keeps its promises?", TODAY, VENDORS))
                .extracting(IntentRouter.Planned::tool).containsExactly("recall_memory");
    }
}
