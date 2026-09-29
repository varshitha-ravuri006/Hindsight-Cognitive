package com.vishwas.workflow;

import com.vishwas.advisor.HistoryStats;
import com.vishwas.advisor.VendorProfileService;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Outcome;
import com.vishwas.memory.KnowledgePages;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.KnowledgePage;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The vendor dossier: Hindsight's knowledge page for the vendor (what memory concluded) followed by the ledger
 * from the database (every case with month, amount and outcome, the communication log and Vishwas's track record),
 * as one markdown file to share with the CFO or an auditor.
 */
@Service
public class DossierService {

    public record PageView(boolean available, String name, String markdown, String body, String refreshedAt, String message) {
    }

    private final VendorRepository vendors;
    private final VendorProfileService profiles;
    private final VendorCommunicationRepository communications;
    private final KnowledgePages pages;
    private final Clock clock;

    public DossierService(VendorRepository vendors, VendorProfileService profiles, VendorCommunicationRepository communications,
                          KnowledgePages pages, Clock clock) {
        this.vendors = vendors;
        this.profiles = profiles;
        this.communications = communications;
        this.pages = pages;
        this.clock = clock;
    }

    public PageView page(String gstin) {
        Vendor v = vendor(gstin);
        try {
            Optional<KnowledgePage> page = pages.vendorPage(v);
            if (page.isEmpty()) {
                return new PageView(false, "Vendors/" + v.getLegalName(), null, null, null,
                        "The knowledge page is created after the history is remembered.");
            }
            KnowledgePage p = page.get();
            boolean empty = p.body() == null || p.body().isBlank();
            return new PageView(!empty, "Vendors/" + p.name(), p.markdown(), p.body(), p.timestamp(),
                    empty ? "Hindsight is still writing this page." : null);
        } catch (HindsightException e) {
            return new PageView(false, "Vendors/" + v.getLegalName(), null, null, null, "Memory did not answer: " + e.getMessage());
        }
    }

    public Optional<String> refresh(String gstin) {
        return pages.refresh(vendor(gstin));
    }

    public String dossier(String gstin) {
        Vendor v = vendor(gstin);
        VendorProfileService.Profile p = profiles.profile(gstin);
        StringBuilder md = new StringBuilder();
        md.append("# Vendor dossier: ").append(v.getLegalName()).append("\n\n")
                .append("GSTIN ").append(v.getGstin()).append(" · ").append(nz(v.getCity())).append(" · ").append(nz(v.getSupplies()))
                .append("\n\nPrepared by Vishwas on ").append(Fmt.day(LocalDate.now(clock)))
                .append(". *Informational only, not tax advice. Past reliability never proves a future invoice is correct.*\n\n")
                .append("| Open potential exposure | Resolved or recovered | Confirmed loss |\n|---|---|---|\n| ")
                .append(Fmt.inr(p.openExposure())).append(" | ").append(Fmt.inr(p.recovered())).append(" | ")
                .append(Fmt.inr(p.confirmedLoss())).append(" |\n\n");

        md.append("## What memory concluded\n\n");
        PageView page = page(gstin);
        md.append(page.available() ? page.body().trim() : "_" + page.message() + "_").append("\n\n");

        md.append("## The ledger, dimension by dimension\n\n");
        for (VendorProfileService.DimensionCard d : p.dimensions()) {
            md.append("### ").append(d.label()).append("\n\n").append(d.headline()).append(" · history: ")
                    .append(d.reliabilityText()).append("\n\n");
            if (!d.pastCases().isEmpty()) {
                md.append("| Month | Invoice | ITC | Outcome |\n|---|---|---|---|\n");
                for (HistoryStats.PastCase c : d.pastCases()) {
                    md.append("| ").append(Fmt.month(c.period())).append(" | ").append(c.invoiceNo()).append(" | ")
                            .append(Fmt.inr(c.exposure())).append(" | ").append(outcome(c)).append(" |\n");
                }
                md.append('\n');
            }
        }

        md.append("## Communication log\n\n");
        var thread = communications.findByVendorGstinOrderByOccurredAtAsc(gstin);
        if (thread.isEmpty()) {
            md.append("_No follow-ups on record._\n\n");
        }
        for (VendorCommunication c : thread) {
            md.append("- **").append(Fmt.day(c.getOccurredAt())).append("** ")
                    .append(c.getDirection() == VendorCommunication.Direction.OUT ? "sent" : "received").append(" (")
                    .append(c.getChannel().name().toLowerCase()).append("): ").append(c.getSummary());
            if (c.getPromiseBy() != null) {
                md.append(" _Promise by ").append(Fmt.day(c.getPromiseBy())).append(": ")
                        .append(c.getPromiseStatus() == null ? "pending" : c.getPromiseStatus().name().toLowerCase()).append("._");
            }
            md.append('\n');
        }
        var t = p.trackRecord();
        md.append("\n## Vishwas's track record with this vendor\n\n").append(t.recommendations()).append(" recommendations, ")
                .append(t.judged()).append(" judged by later data, ").append(t.correct()).append(" right. The accountant accepted ")
                .append(t.accepted()).append(", modified ").append(t.modified()).append(" and rejected ").append(t.rejected()).append(".\n");
        return md.toString();
    }

    private static String outcome(HistoryStats.PastCase c) {
        if (c.status() == com.vishwas.matching.MismatchStatus.WRITTEN_OFF) {
            return "ITC reversed (confirmed loss " + Fmt.inr(c.confirmedLoss()) + ")";
        }
        if (c.outcome() == null) {
            return "still open";
        }
        return c.outcome() == Outcome.RESOLVED_LATE ? "appeared " + c.monthsLate() + " month(s) late" : c.outcome().label();
    }

    private Vendor vendor(String gstin) {
        return vendors.findById(gstin).orElseThrow(() -> new NoSuchElementException("Unknown vendor " + gstin));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
