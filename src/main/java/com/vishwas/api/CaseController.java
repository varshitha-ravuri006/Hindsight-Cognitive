package com.vishwas.api;

import com.vishwas.advisor.LearningLoop;
import com.vishwas.advisor.Recommendation;
import com.vishwas.workflow.CaseBriefService;
import com.vishwas.workflow.DecisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The investigation brief and the Approve / Modify / Reject decision on a recommendation. */
@RestController
@RequestMapping("/api")
public class CaseController {

    public record DecisionRequest(@NotNull Recommendation.Decision decision, @NotNull Recommendation.Reason reason,
                                  @Size(max = 500) String note, @Size(max = 500) String modifiedStep, @Size(max = 100) String by) {
    }

    private final CaseBriefService briefs;
    private final DecisionService decisions;
    private final LearningLoop learning;

    public CaseController(CaseBriefService briefs, DecisionService decisions, LearningLoop learning) {
        this.briefs = briefs;
        this.decisions = decisions;
        this.learning = learning;
    }

    @GetMapping("/cases/{id}")
    public CaseBriefService.Brief brief(@PathVariable long id, @RequestParam(required = false) String period) {
        return briefs.brief(id, period);
    }

    @PostMapping("/recommendations/{id}/decision")
    public Map<String, Object> decide(@PathVariable long id, @Valid @RequestBody DecisionRequest req) {
        if (req.decision() == Recommendation.Decision.MODIFIED && (req.modifiedStep() == null || req.modifiedStep().isBlank())) {
            throw new IllegalArgumentException("A modified recommendation needs the modified next step.");
        }
        decisions.decide(id, req.decision(), req.reason(), req.note(), req.modifiedStep(), req.by());
        return Map.of("recommendationId", id, "decision", req.decision());
    }

    @GetMapping("/learning/accuracy")
    public List<LearningLoop.Accuracy> accuracy() {
        return learning.accuracy();
    }
}
