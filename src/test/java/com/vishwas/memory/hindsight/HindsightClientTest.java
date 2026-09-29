package com.vishwas.memory.hindsight;

import com.vishwas.support.StubApis;
import com.vishwas.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HindsightClientTest {

    private StubApis stub;
    private HindsightClient client;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApis();
        client = new HindsightClient(TestProps.http(), TestProps.apiJson(), TestProps.withApis(stub.baseUrl()));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void retainSendsSnakeCaseItemsWithScopesEntitiesAndUpdateMode() {
        var item = new MemoryItem("Kaveri Packaging promised to file by 20 Jul 2026.", "2026-07-06T11:00:00+05:30",
                "vendor communication", "thread-29AAHCK5512D1ZO", List.of("vendor:29AAHCK5512D1ZO", "dim:RESPONSIVENESS"),
                Map.of("vendor", "Kaveri Packaging Pvt Ltd"),
                List.of(List.of("vendor:29AAHCK5512D1ZO"), List.of("vendor:29AAHCK5512D1ZO", "dim:RESPONSIVENESS")),
                List.of(new MemoryItem.EntityInput("Kaveri Packaging Pvt Ltd", "ORGANIZATION")), false, "append");

        String op = client.retain("bank-1", List.of(item), true);

        assertThat(op).startsWith("op-");
        var sent = stub.calls("POST", "/banks/bank-1/memories").get(0).body();
        assertThat(sent.path("async").asBoolean()).isTrue();
        var i = sent.path("items").get(0);
        assertThat(i.path("document_id").asText()).isEqualTo("thread-29AAHCK5512D1ZO");
        assertThat(i.path("update_mode").asText()).isEqualTo("append");
        assertThat(i.path("resolve_entities").asBoolean(true)).isFalse();
        assertThat(i.path("observation_scopes").get(1).get(1).asText()).isEqualTo("dim:RESPONSIVENESS");
        assertThat(i.path("entities").get(0).path("text").asText()).isEqualTo("Kaveri Packaging Pvt Ltd");
    }

    @Test
    void fileRetainIsMultipartWithPerFileMetadata() {
        var pdf = new FileUpload("kaveri-letter.pdf", "application/pdf", "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8),
                "letter-kaveri-2026-07-06", "vendor communication", "2026-07-06T10:00:00+05:30",
                List.of("vendor:29AAHCK5512D1ZO", "dim:RESPONSIVENESS"), Map.of("kind", "letter"));

        List<String> ops = client.retainFiles("bank-1", List.of(pdf));

        assertThat(ops).containsExactly("op-file-1");
        var call = stub.calls("POST", "/files/retain").get(0);
        assertThat(call.contentType()).startsWith("multipart/form-data; boundary=");
        String raw = call.rawText();
        assertThat(raw).contains("name=\"request\"").contains("\"files_metadata\"")
                .contains("\"document_id\":\"letter-kaveri-2026-07-06\"")
                .contains("\"tags\":[\"vendor:29AAHCK5512D1ZO\",\"dim:RESPONSIVENESS\"]")
                .contains("name=\"files\"; filename=\"kaveri-letter.pdf\"").contains("%PDF-1.4 test");
    }

    @Test
    void listMemoriesRepeatsTagsForExactScope() {
        client.listMemories("bank-1", "observation", List.of("vendor:36ABKFS2231Q1ZP", "dim:TIMING"), "exact", null, 5, 0);

        String q = stub.calls("GET", "/memories/list").get(0).query();
        assertThat(q).contains("type=observation").contains("tags=vendor%3A36ABKFS2231Q1ZP")
                .contains("tags=dim%3ATIMING").contains("tags_match=exact");
    }

    @Test
    void recallCarriesTemporalAnchorAndWindow() {
        var query = RecallQuery.of("What happened to Kaveri last month?", null, List.of("vendor:29AAHCK5512D1ZO"), "any_strict")
                .withTime("2026-09-28T12:00:00+05:30", new RecallQuery.TemporalWindow("2026-08-01", "2026-08-31"));

        client.recall("bank-1", query);

        var body = stub.calls("POST", "/memories/recall").get(0).body();
        assertThat(body.path("query_timestamp").asText()).isEqualTo("2026-09-28T12:00:00+05:30");
        assertThat(body.path("temporal_window").path("start").asText()).isEqualTo("2026-08-01");
        assertThat(body.path("tags_match").asText()).isEqualTo("any_strict");
    }

    @Test
    void parsesObservationHistory() {
        stub.on("GET", "/history", c -> StubApis.Reply.ok("[{\"previous_text\":\"Sometimes late.\",\"changed_at\":\"2026-09-28T10:00:00+00:00\","
                + "\"new_source_memory_ids\":[\"f2\"],\"source_facts\":[{\"id\":\"f2\",\"text\":\"Appeared in June 2B\",\"is_new\":true}]}]"));

        var history = client.observationHistory("bank-1", "obs-1");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).previousText()).isEqualTo("Sometimes late.");
        assertThat(history.get(0).sourceFacts().get(0).isNew()).isTrue();
    }

    @Test
    void httpErrorsBecomeHindsightExceptionsWithStatus() {
        stub.on("POST", "/reflect", c -> new StubApis.Reply(503, "{\"detail\":\"overloaded\"}"));

        assertThatThrownBy(() -> client.reflect("bank-1", ReflectQuery.scoped("q", List.of("vendor:x"), "low", null, 100)))
                .isInstanceOf(HindsightException.class)
                .satisfies(e -> assertThat(((HindsightException) e).status()).isEqualTo(503));
    }

    @Test
    void notConfiguredFailsFastWithoutNetwork() {
        var offline = new HindsightClient(TestProps.http(), TestProps.apiJson(), TestProps.defaults());
        assertThat(offline.configured()).isFalse();
        assertThatThrownBy(() -> offline.stats("b")).isInstanceOf(HindsightException.class).hasMessageContaining("not set");
    }
}
