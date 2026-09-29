package com.vishwas.memory;

import com.vishwas.matching.Dimension;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.support.StubApis;
import com.vishwas.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VendorMemoryTest {

    static final String SBT = "36ABKFS2231Q1ZP";

    private StubApis stub;
    private VendorMemory memory;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApis();
        var props = TestProps.withApis(stub.baseUrl());
        MemoryBatchRepository batches = mock(MemoryBatchRepository.class);
        when(batches.findAllByOrderByStartedAtAsc()).thenReturn(List.of(
                batch("History: April 2026", "2026-09-28T10:00:00Z"),
                batch("History: May 2026", "2026-09-28T10:10:00Z"),
                batch("History: June 2026", "2026-09-28T10:20:00Z"),
                batch("Next month: September 2026", "2026-09-28T12:00:00Z")));
        memory = new VendorMemory(new HindsightClient(TestProps.http(), TestProps.apiJson(), props), batches, props);
    }

    static MemoryBatch batch(String label, String at) {
        return new MemoryBatch(label, null, 10, Instant.parse(at));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void beliefHistoryLabelsEachVersionWithTheMonthThatCausedIt() {
        stub.on("GET", "/memories/list", c -> StubApis.Reply.ok("{\"items\":[{\"id\":\"obs-1\",\"text\":"
                + "\"Sri Balaji Traders' missing invoices appeared in the next GSTR-2B in 5 of 5 cases.\",\"fact_type\":\"observation\","
                + "\"tags\":[\"vendor:" + SBT + "\",\"dim:TIMING\"]}],\"total\":1,\"limit\":5,\"offset\":0}"));
        // most recent first, as the API returns it
        stub.on("GET", "/memories/obs-1/history", c -> StubApis.Reply.ok("["
                + "{\"previous_text\":\"Appeared next month in 3 of 3 cases.\",\"changed_at\":\"2026-09-28T12:03:00+00:00\","
                + "\"source_facts\":[{\"id\":\"f9\",\"text\":\"SBT/2026/0118 appeared 1 month late in the September 2026 GSTR-2B\",\"is_new\":true}]},"
                + "{\"previous_text\":\"Two invoices were late in April.\",\"changed_at\":\"2026-09-28T10:12:00+00:00\","
                + "\"source_facts\":[{\"id\":\"f3\",\"text\":\"SBT/2026/0057 missing in May\",\"is_new\":true}]}]"));

        var h = memory.beliefHistory(SBT, Dimension.TIMING).orElseThrow();

        assertThat(stub.calls("GET", "/memories/list").get(0).query()).contains("tags_match=exact").contains("dim%3ATIMING");
        assertThat(h.versions()).extracting(VendorMemory.BeliefVersion::text).containsExactly(
                "Two invoices were late in April.",
                "Appeared next month in 3 of 3 cases.",
                "Sri Balaji Traders' missing invoices appeared in the next GSTR-2B in 5 of 5 cases.");
        assertThat(h.versions()).extracting(VendorMemory.BeliefVersion::becauseOf).containsExactly(
                "History: April 2026", "History: May 2026", "Next month: September 2026");
        assertThat(h.versions().get(2).newFacts()).singleElement().asString().contains("SBT/2026/0118");
    }

    @Test
    void noObservationYetMeansNoHistory() {
        assertThat(memory.beliefHistory(SBT, Dimension.TIMING)).isEmpty();
    }

    @Test
    void learnedBeliefsAreScopedToTheVendorOnly() {
        stub.on("POST", "/memories/recall", c -> StubApis.Reply.ok("{\"results\":[{\"id\":\"o1\",\"text\":\"Late filer, always next month.\","
                + "\"type\":\"observation\",\"tags\":[\"vendor:" + SBT + "\",\"dim:TIMING\"]}]}"));
        var beliefs = memory.learned(SBT, "Sri Balaji Traders");
        var body = stub.calls("POST", "/memories/recall").get(0).body();
        assertThat(body.path("tags").get(0).asText()).isEqualTo("vendor:" + SBT);
        assertThat(body.path("tags_match").asText()).isEqualTo("any_strict");
        assertThat(body.path("types").get(0).asText()).isEqualTo("observation");
        assertThat(beliefs).singleElement().satisfies(b -> assertThat(b.dimension()).isEqualTo("TIMING"));
    }
}
