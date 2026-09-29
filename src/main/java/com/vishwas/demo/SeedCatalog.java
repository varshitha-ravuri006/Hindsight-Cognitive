package com.vishwas.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** Read-only access to the bundled sample data under {@code src/main/resources/seed}. */
@Component
public class SeedCatalog {

    public static final List<String> HISTORY_PERIODS = List.of("2026-04", "2026-05", "2026-06", "2026-07");
    public static final String LIVE_PERIOD = "2026-08";
    public static final String NEXT_PERIOD = "2026-09";
    public static final String BOOKS_FILE = "purchase-register.csv";
    public static final String GSTR2B_FILE = "gstr2b.json";

    private final ObjectMapper json = new ObjectMapper();

    public byte[] books(String period) {
        return bytes("seed/" + period + "/" + BOOKS_FILE);
    }

    public byte[] gstr2b(String period) {
        return bytes("seed/" + period + "/" + GSTR2B_FILE);
    }

    public byte[] letter(String name) {
        if (!name.matches("[a-z0-9-]+\\.pdf")) {
            throw new IllegalArgumentException("Bad letter name");
        }
        return bytes("seed/letters/" + name);
    }

    public boolean hasLetter(String name) {
        return name != null && name.matches("[a-z0-9-]+\\.pdf") && new ClassPathResource("seed/letters/" + name).exists();
    }

    public JsonNode vendors() {
        return tree("seed/vendors.json");
    }

    public JsonNode journal() {
        return tree("seed/journal.json");
    }

    private JsonNode tree(String path) {
        try {
            return json.readTree(bytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] bytes(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing seed file " + path, e);
        }
    }
}
