package com.vishwas.matching;

/**
 * The dimensions on which Vishwas judges a vendor. There is deliberately no single trust score: a vendor can
 * be perfectly reliable on amounts and chronically late on filing.
 */
public enum Dimension {
    TIMING("Filing timing", "Do their invoices reach GSTR-2B in the right month?"),
    AMOUNT_ACCURACY("Amount accuracy", "Do the amounts they report match the invoice?"),
    TAX_HEAD_CORRECTNESS("Tax head correctness", "IGST vs CGST+SGST charged correctly?"),
    INVOICE_FORMAT("Invoice details", "Invoice number, date and GSTIN as booked?"),
    RESPONSIVENESS("Responsiveness", "Do they reply to follow-ups and keep promises?"),
    DUPLICATES("Duplicates", "Has the same invoice been booked more than once?");

    private final String label;
    private final String question;

    Dimension(String label, String question) {
        this.label = label;
        this.question = question;
    }

    public String label() {
        return label;
    }

    public String question() {
        return question;
    }

    public String tag() {
        return "dim:" + name();
    }
}
