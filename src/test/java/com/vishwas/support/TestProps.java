package com.vishwas.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.config.AppConfig;
import com.vishwas.config.VishwasProperties;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.ZoneId;
import java.util.Set;

/** Builds {@link VishwasProperties} for plain unit tests (no Spring context), pointed at a stub server. */
public final class TestProps {

    private TestProps() {
    }

    public static VishwasProperties defaults() {
        return withApis(null);
    }

    /** @param stubBaseUrl base URL of {@link StubApis}; null = external APIs not configured */
    public static VishwasProperties withApis(String stubBaseUrl) {
        String key = stubBaseUrl == null ? "" : "test-key";
        return new VishwasProperties(
                ZoneId.of("Asia/Kolkata"),
                new VishwasProperties.Company("Deccan Home Appliances Pvt Ltd", "36AAGCD4821M1ZG", "36", "Hyderabad",
                        "Lakshmi Prasad", "Srinivas Reddy"),
                new VishwasProperties.Matching(new BigDecimal("1.00"), 10, 2),
                new VishwasProperties.Outcomes(2),
                new VishwasProperties.Advice(new BigDecimal("50000"), 3, "low", 3000, 4),
                new VishwasProperties.AutoResolve(Set.of("FORMAT_ONLY")),
                new VishwasProperties.Memory(20, "", ""),
                new VishwasProperties.Hindsight(stubBaseUrl == null ? "http://127.0.0.1:9" : stubBaseUrl, key, "vishwas-test"),
                new VishwasProperties.Groq(stubBaseUrl == null ? "http://127.0.0.1:9" : stubBaseUrl, key, "openai/gpt-oss-120b"),
                new VishwasProperties.Demo(true, "./target/test-snapshot.json"));
    }

    public static ObjectMapper apiJson() {
        return new AppConfig().apiJson();
    }

    public static HttpClient http() {
        return new AppConfig().httpClient();
    }
}
