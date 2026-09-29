package com.vishwas.workflow;

import com.vishwas.advisor.Recommendation;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.MemoryWriter;
import com.vishwas.outcomes.AccountantAction;
import com.vishwas.outcomes.AccountantActionRepository;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The action center: every open case with its working state, owner, due date, notes and reminders; vendor
 * e-mail drafts; recording what was sent and what came back; and the ITC write-off (the only way an exposure
 * becomes a confirmed loss). Meaningful actions are remembered so the vendor's history includes how it was chased.
 */
@Service
public class ActionCenterService {

    public record Row(long id, String period, String vendor, String gstin, String invoice, String type, BigDecimal exposure,
                      String category, String state, String stateLabel, String owner, LocalDate dueDate, boolean overdue,
                      int openReminders, LocalDate nextReminder) {
    }

    public record Board(List<Row> rows, Map<CaseState, Long> counts, long overdue, long remindersDue, LocalDate today) {
    }

    public record CaseDetail(long id, String state, String stateLabel, List<String> allowedNext, String owner, LocalDate dueDate,
                             List<CaseNote> notes, List<Reminder> reminders, List<AccountantAction> history) {
    }

    private final CaseFileRepository cases;
    private final CaseNoteRepository notes;
    private final ReminderRepository reminders;
    private final MismatchRepository mismatches;
    private final AccountantActionRepository actions;
    private final VendorCommunicationRepository communications;
    private final VendorRepository vendors;
    private final RecommendationRepository recommendations;
    private final MemoryWriter writer;
    private final MemoryPublisher publisher;
    private final EmailDrafter drafter;
    private final Clock clock;
    private final String accountant;

    public ActionCenterService(CaseFileRepository cases, CaseNoteRepository notes, ReminderRepository reminders,
                               MismatchRepository mismatches, AccountantActionRepository actions,
                               VendorCommunicationRepository communications, VendorRepository vendors,
                               RecommendationRepository recommendations, MemoryWriter writer, MemoryPublisher publisher,
                               EmailDrafter drafter, Clock clock, VishwasProperties props) {
        this.cases = cases;
        this.notes = notes;
        this.reminders = reminders;
        this.mismatches = mismatches;
        this.actions = actions;
        this.communications = communications;
        this.vendors = vendors;
        this.recommendations = recommendations;
        this.writer = writer;
        this.publisher = publisher;
        this.drafter = drafter;
        this.clock = clock;
        this.accountant = props.company().accountant();
    }

    /** The data decides "resolved"; otherwise the accountant's working state (Detected until touched). */
    public CaseState effectiveState(Mismatch m) {
        if (m.getStatus() == MismatchStatus.RESOLVED || m.getStatus() == MismatchStatus.WRITTEN_OFF) {
            return CaseState.RESOLVED;
        }
        return cases.findById(m.getId()).map(CaseFile::getState).orElse(CaseState.DETECTED);
    }

    @Transactional(readOnly = true)
    public Board board() {
        LocalDate today = LocalDate.now(clock);
        List<Reminder> open = reminders.findByDoneFalseOrderByDueOnAsc();
        List<Row> rows = new ArrayList<>();
        for (Mismatch m : mismatches.findByStatusInOrderByPeriodAscIdAsc(List.of(MismatchStatus.OPEN, MismatchStatus.AT_RISK))) {
            CaseFile f = cases.findById(m.getId()).orElse(null);
            CaseState state = effectiveState(m);
            List<Reminder> mine = open.stream().filter(r -> r.getMismatchId().equals(m.getId())).toList();
            String category = recommendations.findFirstByMismatchIdOrderByCreatedAtDescIdDesc(m.getId())
                    .map(r -> r.getCategory().name()).orElse(null);
            LocalDate due = f == null ? null : f.getDueDate();
            rows.add(new Row(m.getId(), m.getPeriod(), m.getVendorName(), m.getVendorGstin(), m.invoiceNo(), m.getType().label(),
                    m.getExposure(), category, state.name(), state.label(), f == null ? null : f.getOwner(), due,
                    due != null && due.isBefore(today) && !state.closed(), mine.size(),
                    mine.isEmpty() ? null : mine.get(0).getDueOn()));
        }
        rows.sort(Comparator.comparing(Row::overdue).reversed().thenComparing(Row::exposure, Comparator.reverseOrder()));
        Map<CaseState, Long> counts = new EnumMap<>(CaseState.class);
        for (CaseState s : CaseState.values()) {
            counts.put(s, rows.stream().filter(r -> r.state().equals(s.name())).count());
        }
        long overdue = rows.stream().filter(Row::overdue).count();
        long remindersDue = open.stream().filter(r -> !r.getDueOn().isAfter(today)).count();
        return new Board(rows, counts, overdue, remindersDue, today);
    }

