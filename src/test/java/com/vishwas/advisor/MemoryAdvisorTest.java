package com.vishwas.advisor;

import com.vishwas.matching.Dimension;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryAdvisorTest {

    static HistoryStats.PastCase c(String period, String inv, String amt, MismatchStatus s, Outcome o, Integer late, String loss) {
        return new HistoryStats.PastCase(1, period, inv, "MISSING_IN_2B", new BigDecimal(amt), s, o, late, null,
                BigDecimal.ZERO, new BigDecimal(loss));
    }

    @Test
    void ledgerStatesExactCountsAmountsAndOpenExposure() {
        var stats = new HistoryStats(List.of(
                c("2026-04", "KPL/0456", "8640", MismatchStatus.WRITTEN_OFF, Outcome.UNRESOLVED_AT_RISK, 2, "8640"),
                c("2026-05", "KPL/0502", "12600", MismatchStatus.AT_RISK, Outcome.UNRESOLVED_AT_RISK, 2, "0"),
                c("2026-06", "KPL/0547", "13500", MismatchStatus.AT_RISK, Outcome.UNRESOLVED_AT_RISK, 2, "0"),
                c("2026-07", "KPL/0589", "15900", MismatchStatus.OPEN, null, null, "0")));

        String line = MemoryAdvisor.ledger(stats, Dimension.TIMING);

        assertThat(line).startsWith("TIMING: 4 past cases")
                .contains("April 2026 KPL/0456 Rs 8,640 ITC reversed, confirmed loss Rs 8,640")
                .contains("May 2026 KPL/0502 Rs 12,600 unresolved, at risk")
                .contains("July 2026 KPL/0589 Rs 15,900 still open")
                .endsWith("Still unresolved: 3 cases (Rs 42,000 potential exposure).");
    }

    @Test
    void emptyHistoryIsSaidPlainly() {
        assertThat(MemoryAdvisor.ledger(HistoryStats.empty(), Dimension.TIMING)).contains("no past cases");
    }

    @Test
    void resolvedLateCasesStateMonthsLate() {
        var stats = new HistoryStats(List.of(c("2026-05", "SBT/2026/0057", "11052", MismatchStatus.RESOLVED, Outcome.RESOLVED_LATE, 1, "0")));
        assertThat(MemoryAdvisor.ledger(stats, Dimension.TIMING)).contains("SBT/2026/0057 Rs 11,052 appeared 1 month late")
                .endsWith("Still unresolved: 0 cases.");
    }
}
