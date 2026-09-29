package com.vishwas.ingest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Lenient parsing of the values Indian accounting exports contain (dd-MM-yyyy dates, "1,42,000.00" amounts). */
final class Values {

    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"));

    private Values() {
    }

    static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(raw.trim(), f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("unrecognised date '" + raw + "'");
    }

    static BigDecimal money(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO.setScale(2);
        }
        return new BigDecimal(raw.trim().replace(",", "").replace("₹", "")).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal money(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO.setScale(2);
        }
        return node.isNumber() ? node.decimalValue().setScale(2, RoundingMode.HALF_UP) : money(node.asText());
    }
}