    @Transactional(readOnly = true)
    public CaseDetail detail(long id) {
        Mismatch m = mismatch(id);
        CaseFile f = cases.findById(id).orElse(null);
        CaseState state = effectiveState(m);
        return new CaseDetail(id, state.name(), state.label(), state.next().stream().map(Enum::name).toList(),
                f == null ? null : f.getOwner(), f == null ? null : f.getDueDate(), notes.findByMismatchIdOrderByCreatedAtAsc(id),
                reminders.findByMismatchIdOrderByDueOnAsc(id),
                actions.findByMismatchIdOrderByOccurredAtAsc(id));
    }

    @Transactional
    public CaseDetail transition(long id, CaseState to, String note, String by) {
        Mismatch m = mismatch(id);
        String who = who(by);
        Instant now = Instant.now(clock);
        CaseFile f = file(m, now, who);
        CaseState from = effectiveState(m);
        if (from == CaseState.RESOLVED && m.getStatus() != MismatchStatus.OPEN && m.getStatus() != MismatchStatus.AT_RISK) {
            throw new IllegalStateException("This case was resolved by the data; it cannot be moved.");
        }
        f.moveTo(to, now, who);
        cases.save(f);
        String text = "Case moved from " + from.label() + " to " + to.label() + (note == null || note.isBlank() ? "." : ". " + note.trim());
        remember(actions.save(new AccountantAction(m.getVendorGstin(), id, now, who, AccountantAction.STATE_CHANGE, text, null)), m);
        return detail(id);
    }

    @Transactional
    public CaseDetail assign(long id, String owner, LocalDate due, String by) {
        Mismatch m = mismatch(id);
        Instant now = Instant.now(clock);
        CaseFile f = file(m, now, who(by));
        f.assign(owner, due, now, who(by));
        cases.save(f);
        return detail(id);
    }

