package com.vishwas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Set;

/**
 * Everything tunable lives here and comes from application.yml / env vars. No secrets appear in code.
 */
@ConfigurationProperties(prefix = "vishwas")
public record VishwasProperties(
        ZoneId zone,
        Company company,
        Matching matching,
        Outcomes outcomes,
        Advice advice,
        AutoResolve autoResolve,
        Memory memory,
        Hindsight hindsight,
        Groq groq,
        Demo demo) {

    public record Company(String legalName, String gstin, String stateCode, String city,
                          String accountant, String cfo) {
    }

    public record Matching(BigDecimal amountToleranceInr, int candidateDateWindowDays, int candidateMaxEditDistance) {
    }

    public record Outcomes(int atRiskAfterMonths) {
    }

    public record Advice(BigDecimal materialityInr, int thinHistoryCases, String reflectBudget,
                         int reflectMaxTokens, int parallelism) {
    }

    public record AutoResolve(Set<String> approvedRules) {
        public boolean approved(String rule) {
            return approvedRules != null && approvedRules.contains(rule);
        }
    }

    public record Memory(long processingPollMs, String publicBaseUrl, String webhookSecret) {
        public boolean webhookEnabled() {
            return publicBaseUrl != null && !publicBaseUrl.isBlank();
        }
    }

    public record Hindsight(String baseUrl, String apiKey, String bankId) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Groq(String baseUrl, String apiKey, String model) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Demo(boolean resetEnabled, String snapshotFile) {
    }
}
