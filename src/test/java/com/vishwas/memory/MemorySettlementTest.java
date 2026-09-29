package com.vishwas.memory;

import com.vishwas.memory.hindsight.Operation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySettlementTest {

    static final Instant SINCE = Instant.parse("2026-09-29T10:00:00Z");

    static Operation op(String kind, String status, String created, String completed) {
        return new Operation("op", null, kind, null, 1, status, created, completed, null);
    }

    @Test
    void extractionComesFirst() {
        var s = MemorySettlement.classify(List.of(op("retain", "processing", "2026-09-29T10:00:01Z", null)), SINCE, false,
                SINCE.plusSeconds(5));
        assertThat(s.stage()).isEqualTo(MemorySettlement.Stage.EXTRACTING);
    }

    @Test
    void thenConsolidationThenModels() {
        var consolidating = MemorySettlement.classify(List.of(
                op("retain", "completed", "2026-09-29T10:00:01Z", "2026-09-29T10:00:30Z"),
                op("consolidation", "processing", "2026-09-29T10:00:31Z", null)), SINCE, false, SINCE.plusSeconds(40));
        assertThat(consolidating.stage()).isEqualTo(MemorySettlement.Stage.CONSOLIDATING);

        var models = MemorySettlement.classify(List.of(
                op("consolidation", "completed", "2026-09-29T10:00:31Z", "2026-09-29T10:01:30Z"),
                op("refresh_mental_model", "pending", "2026-09-29T10:01:31Z", null)), SINCE, false, SINCE.plusSeconds(95));
        assertThat(models.stage()).isEqualTo(MemorySettlement.Stage.REFRESHING_MODELS);
        assertThat(models.consolidationCompleted()).isTrue();
    }

    @Test
    void settledOnceConsolidatedAndNothingIsBusy() {
        var s = MemorySettlement.classify(List.of(
                op("retain", "completed", "2026-09-29T10:00:01Z", "2026-09-29T10:00:30Z"),
                op("consolidation", "completed", "2026-09-29T10:00:31Z", "2026-09-29T10:01:30Z")), SINCE, false, SINCE.plusSeconds(100));
        assertThat(s.stage()).isEqualTo(MemorySettlement.Stage.SETTLED);
    }

    @Test
    void extractionDoneButNoConsolidationYetKeepsWaitingUntilQuiet() {
        var ops = List.of(op("retain", "completed", "2026-09-29T10:00:01Z", "2026-09-29T10:00:30Z"));
        assertThat(MemorySettlement.classify(ops, SINCE, false, SINCE.plusSeconds(35)).stage())
                .isEqualTo(MemorySettlement.Stage.CONSOLIDATING);
        assertThat(MemorySettlement.classify(ops, SINCE, false, SINCE.plusSeconds(60)).stage())
                .isEqualTo(MemorySettlement.Stage.SETTLED);
        assertThat(MemorySettlement.classify(ops, SINCE, true, SINCE.plusSeconds(35)).stage())
                .as("the webhook ends the wait early").isEqualTo(MemorySettlement.Stage.SETTLED);
    }
}
