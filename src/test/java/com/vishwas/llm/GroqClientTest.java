package com.vishwas.llm;

import com.vishwas.support.StubApis;
import com.vishwas.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GroqClientTest {

    private StubApis stub;
    private GroqClient groq;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApis();
        groq = new GroqClient(TestProps.http(), TestProps.apiJson(), TestProps.withApis(stub.baseUrl()));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void jsonModeRetriesOnceWithTheValidationErrorThenSucceeds() {
        AtomicInteger n = new AtomicInteger();
        stub.on("POST", "/chat/completions", c -> StubApis.Reply.ok(StubApis.groqText(
                n.incrementAndGet() == 1 ? "{\"cases\": \"oops\"}" : "```json\n{\"cases\": []}\n```")));

        var result = groq.completeJson("sys", "user",
                j -> j.path("cases").isArray() ? Optional.empty() : Optional.of("cases must be an array"));

        assertThat(result).isPresent();
        var second = stub.calls("POST", "/chat/completions").get(1).body();
        assertThat(second.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(second.path("messages").toString()).contains("cases must be an array");
    }

    @Test
    void jsonModeGivesUpAfterOneRetry() {
        stub.on("POST", "/chat/completions", c -> StubApis.Reply.ok(StubApis.groqText("not json at all")));

        assertThat(groq.completeJson("sys", "user", j -> Optional.empty())).isEmpty();
        assertThat(stub.calls("POST", "/chat/completions")).hasSize(2);
    }

    @Test
    void parsesToolCallArguments() {
        stub.on("POST", "/chat/completions", c -> StubApis.Reply.ok(
                StubApis.groqToolCall("search_mismatches", "{\"min_exposure_inr\":25000,\"status\":\"OPEN\"}")));

        var turn = groq.chat(List.of(GroqClient.msg("user", "Show unresolved discrepancies above 25000")),
                List.of(Map.of("type", "function", "function", Map.of("name", "search_mismatches"))));

        assertThat(turn).isPresent();
        assertThat(turn.get().wantsTools()).isTrue();
        assertThat(turn.get().toolCalls().get(0).arguments().path("min_exposure_inr").asInt()).isEqualTo(25000);
    }

    @Test
    void toolUseFailuresAreRetriedThenEmpty() {
        stub.on("POST", "/chat/completions", c -> new StubApis.Reply(400,
                "{\"error\":{\"code\":\"tool_use_failed\",\"message\":\"Failed to call a function\"}}"));

        assertThat(groq.chat(List.of(GroqClient.msg("user", "hi")), List.of())).isEmpty();
        assertThat(stub.calls("POST", "/chat/completions")).hasSize(2);
    }

    @Test
    void unconfiguredClientNeverCallsOut() {
        var offline = new GroqClient(TestProps.http(), TestProps.apiJson(), TestProps.defaults());
        assertThat(offline.complete("a", "b")).isEmpty();
        assertThat(offline.healthy()).isFalse();
    }
}
