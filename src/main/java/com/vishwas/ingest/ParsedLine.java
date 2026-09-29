package com.vishwas.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A parsed input line: the matching-relevant {@link InvoiceRow} plus descriptive fields kept for the UI. */
public record ParsedLine(
        InvoiceRow row,
        BigDecimal rate,
        String hsn,
        String description,
        String placeOfSupply,
        String voucherNo,
        LocalDate bookingDate,
        LocalDate filedOn,
        String filingPeriod) {
}
