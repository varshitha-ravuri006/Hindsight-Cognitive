# Simplified GSTR-2B format (`vishwas-gstr2b-simplified/1`)

> **This is not the GST portal schema.** It is a deliberately simplified, invoice-level JSON that carries
> exactly what reconciliation needs, so sample data stays readable and tests stay small. Mapping the
> portal's GSTR-2B JSON (with its nested `docdata`, per-rate items and `dd-mm-yyyy` dates) into this shape
> is a separate adapter on the roadmap.

GSTR-2B for a return period is generated on the 14th of the following month. It lists inward supplies
reported by suppliers in their GSTR-1.

```json
{
  "format": "vishwas-gstr2b-simplified/1",
  "disclaimer": "Simplified GSTR-2B for Vishwas. Not the GST portal schema.",
  "recipient_gstin": "36AAGCD4821M1ZG",
  "return_period": "2026-08",
  "generated_on": "2026-09-14",
  "b2b": [
    {
      "supplier_gstin": "36ABKFS2231Q1ZP",
      "supplier_name": "Sri Balaji Traders",
      "gstr1_period": "2026-08",
      "gstr1_filed_on": "2026-09-11",
      "invoices": [
        { "invoice_no": "SBT/2026/0112", "invoice_date": "2026-08-04", "place_of_supply": "36",
          "taxable_value": 93000.00, "rate": 18, "igst": 0, "cgst": 8370.00, "sgst": 8370.00 }
      ]
    }
  ],
  "b2ba": [
    {
      "supplier_gstin": "36AAPFM8841E1ZX", "supplier_name": "Metro Logistics",
      "gstr1_period": "2026-09", "gstr1_filed_on": "2026-10-10",
      "invoices": [
        { "original_invoice_no": "MLS/1131", "original_invoice_date": "2026-08-06",
          "invoice_no": "MLS/1131", "invoice_date": "2026-08-06", "place_of_supply": "36",
          "taxable_value": 40000.00, "rate": 5, "igst": 0, "cgst": 1000.00, "sgst": 1000.00 }
      ]
    }
  ],
  "cdnr": [
    {
      "supplier_gstin": "29AAJCN6620H1Z8", "supplier_name": "Nandi Electricals Pvt Ltd",
      "gstr1_period": "2026-09", "gstr1_filed_on": "2026-10-11",
      "notes": [
        { "note_type": "C", "note_no": "NEL/CN/044", "note_date": "2026-09-12",
          "original_invoice_no": "NEL/7851", "taxable_value": 9000.00, "rate": 18,
          "igst": 1620.00, "cgst": 0, "sgst": 0 }
      ]
    }
  ]
}
```

| Section | Meaning | Used by |
|---------|---------|---------|
| `b2b`   | Invoices reported in this period. An invoice dated in an earlier month here means the supplier filed late. | matcher; outcome detector (late filing) |
| `b2ba`  | Amendments of invoices reported earlier (`original_invoice_no` identifies the invoice). | outcome detector (AMENDED) |
| `cdnr`  | Credit notes (`note_type` `C`) against an invoice. Debit notes are ignored with a warning. | outcome detector (CREDIT_NOTE) |

Dates are ISO `yyyy-mm-dd`. Amounts are rupees with two decimals. Intra-state supplies carry CGST + SGST,
inter-state supplies IGST.

## Purchase register CSV

One row per invoice; column order does not matter. Required: `supplier_gstin, supplier_name, invoice_no,
invoice_date, taxable_value, igst, cgst, sgst`. Optional: `voucher_no, booking_date, place_of_supply, hsn,
description, gst_rate`. Dates `dd-mm-yyyy` or ISO; amounts may use Indian digit grouping in quotes
(`"1,42,000.00"`).
