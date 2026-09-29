package com.vishwas.advisor;

import com.vishwas.matching.Dimension;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The database's view of a vendor's past: every earlier case on a dimension with month, amount and outcome
 * (the evidence the UI shows), and responsiveness computed from the communication log.
 */
@Service
@Transactional(readOnly = true)
public class VendorHistoryService {

    public record Responsiveness(int followUps, int replies, int unanswered, int promisesMade, int promisesKept,
                                 int promisesBroken, Double averageReplyDays) {
    }

    private final MismatchRepository mismatches;
    private final VendorCommunicationRepository communications;

    public VendorHistoryService(MismatchRepository mismatches, VendorCommunicationRepository communications) {
        this.mismatches = mismatches;
        this.communications = communications;
    }

    /**
     * Past cases of this vendor on this dimension, as history for {@code current}: other cases that already have
     * an outcome, or that come from an earlier period. Open cases of the same period are not history yet.
     */
    public HistoryStats historyFor(Mismatch current) {
        return historyFor(current.getVendorGstin(), current.getDimension(), current.getId(), current.getPeriod());
    }

    public HistoryStats historyFor(String gstin, Dimension dim, Long excludeId, String period) {
        List<HistoryStats.PastCase> cases = mismatches.findByVendorGstinAndDimensionOrderByDetectedAtAsc(gstin, dim).stream()
                .filter(m -> excludeId == null || !m.getId().equals(excludeId))
                .filter(m -> m.getVerdict() != null || m.getStatus() == MismatchStatus.WRITTEN_OFF
                        || (period != null && m.getPeriod().compareTo(period) < 0))
                .map(VendorHistoryService::past)
                .toList();
        return new HistoryStats(cases);
    }

    /** Everything on record for the vendor and dimension (for profile cards). */
    public HistoryStats all(String gstin, Dimension dim) {
        return new HistoryStats(mismatches.findByVendorGstinAndDimensionOrderByDetectedAtAsc(gstin, dim).stream()
                .map(VendorHistoryService::past).toList());
    }

    public static HistoryStats.PastCase past(Mismatch m) {
        Long days = m.getVerdictAt() == null || m.getDetectedAt() == null ? null
                : Duration.between(m.getDetectedAt(), m.getVerdictAt()).toDays();
        return new HistoryStats.PastCase(m.getId(), m.getPeriod(), m.invoiceNo(), m.getType().name(), m.getExposure(),
                m.getStatus(), m.getVerdict(), m.getMonthsLate(), m.getStatus() == MismatchStatus.RESOLVED ? days : null,
                m.getRecoveredAmount(), m.getConfirmedLoss());
    }

    public Responsiveness responsiveness(String gstin) {
        List<VendorCommunication> thread = communications.findByVendorGstinOrderByOccurredAtAsc(gstin);
        int followUps = 0;
        int replies = 0;
        int unanswered = 0;
        double replyDaysTotal = 0;
        int replyCount = 0;
        for (int i = 0; i < thread.size(); i++) {
            VendorCommunication c = thread.get(i);
            if (c.getDirection() == VendorCommunication.Direction.OUT) {
                followUps++;
                VendorCommunication reply = null;
                for (int j = i + 1; j < thread.size(); j++) {
                    VendorCommunication n = thread.get(j);
                    if (n.getDirection() == VendorCommunication.Direction.IN) {
                        reply = n;
                        break;
                    }
                    if (n.getDirection() == VendorCommunication.Direction.OUT) {
                        break;
                    }
                }
                if (reply == null || ChronoUnit.DAYS.between(c.getOccurredAt(), reply.getOccurredAt()) > 7) {
                    unanswered++;
                } else {
                    replyDaysTotal += ChronoUnit.HOURS.between(c.getOccurredAt(), reply.getOccurredAt()) / 24.0;
                    replyCount++;
                }
            } else {
                replies++;
            }
        }
        int made = (int) thread.stream().filter(c -> c.getPromiseBy() != null).count();
        int kept = (int) thread.stream().filter(c -> c.getPromiseStatus() == VendorCommunication.PromiseStatus.KEPT).count();
        int broken = (int) thread.stream().filter(c -> c.getPromiseStatus() == VendorCommunication.PromiseStatus.BROKEN).count();
        return new Responsiveness(followUps, replies, unanswered, made, kept, broken,
                replyCount == 0 ? null : Math.round(replyDaysTotal / replyCount * 10) / 10.0);
    }
}
