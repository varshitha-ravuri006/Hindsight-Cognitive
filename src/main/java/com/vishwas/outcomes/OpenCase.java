package com.vishwas.outcomes;

import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;

import java.math.BigDecimal;

/** The facts the outcome detector needs about one open mismatch (a pure view of {@link Mismatch}). */
public record OpenCase(long id, MismatchType type, String period, MismatchStatus status, String gstin,
                       String invoiceNoNorm, String invoiceNo, BigDecimal exposure, BigDecimal itcBooks) {

    public static OpenCase of(Mismatch m) {
        return new OpenCase(m.getId(), m.getType(), m.getPeriod(), m.getStatus(), m.getVendorGstin(), m.getInvoiceNoNorm(),
                m.invoiceNo(), m.getExposure(), m.getItcBooks());
    }
}
