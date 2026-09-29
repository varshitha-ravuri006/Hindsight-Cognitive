package com.vishwas.ingest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsersTest {

    @Test
    void purchaseRegisterHandlesQuotedCommasIndianDatesAndWarnsOnTaxHeads() {
        String csv = """
                voucher_no,booking_date,supplier_gstin,supplier_name,invoice_no,invoice_date,place_of_supply,hsn,description,taxable_value,gst_rate,igst,cgst,sgst
                PV-001,05-08-2026,36ABKFS2231Q1ZP,Sri Balaji Traders,SBT/2026/0118,04-08-2026,36,8536,"Switches, sockets and MCBs","93,000.00",18,0,8370.00,8370.00
                PV-002,06-08-2026,36AAPFM8841E1ZX,Metro Logistics,MLS/1131,06-08-2026,36,9965,Freight,40000,5,2000,0,0
                PV-003,06-08-2026,36XXXXX0000X1Z0,Bad Vendor,B-1,06-08-2026,36,,Thing,100,18,0,9,9
                """;
        ParseResult r = new PurchaseRegisterParser().parse(csv.getBytes(StandardCharsets.UTF_8), "2026-08", "36");

        assertThat(r.lines()).hasSize(3);
        var first = r.lines().get(0);
        assertThat(first.description()).isEqualTo("Switches, sockets and MCBs");
        assertThat(first.row().taxableValue()).isEqualByComparingTo("93000");
        assertThat(first.row().invoiceDate()).hasToString("2026-08-04");
        assertThat(first.row().itc()).isEqualByComparingTo("16740");
        assertThat(r.warnings()).anyMatch(w -> w.contains("MLS/1131") && w.contains("IGST"));
        assertThat(r.warnings()).anyMatch(w -> w.contains("36XXXXX0000X1Z0"));
    }

    @Test
    void purchaseRegisterWithoutRequiredColumnsIsRejected() {
        assertThatThrownBy(() -> new PurchaseRegisterParser().parse("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), "2026-08", "36"))
                .isInstanceOf(InvalidFileException.class).hasMessageContaining("supplier_gstin");
    }

    @Test
    void gstr2bParsesInvoicesAmendmentsAndCreditNotes() {
        String json = """
                {"format":"vishwas-gstr2b-simplified/1","recipient_gstin":"36AAGCD4821M1ZG","return_period":"2026-09",
                 "b2b":[{"supplier_gstin":"36ABKFS2231Q1ZP","supplier_name":"Sri Balaji Traders","gstr1_period":"2026-09","gstr1_filed_on":"2026-10-11",
                         "invoices":[{"invoice_no":"SBT/2026/0118","invoice_date":"2026-08-04","taxable_value":93000,"rate":18,"igst":0,"cgst":8370,"sgst":8370}]}],
                 "b2ba":[{"supplier_gstin":"36AAPFM8841E1ZX","supplier_name":"Metro Logistics","gstr1_period":"2026-09",
                         "invoices":[{"original_invoice_no":"MLS/1131","original_invoice_date":"2026-08-06","invoice_no":"MLS/1131","invoice_date":"2026-08-06","taxable_value":40000,"rate":5,"igst":0,"cgst":1000,"sgst":1000}]}],
                 "cdnr":[{"supplier_gstin":"29AAJCN6620H1Z8","supplier_name":"Nandi Electricals","gstr1_period":"2026-09",
                         "notes":[{"note_type":"C","note_no":"NEL/CN/044","note_date":"2026-09-12","original_invoice_no":"NEL/7851","taxable_value":9000,"rate":18,"igst":1620,"cgst":0,"sgst":0},
                                  {"note_type":"D","note_no":"NEL/DN/002","note_date":"2026-09-12","taxable_value":100,"igst":18}]}]}
                """;
        ParseResult r = new Gstr2bParser().parse(json.getBytes(StandardCharsets.UTF_8), "2026-09", "36AAGCD4821M1ZG");

        assertThat(r.lines()).extracting(l -> l.row().kind())
                .containsExactly(InvoiceRow.Kind.INVOICE, InvoiceRow.Kind.AMENDMENT, InvoiceRow.Kind.CREDIT_NOTE);
        assertThat(r.lines().get(1).row().originalInvoiceNo()).isEqualTo("MLS/1131");
        assertThat(r.lines().get(2).row().normalisedOriginalNo()).isEqualTo("7851");
        assertThat(r.lines().get(0).filedOn()).hasToString("2026-10-11");
        assertThat(r.warnings()).anyMatch(w -> w.contains("Debit note"));
    }

    @Test
    void gstr2bForTheWrongPeriodOrFormatIsRejected() {
        String json = "{\"format\":\"vishwas-gstr2b-simplified/1\",\"return_period\":\"2026-07\"}";
        assertThatThrownBy(() -> new Gstr2bParser().parse(json.getBytes(StandardCharsets.UTF_8), "2026-08", null))
                .isInstanceOf(InvalidFileException.class).hasMessageContaining("2026-07");
        assertThatThrownBy(() -> new Gstr2bParser().parse("{\"data\":{}}".getBytes(StandardCharsets.UTF_8), "2026-08", null))
                .isInstanceOf(InvalidFileException.class).hasMessageContaining("simplified");
    }
}
