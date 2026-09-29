package com.vishwas.advisor;

import com.vishwas.matching.Dimension;
import com.vishwas.matching.Outcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One Vishwas recommendation for one mismatch, and the learning loop around it: the accountant's decision
 * (accept / modify / reject, with a reason) and, once a later GSTR-2B judges the case, whether the
 * recommendation was right. Accuracy is computed from these rows in the database, never by the LLM.
 */
@Entity
@Table(name = "recommendation")
public class Recommendation {

    public enum Source { MEMORY, TEXTBOOK }

    public enum Decision { ACCEPTED, MODIFIED, REJECTED }

    public enum Reason { TIMING_DIFFERENCE, TYPO, DUPLICATE, MISSING_DOCUMENT, INCORRECT_AMOUNT, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long mismatchId;

    private Long runId;

    @Column(nullable = false, length = 15)
    private String vendorGstin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Dimension dimension;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source;

    @Column(length = 600)
    private String headline;
    @Column(length = 600)
    private String nextStep;
    @Column(length = 4000)
    private String causesJson;
    @Column(length = 2000)
    private String evidenceRefsJson;
    @Column(length = 12000)
    private String memoryFactsJson;
    @Column(length = 1000)
    private String guardrailsJson;
    @Column(length = 1500)
    private String vendorSummary;
    @Column(length = 20)
    private String ruleId;

    @Column(length = 8)
    private String confidenceLevel;
    @Column(length = 300)
    private String confidenceText;
    private Integer historyCases;
    @Column(length = 8)
    private String modelConfidence;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Cause topCause;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Outcome predictedOutcome;

    @Column(length = 600)
    private String baselineAction;
    @Column(length = 40)
    private String baselineCategory;

    @Column(nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Decision decision;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Reason decisionReason;
    @Column(length = 600)
    private String decisionNote;
    @Column(length = 600)
    private String modifiedStep;
    private String decidedBy;
    private Instant decidedAt;

    private Boolean wasCorrect;
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Outcome actualOutcome;
    private Instant judgedAt;

    protected Recommendation() {
    }

    public Recommendation(Long mismatchId, Long runId, String vendorGstin, Dimension dimension, Category category, Source source,
                          Instant createdAt) {
        this.mismatchId = mismatchId;
        this.runId = runId;
        this.vendorGstin = vendorGstin;
        this.dimension = dimension;
        this.category = category;
        this.source = source;
        this.createdAt = createdAt;
    }

    public void describe(String headline, String nextStep, Cause topCause, String causesJson, String evidenceRefsJson,
                         String memoryFactsJson, String guardrailsJson, String vendorSummary, String ruleId) {
        this.headline = clip(headline, 600);
        this.nextStep = clip(nextStep, 600);
        this.topCause = topCause;
        this.predictedOutcome = topCause == null ? null : topCause.expectedOutcome();
        this.causesJson = clip(causesJson, 4000);
        this.evidenceRefsJson = clip(evidenceRefsJson, 2000);
        this.memoryFactsJson = clip(memoryFactsJson, 12000);
        this.guardrailsJson = clip(guardrailsJson, 1000);
        this.vendorSummary = clip(vendorSummary, 1500);
        this.ruleId = ruleId;
    }

    public void confidence(Confidence c, String modelConfidence) {
        this.confidenceLevel = c.level().name();
        this.confidenceText = clip(c.explanation(), 300);
        this.historyCases = c.cases();
        this.modelConfidence = modelConfidence;
    }

    public void baseline(String action, String category) {
        this.baselineAction = clip(action, 600);
        this.baselineCategory = category;
    }

    public void decide(Decision decision, Reason reason, String note, String modifiedStep, String by, Instant at) {
        this.decision = decision;
        this.decisionReason = reason;
        this.decisionNote = clip(note, 600);
        this.modifiedStep = clip(modifiedStep, 600);
        this.decidedBy = by;
        this.decidedAt = at;
    }

    /**
     * Judge against the actual outcome. A recommendation with no checkable prediction stays unjudged.
     * The first verdict counts: a timing call that turns "at risk" was wrong even if the invoice shows up later.
     */
    public void judge(Outcome actual, Instant at) {
        if (wasCorrect != null || predictedOutcome == null) {
            return;
        }
        this.actualOutcome = actual;
        this.wasCorrect = predictedOutcome == actual;
        this.judgedAt = at;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public Long getId() {
        return id;
    }

    public Long getMismatchId() {
        return mismatchId;
    }

    public Long getRunId() {
        return runId;
    }

    public String getVendorGstin() {
        return vendorGstin;
    }

    public Dimension getDimension() {
        return dimension;
    }

    public Category getCategory() {
        return category;
    }

    public Source getSource() {
        return source;
    }

    public String getHeadline() {
        return headline;
    }

    public String getNextStep() {
        return nextStep;
    }

    public String getCausesJson() {
        return causesJson;
    }

    public String getEvidenceRefsJson() {
        return evidenceRefsJson;
    }

    public String getMemoryFactsJson() {
        return memoryFactsJson;
    }

    public String getGuardrailsJson() {
        return guardrailsJson;
    }

    public String getVendorSummary() {
        return vendorSummary;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getConfidenceLevel() {
        return confidenceLevel;
    }

    public String getConfidenceText() {
        return confidenceText;
    }

    public Integer getHistoryCases() {
        return historyCases;
    }

    public String getModelConfidence() {
        return modelConfidence;
    }

    public Cause getTopCause() {
        return topCause;
    }

    public Outcome getPredictedOutcome() {
        return predictedOutcome;
    }

    public String getBaselineAction() {
        return baselineAction;
    }

    public String getBaselineCategory() {
        return baselineCategory;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Decision getDecision() {
        return decision;
    }

    public Reason getDecisionReason() {
        return decisionReason;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public String getModifiedStep() {
        return modifiedStep;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Boolean getWasCorrect() {
        return wasCorrect;
    }

    public Outcome getActualOutcome() {
        return actualOutcome;
    }

    public Instant getJudgedAt() {
        return judgedAt;
    }
}
