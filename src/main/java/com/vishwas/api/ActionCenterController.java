package com.vishwas.api;

import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.workflow.ActionCenterService;
import com.vishwas.workflow.CaseState;
import com.vishwas.workflow.EmailDrafter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** The action center: case states, owners, due dates, notes, reminders, vendor e-mails and write-offs. */
@RestController
@RequestMapping("/api")
public class ActionCenterController {

    public record TransitionRequest(@NotNull CaseState to, @Size(max = 1000) String note, @Size(max = 100) String by) {
    }

    public record AssignRequest(@Size(max = 100) String owner, LocalDate dueDate, @Size(max = 100) String by) {
    }

    public record NoteRequest(@NotNull @Size(min = 1, max = 2000) String text, @Size(max = 100) String by) {
    }

    public record ReminderRequest(@NotNull LocalDate dueOn, @Size(max = 500) String text, @Size(max = 100) String by) {
    }

    public record MessageRequest(@NotNull VendorCommunication.Direction direction, @NotNull VendorCommunication.Channel channel,
                                 @NotNull @Size(min = 1, max = 1000) String summary, LocalDate promiseBy, @Size(max = 100) String by) {
    }

    public record WriteOffRequest(@NotNull @Size(min = 1, max = 100) String approvedBy, @Size(max = 1000) String note,
                                  @Size(max = 100) String by) {
    }

    public record ReverseRequest(@Size(max = 1000) String reason, @Size(max = 100) String by) {
    }

    private final ActionCenterService actions;

    public ActionCenterController(ActionCenterService actions) {
        this.actions = actions;
    }

    @GetMapping("/action-center")
    public ActionCenterService.Board board() {
        return actions.board();
    }

    @GetMapping("/cases/{id}/workflow")
    public ActionCenterService.CaseDetail detail(@PathVariable long id) {
        return actions.detail(id);
    }

    @PostMapping("/cases/{id}/transition")
    public ActionCenterService.CaseDetail transition(@PathVariable long id, @Valid @RequestBody TransitionRequest r) {
        return actions.transition(id, r.to(), r.note(), r.by());
    }

    @PostMapping("/cases/{id}/assign")
    public ActionCenterService.CaseDetail assign(@PathVariable long id, @Valid @RequestBody AssignRequest r) {
        return actions.assign(id, r.owner(), r.dueDate(), r.by());
    }

    @PostMapping("/cases/{id}/notes")
    public ActionCenterService.CaseDetail note(@PathVariable long id, @Valid @RequestBody NoteRequest r) {
        return actions.note(id, r.text(), r.by());
    }

    @PostMapping("/cases/{id}/reminders")
    public ActionCenterService.CaseDetail remind(@PathVariable long id, @Valid @RequestBody ReminderRequest r) {
        return actions.remind(id, r.dueOn(), r.text(), r.by());
    }

    @PostMapping("/reminders/{id}/done")
    public Map<String, Object> reminderDone(@PathVariable long id) {
        actions.completeReminder(id);
        return Map.of("reminderId", id, "done", true);
    }

    @GetMapping("/cases/{id}/email-draft")
    public EmailDrafter.Draft draft(@PathVariable long id) {
        return actions.draft(id);
    }

    @PostMapping("/cases/{id}/messages")
    public ActionCenterService.CaseDetail message(@PathVariable long id, @Valid @RequestBody MessageRequest r) {
        return actions.vendorMessage(id, r.direction(), r.channel(), r.summary(), r.promiseBy(), r.by());
    }

    @PostMapping("/cases/{id}/write-off")
    public ActionCenterService.CaseDetail writeOff(@PathVariable long id, @Valid @RequestBody WriteOffRequest r) {
        return actions.writeOff(id, r.approvedBy(), r.note(), r.by());
    }

    @PostMapping("/cases/{id}/auto-resolve/reverse")
    public ActionCenterService.CaseDetail reverse(@PathVariable long id, @Valid @RequestBody ReverseRequest r) {
        return actions.reverseAutoResolve(id, r.reason(), r.by());
    }
}
