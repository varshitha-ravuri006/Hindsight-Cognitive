package com.vishwas.memory;

import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.support.StubApis;
import com.vishwas.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankSetupTest {

    private StubApis stub;
    private BankSetup setup;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApis();
        var props = TestProps.withApis(stub.baseUrl());
        setup = new BankSetup(new HindsightClient(TestProps.http(), TestProps.apiJson(), props), props, new MemoryHealth());
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void configuresMissionsDispositionDirectivesAndMentalModels() {
        setup.setup("vishwas-test");

        assertThat(stub.calls("PUT", "/banks/vishwas-test")).hasSize(1);
        var config = stub.calls("PATCH", "/banks/vishwas-test/config");
        assertThat(config).hasSize(1);
        var updates = config.get(0).body().path("updates");
        assertThat(updates.path("reflect_mission").asText()).contains("protect input tax credit");
        assertThat(updates.path("retain_mission").asText()).contains("promise");
        assertThat(updates.path("disposition_skepticism").asInt()).isEqualTo(5);
        assertThat(updates.path("disposition_literalism").asInt()).isEqualTo(5);
        assertThat(updates.path("enable_observations").asBoolean()).isTrue();

        var directives = stub.calls("POST", "/directives");
        assertThat(directives).hasSize(5);
        assertThat(directives).extracting(c -> c.body().path("name").asText())
                .contains("no-payment-actions", "broken-promises-are-evidence", "own-track-record");

        var models = stub.calls("POST", "/mental-models");
        assertThat(models).extracting(c -> c.body().path("id").asText())
                .containsExactly("money-at-risk-briefing", "vendor-watchlist");
    }

    @Test
    void fallsBackToBankFieldsWhenConfigApiIsDisabled() {
        stub.on("PATCH", "/config", c -> new StubApis.Reply(403, "{\"detail\":\"bank config API disabled\"}"));

        setup.setup("vishwas-test");

        var puts = stub.calls("PUT", "/banks/vishwas-test");
        assertThat(puts).hasSize(2);
        assertThat(puts.get(1).body().path("disposition_skepticism").asInt()).isEqualTo(5);
        assertThat(puts.get(1).body().path("observations_mission").asText()).isNotBlank();
    }

    @Test
    void updatesOnlyDirectivesWhoseWordingChanged() {
        String unchanged = MemoryDesign.DIRECTIVES.get(0).content().replace("\"", "\\\"").replace("\n", "\\n");
        stub.on("GET", "/directives", c -> StubApis.Reply.ok("{\"items\":["
                + "{\"id\":\"d1\",\"name\":\"cite-month-amount-outcome\",\"content\":\"" + unchanged + "\",\"priority\":10},"
                + "{\"id\":\"d2\",\"name\":\"no-payment-actions\",\"content\":\"old wording\",\"priority\":9}]}"));

        setup.setup("vishwas-test");

        assertThat(stub.calls("POST", "/directives")).hasSize(3);
        assertThat(stub.calls("PATCH", "/directives/d2")).hasSize(1);
        assertThat(stub.calls("PATCH", "/directives/d1")).isEmpty();
    }
}
