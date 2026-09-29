package com.vishwas.workflow;

import com.vishwas.advisor.Recommendation;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.IngestService;
import com.vishwas.ingest.InvoiceRow;
import com.vishwas.matching.Mismatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Month-end close: what still blocks closing the period and who owns each item. Items 1 to 5 are computed from
 * the data; the final review is a manual sign-off by the CFO, possible only when nothing else blocks.
 */
@Service
public class CloseChecklistService {

    public static final String FINAL_REVIEW = "FINAL_REVIEW";

    public record Blocker(Long caseId, String label) {
    }

    public record Item(String key, String title, String owner, boolean done, String detail, List<Blocker> blockers,
                       String signedBy, Instant signedAt) {
    }

    public record Checklist(String period, String periodLabel, boolean readyToClose, int blocking, List<Item> items) {
    }

    private final IngestService ingest;
    private final ReconcileService reconcile;
    private final RecommendationRepository recommendations;
    private final ActionCenterService actions;
    private final CaseFileRepository cases;
    private final CloseSignoffRepository signoffs;
    private final VishwasProperties props;
    private final Clock clock;

    public CloseChecklistService(IngestService ingest, ReconcileService reconcile, RecommendationRepository recommendations,
                                 ActionCenterService actions, CaseFileRepository cases, CloseSignoffRepository signoffs,
                                 VishwasProperties props, Clock clock) {
        this.ingest = ingest;
        this.reconcile = reconcile;
        this.recommendations = recommendations;
        this.actions = actions;
        this.cases = cases;
        this.signoffs = signoffs;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Checklist checklist(String period) {
        String accountant = props.company().accountant();
        String cfo = props.company().cfo();
        List<Mismatch> open = reconcile.casesInView(period).stream().filter(m -> m.getStatus().open()).toList();
        Map<Long, Recommendation> latest = new java.util.HashMap<>();
        for (Mismatch m : open) {
            recommendations.findFirstByMismatchIdOrderByCreatedAtDescIdDesc(m.getId()).ifPresent(r -> latest.put(m.getId(), r));
        }
        List<Item> items = new ArrayList<>();

        boolean books = ingest.imported(period, InvoiceRow.Source.BOOKS);
        boolean g2b = ingest.imported(period, InvoiceRow.Source.GSTR2B);
        List<Blocker> importBlockers = new ArrayList<>();
        if (!books) {
            importBlockers.add(new Blocker(null, "Purchase register for " + Fmt.month(period) + " not imported"));
        }
        if (!g2b) {
            importBlockers.add(new Blocker(null, "GSTR-2B for " + Fmt.month(period) + " not imported"));
        }
        items.add(item("IMPORTS", "Imports done (purchase register and GSTR-2B)", accountant, importBlockers,
                books && g2b ? "Both files imported." : null));

        List<Blocker> unreviewed = open.stream().filter(m -> latest.get(m.getId()) == null || latest.get(m.getId()).getDecision() == null)
                .map(CloseChecklistService::blocker).toList();
        items.add(item("REVIEWED", "Mismatches reviewed (every open case has a decision)", accountant, unreviewed,
                open.size() - unreviewed.size() + " of " + open.size() + " open cases reviewed."));

        BigDecimal materiality = props.advice().materialityInr();
        List<Mismatch> highValue = open.stream().filter(m -> m.getExposure().compareTo(materiality) >= 0).toList();
        List<Blocker> unapproved = highValue.stream().filter(m -> latest.get(m.getId()) == null
                || latest.get(m.getId()).getDecision() == null || latest.get(m.getId()).getDecision() == Recommendation.Decision.REJECTED)
                .map(CloseChecklistService::blocker).toList();
        items.add(item("HIGH_VALUE", "High-value cases approved (" + Fmt.inr(materiality) + " or more)", cfo, unapproved,
                highValue.isEmpty() ? "No case at or above " + Fmt.inr(materiality) + "." : highValue.size() - unapproved.size() + " of "
                        + highValue.size() + " approved."));

        List<Blocker> waiting = open.stream().filter(m -> actions.effectiveState(m) == CaseState.WAITING_FOR_VENDOR)
                .map(m -> new Blocker(m.getId(), m.getVendorName() + " · " + m.invoiceNo() + " · waiting for the vendor")).toList();
        items.add(item("VENDOR_RESPONSES", "Vendor responses received", accountant, waiting,
                waiting.isEmpty() ? "No case is waiting for a vendor reply." : null));

        List<Blocker> unassigned = open.stream().filter(m -> cases.findById(m.getId()).map(CaseFile::getOwner).orElse(null) == null)
                .map(CloseChecklistService::blocker).toList();
        items.add(item("ASSIGNED", "Open cases assigned to an owner", accountant, unassigned,
                open.size() - unassigned.size() + " of " + open.size() + " open cases have an owner."));

        long blockingBefore = items.stream().filter(i -> !i.done()).count();
        Optional<CloseSignoff> signed = signoffs.findByPeriodAndItem(period, FINAL_REVIEW);
        List<Blocker> finalBlockers = signed.isPresent() ? List.of()
                : List.of(new Blocker(null, blockingBefore == 0 ? "Awaiting the CFO's sign-off" : blockingBefore + " item(s) above must be done first"));
        items.add(new Item(FINAL_REVIEW, "Final review and sign-off", cfo, signed.isPresent(),
                signed.map(s -> "Signed off by " + s.getSignedBy() + (s.getNote() == null ? "" : ": " + s.getNote())).orElse(null),
                finalBlockers, signed.map(CloseSignoff::getSignedBy).orElse(null), signed.map(CloseSignoff::getSignedAt).orElse(null)));

        int blocking = (int) items.stream().filter(i -> !i.done()).count();
        return new Checklist(period, Fmt.month(period), blocking == 0, blocking, items);
    }

    /** The CFO signs the final review; refused while any other item still blocks the close. */
    @Transactional
    public Checklist signOff(String period, String by, String note) {
        if (by == null || by.isBlank()) {
            throw new IllegalArgumentException("Sign-off needs a name.");
        }
        Checklist current = checklist(period);
        long otherBlockers = current.items().stream().filter(i -> !i.key().equals(FINAL_REVIEW) && !i.done()).count();
        if (otherBlockers > 0) {
            throw new IllegalStateException(otherBlockers + " checklist item(s) still block the close of " + Fmt.month(period) + ".");
        }
        if (signoffs.findByPeriodAndItem(period, FINAL_REVIEW).isEmpty()) {
            signoffs.save(new CloseSignoff(period, FINAL_REVIEW, by.trim(), Instant.now(clock), note == null || note.isBlank() ? null : note.trim()));
        }
        return checklist(period);
    }

    private static Item item(String key, String title, String owner, List<Blocker> blockers, String detail) {
        return new Item(key, title, owner, blockers.isEmpty(), detail, blockers, null, null);
    }

    private static Blocker blocker(Mismatch m) {
        return new Blocker(m.getId(), m.getVendorName() + " · " + m.invoiceNo() + " · " + Fmt.inr(m.getExposure()));
    }
}
