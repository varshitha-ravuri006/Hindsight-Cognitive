package com.vishwas.memory;

import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.support.StubApis;
import com.vishwas.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurationServiceTest {

    static final String SBT = "36ABKFS2231Q1ZP";
    private StubApis stub;
    private CurationService curation;
    private final List<MemoryCorrection> saved = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        stub = new StubApis();
        stub.on("GET", "/memories/fact-1", c -> StubApis.Reply.ok("{\"id\":\"fact-1\",\"text\":\"SBT/2026/0057 was never filed.\","
                + "\"fact_type\":\"world\",\"tags\":[\"vendor:" + SBT + "\",\"dim:TIMING\"]}"));
        stub.on("GET", "/memories/obs-1", c -> StubApis.Reply.ok("{\"id\":\"obs-1\",\"text\":\"belief\",\"fact_type\":\"observation\","
                + "\"tags\":[\"vendor:" + SBT + "\"]}"));
        var props = TestProps.withApis(stub.baseUrl());
        MemoryCorrectionRepository repo = mock(MemoryCorrectionRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        MemoryHealth health = new MemoryHealth();
        health.set(MemoryHealth.State.READY, null);
        var client = new HindsightClient(TestProps.http(), TestProps.apiJson(), props);
        curation = new CurationService(client, repo, new MemoryStats(client, health, props), Clock.systemUTC(), props);
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void invalidatingPatchesTheFactWithStateAndReasonThenRebuildsTheVendorsBeliefs() {
        var row = curation.correct(SBT, "fact-1", MemoryCorrection.Action.INVALIDATE, null,
                "Wrong: SBT/2026/0057 appeared in the June GSTR-2B", "Lakshmi Prasad");

        var patch = stub.calls("PATCH", "/memories/fact-1").get(0).body();
        assertThat(patch.path("state").asText()).isEqualTo("invalidated");
        assertThat(patch.path("reason").asText()).contains("appeared in the June GSTR-2B").contains("Lakshmi Prasad");
        assertThat(patch.has("text")).isFalse();
        var consolidate = stub.calls("POST", "/consolidate").get(0).body();
        assertThat(consolidate.path("observation_scopes").toString()).contains("vendor:" + SBT).contains("dim:TIMING");
        assertThat(row.getStatus()).isEqualTo("DONE");
        assertThat(row.getOldText()).isEqualTo("SBT/2026/0057 was never filed.");
        assertThat(row.getActor()).isEqualTo("Lakshmi Prasad");
    }

    @Test
    void editingSendsTheNewText() {
        curation.correct(SBT, "fact-1", MemoryCorrection.Action.EDIT, "SBT/2026/0057 appeared one month late.", "typo in memory", "LP");
        assertThat(stub.calls("PATCH", "/memories/fact-1").get(0).body().path("text").asText())
                .isEqualTo("SBT/2026/0057 appeared one month late.");
    }

    @Test
    void revertMakesTheFactValidAgain() {
        curation.correct(SBT, "fact-1", MemoryCorrection.Action.REVERT, null, "restored", "LP");
        assertThat(stub.calls("PATCH", "/memories/fact-1").get(0).body().path("state").asText()).isEqualTo("valid");
    }

    @Test
    void refusesObservationsOtherVendorsAndMissingReasons() {
        assertThatThrownBy(() -> curation.correct(SBT, "obs-1", MemoryCorrection.Action.INVALIDATE, null, "why", "LP"))
                .hasMessageContaining("Observations are derived");
        assertThatThrownBy(() -> curation.correct("36ADSFS7710L1ZD", "fact-1", MemoryCorrection.Action.INVALIDATE, null, "why", "LP"))
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> curation.correct(SBT, "fact-1", MemoryCorrection.Action.INVALIDATE, null, " ", "LP"))
                .hasMessageContaining("why");
    }

    @Test
    void aFailedPatchIsStillAudited() {
        stub.on("PATCH", "/memories/fact-1", c -> new StubApis.Reply(422, "{\"detail\":\"bad state\"}"));
        var row = curation.correct(SBT, "fact-1", MemoryCorrection.Action.INVALIDATE, null, "why", "LP");
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getError()).contains("422");
        assertThat(saved).hasSize(2);
    }
}