    @Transactional
    public CaseDetail note(long id, String text, String by) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A note needs text.");
        }
        Mismatch m = mismatch(id);
        Instant now = Instant.now(clock);
        notes.save(new CaseNote(id, who(by), now, text.trim()));
        remember(actions.save(new AccountantAction(m.getVendorGstin(), id, now, who(by), AccountantAction.NOTE, text.trim(), null)), m);
        return detail(id);
    }

    @Transactional
    public CaseDetail remind(long id, LocalDate dueOn, String text, String by) {
        mismatch(id);
        if (dueOn == null) {
            throw new IllegalArgumentException("A reminder needs a date.");
        }
        reminders.save(new Reminder(id, dueOn, text == null || text.isBlank() ? "Follow up with the vendor" : text.trim(),
                Instant.now(clock), who(by)));
        return detail(id);
    }

    @Transactional
    public void completeReminder(long reminderId) {
        Reminder r = reminders.findById(reminderId).orElseThrow(() -> new NoSuchElementException("No reminder " + reminderId));
        r.complete();
        reminders.save(r);
    }

    @Transactional(readOnly = true)
    public EmailDrafter.Draft draft(long id) {
        Mismatch m = mismatch(id);
        Vendor v = vendors.findById(m.getVendorGstin()).orElseThrow(() -> new NoSuchElementException("Unknown vendor"));
        List<Mismatch> open = mismatches.findByVendorGstinOrderByDetectedAtAscIdAsc(v.getGstin()).stream()
                .filter(x -> x.getStatus().open()).toList();
        return drafter.draft(v, open.isEmpty() ? List.of(m) : open);
    }

    /**
     * Record a message on the vendor thread. A message we sent moves the case to Waiting for vendor and sets a
     * follow-up reminder a week out; a reply moves it to Vendor responded (optionally with a promised date).
     */
    @Transactional
    public CaseDetail vendorMessage(long id, VendorCommunication.Direction direction, VendorCommunication.Channel channel,
                                    String summary, LocalDate promiseBy, String by) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Summarise the message.");
        }
        Mismatch m = mismatch(id);
        Instant now = Instant.now(clock);
        String who = direction == VendorCommunication.Direction.OUT ? who(by) : (by == null || by.isBlank() ? m.getVendorName() : by);
        VendorCommunication c = communications.save(new VendorCommunication(m.getVendorGstin(), now, direction, channel, who,
                summary.trim(), List.of(m.invoiceNo()), promiseBy, null, "APP"));
        publisher.publish("Vendor thread " + Fmt.day(now), Fmt.period(now), List.of(writer.communication(c)));
        CaseFile f = file(m, now, who(by));
        CaseState target = direction == VendorCommunication.Direction.OUT ? CaseState.WAITING_FOR_VENDOR : CaseState.VENDOR_RESPONDED;
        if (f.getState() != target && f.getState().canMoveTo(target)) {
            f.moveTo(target, now, who(by));
            cases.save(f);
        }
        if (direction == VendorCommunication.Direction.OUT) {
            reminders.save(new Reminder(id, LocalDate.now(clock).plusDays(7), "No reply yet? Follow up with " + m.getVendorName(),
                    now, who(by)));
        }
        return detail(id);
    }

    /** Reverse the ITC in GSTR-3B: the exposure becomes a confirmed loss. Needs the approver's name. */
    @Transactional
    public CaseDetail writeOff(long id, String approvedBy, String note, String by) {
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("An ITC write-off needs the approver's name.");
        }
        Mismatch m = mismatch(id);
        if (!m.getStatus().open()) {
            throw new IllegalStateException("Only an open case can be written off.");
        }
        Instant now = Instant.now(clock);
        m.writeOff(now, note);
        mismatches.save(m);
        CaseFile f = file(m, now, who(by));
        if (f.getState().canMoveTo(CaseState.RESOLVED)) {
            f.moveTo(CaseState.RESOLVED, now, who(by));
            cases.save(f);
        }
        String text = "ITC of " + Fmt.inr(m.getExposure()) + " reversed in GSTR-3B" + (note == null || note.isBlank() ? "." : ": " + note.trim());
        remember(actions.save(new AccountantAction(m.getVendorGstin(), id, now, who(by), AccountantAction.WRITE_OFF, text,
                approvedBy.trim())), m);
        return detail(id);
    }

    /** Applies an approved AUTO_RESOLVE (rule FORMAT_ONLY): resolved as a confirmed typo, audited, reversible. */
    @Transactional
    public void applyAutoResolve(Recommendation r, String by) {
        Mismatch m = mismatch(r.getMismatchId());
        if (!m.getStatus().open()) {
            return;
        }
        Instant now = Instant.now(clock);
        m.judge(com.vishwas.matching.Outcome.CONFIRMED_TYPO, m.getPeriod(), now, 0, m.getExposure(),
                "Auto-resolved under approved rule " + r.getRuleId() + " (approved by " + who(by) + ").", null);
        mismatches.save(m);
        remember(actions.save(new AccountantAction(m.getVendorGstin(), m.getId(), now, who(by), AccountantAction.AUTO_RESOLVE_APPLIED,
                "Auto-resolved " + m.invoiceNo() + " as a format difference under rule " + r.getRuleId() + ".", who(by))), m);
    }

    /** Undo an auto-resolution: the case is open again and the reversal is audited. */
    @Transactional
    public CaseDetail reverseAutoResolve(long id, String reason, String by) {
        Mismatch m = mismatch(id);
        boolean applied = actions.findByMismatchIdOrderByOccurredAtAsc(id).stream()
                .anyMatch(a -> AccountantAction.AUTO_RESOLVE_APPLIED.equals(a.getAction()));
        if (!applied || m.getStatus() != MismatchStatus.RESOLVED) {
            throw new IllegalStateException("This case was not auto-resolved.");
        }
        Instant now = Instant.now(clock);
        m.reopen();
        mismatches.save(m);
        remember(actions.save(new AccountantAction(m.getVendorGstin(), id, now, who(by), AccountantAction.AUTO_RESOLVE_REVERSED,
                "Auto-resolution of " + m.invoiceNo() + " reversed" + (reason == null || reason.isBlank() ? "." : ": " + reason.trim()),
                null)), m);
        return detail(id);
    }

    private void remember(AccountantAction a, Mismatch m) {
        publisher.publish("Accountant action " + Fmt.day(a.getOccurredAt()), Fmt.period(a.getOccurredAt()), List.of(writer.action(a, m)));
    }

    private CaseFile file(Mismatch m, Instant now, String by) {
        return cases.findById(m.getId()).orElseGet(() -> cases.save(new CaseFile(m.getId(), now, by)));
    }

    private Mismatch mismatch(long id) {
        return mismatches.findById(id).orElseThrow(() -> new NoSuchElementException("No case " + id));
    }

    private String who(String by) {
        return by == null || by.isBlank() ? accountant : by.trim();
    }
}
