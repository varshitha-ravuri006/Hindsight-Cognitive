package com.vishwas.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vishwas.config.VishwasProperties;
import com.vishwas.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotStoreTest {

    @TempDir
    Path dir;

    SnapshotStore store() {
        var p = TestProps.defaults();
        var props = new VishwasProperties(p.zone(), p.company(), p.matching(), p.outcomes(), p.advice(), p.autoResolve(), p.memory(),
                p.hindsight(), p.groq(), new VishwasProperties.Demo(true, dir.resolve("snapshot.json").toString()));
        return new SnapshotStore(new ObjectMapper(), props, Clock.systemUTC());
    }

    @Test
    void savesOrderedResponsesPerEndpointAndDropsAnythingThatIsNotAnApiCall() {
        var json = new ObjectMapper();
        ObjectNode rec = json.createObjectNode();
        ObjectNode entries = rec.putObject("entries");
        ArrayNode runs = entries.putArray("GET /api/advice-runs/1");
        runs.addObject().put("status", "RUNNING");
        runs.addObject().put("status", "DONE");
        entries.putArray("POST /api/periods/2026-08/reconcile").addObject().put("newMismatches", 12);
        entries.putArray("GET /api/snapshot").addObject();
        entries.putArray("DELETE /api/everything").addObject();
        entries.putArray("GET /etc/passwd").addObject();

        var info = store().save(rec, "vishwas-demo");

        assertThat(info.available()).isTrue();
        assertThat(info.endpoints()).isEqualTo(2);
        assertThat(info.responses()).isEqualTo(3);
        var loaded = store().load().orElseThrow();
        assertThat(loaded.path("format").asText()).isEqualTo(SnapshotStore.FORMAT);
        assertThat(loaded.path("bankId").asText()).isEqualTo("vishwas-demo");
        assertThat(loaded.path("entries").path("GET /api/advice-runs/1").get(1).path("status").asText()).isEqualTo("DONE");
    }

    @Test
    void keepsOnlyTheLastResponsesOfAVeryChattyEndpoint() {
        var json = new ObjectMapper();
        ObjectNode rec = json.createObjectNode();
        ArrayNode seq = rec.putObject("entries").putArray("GET /api/status");
        for (int i = 0; i < SnapshotStore.MAX_PER_ENDPOINT + 50; i++) {
            seq.addObject().put("n", i);
        }
        store().save(rec, "b");
        var kept = store().load().orElseThrow().path("entries").path("GET /api/status");
        assertThat(kept.size()).isEqualTo(SnapshotStore.MAX_PER_ENDPOINT);
        assertThat(kept.get(0).path("n").asInt()).isEqualTo(50);
    }

    @Test
    void anEmptyRecordingIsRejectedAndNoRecordingMeansNotAvailable() {
        assertThat(store().info().available()).isFalse();
        assertThatThrownBy(() -> store().save(new ObjectMapper().createObjectNode(), "b")).hasMessageContaining("no responses");
    }
}
