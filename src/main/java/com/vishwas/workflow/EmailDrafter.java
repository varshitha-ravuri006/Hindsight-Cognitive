package com.vishwas.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.vishwas.advisor.CategoryPolicy;
import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.Vendor;
import com.vishwas.llm.GroqClient;
import com.vishwas.matching.Mismatch;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Drafts a polite, specific follow-up e-mail to a vendor about its open cases. Groq writes it; the draft is
 * validated (it must name every invoice and must not threaten or mention holding payment), retried once, and
 * falls back to a plain template. The accountant always reviews and sends it; Vishwas never sends e-mail.
 */
@Component
public class EmailDrafter {

    public record Draft(String to, String subject, String body, String source) {
    }

    static final String SYSTEM = """
            You draft short, polite, specific follow-up e-mails from an Indian company's accounts team to a supplier \
            about GST reconciliation differences. Name every invoice with its date, amount and what is wrong, and say \
            exactly what the supplier should do (file or amend GSTR-1, issue a credit note, confirm the GSTIN). Never \
            threaten, never mention holding, stopping or withholding payment, and never give tax advice. Reply with \
            JSON only: {"subject": "...", "body": "..."}.""";

    private final GroqClient groq;
    private final VishwasProperties props;

    public EmailDrafter(GroqClient groq, VishwasProperties props) {
        this.groq = groq;
        this.props = props;
    }

    public Draft draft(Vendor vendor, List<Mismatch> cases) {
        String facts = cases.stream().map(EmailDrafter::line).collect(Collectors.joining("\n"));
        String user = "Supplier: " + vendor.getLegalName() + " (GSTIN " + vendor.getGstin() + "), contact "
                + nz(vendor.getContactPerson()) + ".\nFrom: " + props.company().accountant() + ", Accounts, "
                + props.company().legalName() + " (GSTIN " + props.company().gstin() + ").\nOpen items:\n" + facts;
        Optional<JsonNode> reply = groq.completeJson(SYSTEM, user, j -> validate(j, cases));
        if (reply.isPresent()) {
            return new Draft(vendor.getEmail(), reply.get().path("subject").asText(), reply.get().path("body").asText(), "GROQ");
        }
        return template(vendor, cases);
    }

    static Optional<String> validate(JsonNode j, List<Mismatch> cases) {
        String subject = j.path("subject").asText("");
        String body = j.path("body").asText("");
        if (subject.isBlank() || body.isBlank()) {
            return Optional.of("subject and body are required");
        }
        for (Mismatch m : cases) {
            if (!body.contains(m.invoiceNo())) {
                return Optional.of("the body must name invoice " + m.invoiceNo());
            }
        }
        if (CategoryPolicy.mentionsPaymentAction(body)) {
            return Optional.of("do not mention holding or withholding payment");
        }
        return Optional.empty();
    }

    public Draft template(Vendor vendor, List<Mismatch> cases) {
        String subject = "GSTR-1 follow-up: " + cases.size() + " invoice" + (cases.size() == 1 ? "" : "s") + " for "
                + props.company().legalName();
        String body = "Dear " + (vendor.getContactPerson() == null ? "Sir/Madam" : vendor.getContactPerson()) + ",\n\n"
                + "While reconciling our GSTR-2B we found the following differences for " + vendor.getLegalName()
                + " (GSTIN " + vendor.getGstin() + "):\n\n"
                + cases.stream().map(m -> "- " + line(m)).collect(Collectors.joining("\n"))
                + "\n\nCould you please look into these and update your GSTR-1 (or share the credit note or corrected details) "
                + "so they reflect correctly in our GSTR-2B? Do let us know the date by which this will be done.\n\n"
                + "Thank you,\n" + props.company().accountant() + "\nAccounts, " + props.company().legalName();
        return new Draft(vendor.getEmail(), subject, body, "TEMPLATE");
    }

    static String line(Mismatch m) {
        String what = switch (m.getType()) {
            case MISSING_IN_2B -> "is not in our GSTR-2B; please file it in GSTR-1";
            case MISSING_IN_BOOKS -> "appears in GSTR-2B but we have not received the invoice; please send a copy";
            case AMOUNT_MISMATCH -> "is reported at a different amount (ITC " + Fmt.inr(m.getItcGstr2b()) + " in GSTR-2B vs "
                    + Fmt.inr(m.getItcBooks()) + " on the invoice); please amend or issue a credit note";
            case TAX_HEAD_MISMATCH -> "is reported with the wrong tax head (IGST vs CGST+SGST); please amend GSTR-1";
            case GSTIN_MISMATCH -> "is reported under a different GSTIN than the one on the invoice; please confirm";
            case INVOICE_NO_FORMAT -> "is reported as " + m.getInvoiceNoGstr2b() + "; please confirm it is the same invoice";
            case DATE_MISMATCH -> "is reported with date " + Fmt.day(m.getInvoiceDateGstr2b()) + "; please confirm the invoice date";
            case POSSIBLE_DUPLICATE -> "may have been issued twice; please confirm it was issued once";
        };
        return "Invoice " + m.invoiceNo() + " dated " + Fmt.day(m.getInvoiceDateBooks() != null ? m.getInvoiceDateBooks()
                : m.getInvoiceDateGstr2b()) + " (" + Fmt.month(m.getPeriod()) + ", GST " + Fmt.inr(m.getItcBooks() != null
                ? m.getItcBooks() : m.getItcGstr2b()) + ") " + what + ".";
    }

    private static String nz(String s) {
        return s == null ? "accounts" : s;
    }
}
