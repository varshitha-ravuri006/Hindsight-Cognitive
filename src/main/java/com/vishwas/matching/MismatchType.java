package com.vishwas.matching;

/** Every kind of difference the deterministic matcher reports, with the vendor dimension it speaks to. */
public enum MismatchType {
    MISSING_IN_2B(Dimension.TIMING, true, "In books, not in GSTR-2B"),
    MISSING_IN_BOOKS(Dimension.TIMING, false, "In GSTR-2B, not in books"),
    AMOUNT_MISMATCH(Dimension.AMOUNT_ACCURACY, true, "Amounts differ"),
    TAX_HEAD_MISMATCH(Dimension.TAX_HEAD_CORRECTNESS, true, "IGST vs CGST+SGST"),
    GSTIN_MISMATCH(Dimension.INVOICE_FORMAT, true, "Supplier GSTIN differs"),
    INVOICE_NO_FORMAT(Dimension.INVOICE_FORMAT, false, "Invoice number written differently"),
    DATE_MISMATCH(Dimension.INVOICE_FORMAT, false, "Invoice date differs"),
    POSSIBLE_DUPLICATE(Dimension.DUPLICATES, true, "Same invoice booked twice");

    private final Dimension dimension;
    private final boolean carriesItcRisk;
    private final String label;

    MismatchType(Dimension dimension, boolean carriesItcRisk, String label) {
        this.dimension = dimension;
        this.carriesItcRisk = carriesItcRisk;
        this.label = label;
    }

    public Dimension dimension() {
        return dimension;
    }

    /** True when an unresolved case of this type can cost input tax credit (so it can become "at risk"). */
    public boolean carriesItcRisk() {
        return carriesItcRisk;
    }

    public String label() {
        return label;
    }

    public String tag() {
        return "type:" + name();
    }
}
