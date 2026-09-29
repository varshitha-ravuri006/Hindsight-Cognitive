package com.vishwas.workflow;

import com.vishwas.advisor.Category;
import com.vishwas.advisor.Recommendation;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.demo.HistoryLoader;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.Outcome;
import com.vishwas.outcomes.AccountantAction;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The action center on the replayed seed history, memory off (the database is the source of truth). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:vishwas-action-it;DB_CLOSE_DELAY=-1")
class ActionCenterIntegrationTest {

    static final String KPL = "29AAHCK5512D1ZO";
    static final String GSI = "37AACCG3309R1Z8";

    @Autowired HistoryLoader history;
    @Autowired ActionCenterService actions;
    @Autowired DecisionService decisions;
    @Autowired MismatchRepository mismatches;
    @Autowired RecommendationRepository recommendations;
    @Autowired VendorCommunicationRepository communications;
    @Autowired LetterService letters;
    @Autowired DossierService dossiers;

    @BeforeEach
    void seed() {
        history.replayDatabaseNow();
    }

    Mismatch find(String gstin, String invoice) {
        return mismatches.findByVendorGstinOrderByDetectedAtAscIdAsc(gstin).stream().filter(m -> invoice.equals(m.invoiceNo()))
                .findFirst().orElseThrow();
    }

    @Test
    void casesMoveThroughTheWorkflowWithOwnersNotesAndHistory() {
        long id = find(KPL, "KPL/0589").getId();
        assertThat(actions.detail(id).state()).isEqualTo("DETECTED");

        actions.assign(id, "Lakshmi Prasad", LocalDate.now().minusDays(1), null);
        actions.transition(id, CaseState.INVESTIGATING, "Checking the contract", null);
        var d = actions.note(id, "Kaveri's accounts team changed in June.", null);

        assertThat(d.state()).isEqualTo("INVESTIGATING");
        assertThat(d.owner()).isEqualTo("Lakshmi Prasad");
        assertThat(d.notes()).extracting(CaseNote::getText).contains("Kaveri's accounts team changed in June.");
        assertThat(d.history()).extracting(AccountantAction::getAction).contains(AccountantAction.STATE_CHANGE, AccountantAction.NOTE);
        assertThat(actions.board().rows()).filteredOn(r -> r.id() == id).singleElement()
                .satisfies(r -> assertThat(r.overdue()).isTrue());
        assertThatThrownBy(() -> actions.transition(id, CaseState.VENDOR_RESPONDED, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendingAFollowUpWaitsForTheVendorAndSetsAReminderAndAReplyCarriesAPromise() {
        long id = find(KPL, "KPL/0547").getId();
        var afterSend = actions.vendorMessage(id, VendorCommunication.Direction.OUT, VendorCommunication.Channel.EMAIL,
                "Follow-up 4 on KPL/0547.", null, null);
        assertThat(afterSend.state()).isEqualTo("WAITING_FOR_VENDOR");
        assertThat(afterSend.reminders()).anySatisfy(r -> assertThat(r.getDueOn()).isEqualTo(LocalDate.now().plusDays(7)));

        var afterReply = actions.vendorMessage(id, VendorCommunication.Direction.IN, VendorCommunication.Channel.EMAIL,
                "Will file by 10 Oct.", LocalDate.of(2026, 10, 10), null);
        assertThat(afterReply.state()).isEqualTo("VENDOR_RESPONDED");
        assertThat(communications.findByVendorGstinOrderByOccurredAtAsc(KPL)).last().satisfies(c -> {
            assertThat(c.getPromiseStatus()).isEqualTo(VendorCommunication.PromiseStatus.PENDING);
            assertThat(c.getSource()).isEqualTo("APP");
        });
        actions.completeReminder(afterSend.reminders().get(0).getId());
        assertThat(actions.detail(id).reminders()).allSatisfy(r -> assertThat(r.isDone()).isTrue());
    }

    @Test
    void writingOffTurnsExposureIntoConfirmedLossWithTheApprover() {
        Mismatch m = find(KPL, "KPL/0502");
        BigDecimal exposure = m.getExposure();
        assertThatThrownBy(() -> actions.writeOff(m.getId(), " ", null, null)).hasMessageContaining("approver");
        var d = actions.writeOff(m.getId(), "Srinivas Reddy", "Not filed after four months", null);
        Mismatch after = mismatches.findById(m.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(MismatchStatus.WRITTEN_OFF);
        assertThat(after.getConfirmedLoss()).isEqualByComparingTo(exposure);
        assertThat(d.state()).isEqualTo("RESOLVED");
        assertThat(d.history()).anySatisfy(a -> assertThat(a.getApprovedBy()).isEqualTo("Srinivas Reddy"));
    }

    @Test
    void approvingAnAutoResolveAppliesTheRuleAuditedAndReversibly() {
        Mismatch m = find(GSI, "102");
        Recommendation rec = recommendations.findByMismatchIdOrderByCreatedAtAsc(m.getId()).get(0);
        assertThat(rec.getCategory()).isEqualTo(Category.AUTO_RESOLVE);
        actions.applyAutoResolve(rec, "Lakshmi Prasad");
        Mismatch resolved = mismatches.findById(m.getId()).orElseThrow();
        assertThat(resolved.getVerdict()).isEqualTo(Outcome.CONFIRMED_TYPO);
        assertThat(resolved.getVerdictNote()).contains("FORMAT_ONLY");

        var d = actions.reverseAutoResolve(m.getId(), "Wanted to confirm with Godavari first", null);
        assertThat(mismatches.findById(m.getId()).orElseThrow().getStatus()).isEqualTo(MismatchStatus.OPEN);
        assertThat(d.history()).extracting(AccountantAction::getAction)
                .contains(AccountantAction.AUTO_RESOLVE_APPLIED, AccountantAction.AUTO_RESOLVE_REVERSED);
        assertThatThrownBy(() -> actions.reverseAutoResolve(find(KPL, "KPL/0589").getId(), null, null))
                .hasMessageContaining("not auto-resolved");
    }

    @Test
    void anUploadedLetterIsStoredRecordedAndServed() {
        byte[] pdf = "%PDF-1.4\n% test letter\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        var up = letters.upload(KPL, "kaveri.pdf", pdf, LocalDate.of(2026, 9, 25), "Kaveri promises to file KPL/0631 by 10 Oct.",
                LocalDate.of(2026, 10, 10), List.of("KPL/0631"), "R. Manjunath");
        assertThat(up.sentToMemory()).isFalse();   // memory is off in this test
        assertThat(letters.read(up.attachment())).get().isEqualTo(pdf);
        assertThat(communications.findById(up.communicationId()).orElseThrow().getChannel()).isEqualTo(VendorCommunication.Channel.LETTER);
        assertThatThrownBy(() -> letters.upload(KPL, "x.txt", "hello".getBytes(), null, "s", null, List.of(), null))
                .hasMessageContaining("PDF");
    }

    @Test
    void theDossierCarriesTheLedgerEvenWhenMemoryIsOff() {
        String md = dossiers.dossier(KPL);
        assertThat(md).startsWith("# Vendor dossier: Kaveri Packaging Pvt Ltd")
                .contains("Informational only, not tax advice")
                .contains("## What memory concluded")
                .contains("| July 2026 | KPL/0589 | Rs 15,900 |")
                .contains("ITC reversed (confirmed loss Rs 8,640)")
                .contains("_Promise by 20 Jul 2026: broken._");
    }
}
