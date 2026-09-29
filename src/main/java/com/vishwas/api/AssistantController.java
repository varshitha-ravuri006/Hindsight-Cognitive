package com.vishwas.api;

import com.vishwas.assistant.AssistantService;
import com.vishwas.workflow.CloseChecklistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/** The assistant (tool-grounded, stateless) and the month-end close checklist. */
@RestController
@RequestMapping("/api")
public class AssistantController {

    public record Question(@NotBlank @Size(max = 500) String question) {
    }

    public record SignOff(@NotBlank @Size(max = 100) String by, @Size(max = 1000) String note) {
    }

    private final AssistantService assistant;
    private final CloseChecklistService close;

    public AssistantController(AssistantService assistant, CloseChecklistService close) {
        this.assistant = assistant;
        this.close = close;
    }

    @PostMapping("/assistant/ask")
    public AssistantService.Answer ask(@Valid @RequestBody Question q) {
        return assistant.ask(q.question());
    }

    @GetMapping("/close/{period}")
    public CloseChecklistService.Checklist checklist(@PathVariable String period) {
        YearMonth.parse(period);
        return close.checklist(period);
    }

    @PostMapping("/close/{period}/signoff")
    public CloseChecklistService.Checklist signOff(@PathVariable String period, @Valid @RequestBody SignOff s) {
        YearMonth.parse(period);
        return close.signOff(period, s.by(), s.note());
    }
}
