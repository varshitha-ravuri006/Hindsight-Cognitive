package com.vishwas.advisor;

/** The four decision categories, from most to least automatic. */
public enum Category {
    AUTO_RESOLVE("Auto-resolve", "An explicitly approved deterministic rule applies; fully audited and reversible."),
    RECOMMEND("Recommend", "Likely cause with a suggested next step."),
    REQUIRE_REVIEW("Review", "Material, ambiguous or financially significant: a person must decide."),
    ESCALATE("Escalate", "Missing evidence, conflicting records or a suspicious pattern.");

    private final String label;
    private final String meaning;

    Category(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    public String label() {
        return label;
    }

    public String meaning() {
        return meaning;
    }

    public boolean needsReview() {
        return this == REQUIRE_REVIEW || this == ESCALATE;
    }

    /** Stricter of two categories (ESCALATE > REQUIRE_REVIEW > RECOMMEND > AUTO_RESOLVE). */
    public static Category stricter(Category a, Category b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
