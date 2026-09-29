package com.vishwas.outcomes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Judges vendor promises from the data, never from what the vendor says afterwards. A promise ("will file
 * by 20 July") is due once a GSTR-2B generated after the promised date has been processed: it is KEPT if every
 * invoice it covered is resolved by then, otherwise BROKEN.
 */
public final class PromiseTracker {

    private PromiseTracker() {
    }

    /**
     * @param promiseBy        the promised date
     * @param invoices         invoice numbers the promise covered
     * @param gstr2bGenerated  generation date of the GSTR-2B just processed
     * @param resolved         invoice number -> resolved (terminal outcome) as of that GSTR-2B
     * @return empty while the promise is not yet due
     */
    public static Optional<VendorCommunication.PromiseStatus> judge(LocalDate promiseBy, List<String> invoices,
                                                                   LocalDate gstr2bGenerated, Map<String, Boolean> resolved) {
        if (promiseBy == null || !gstr2bGenerated.isAfter(promiseBy)) {
            return Optional.empty();
        }
        boolean allResolved = !invoices.isEmpty() && invoices.stream().allMatch(i -> resolved.getOrDefault(i, false));
        return Optional.of(allResolved ? VendorCommunication.PromiseStatus.KEPT : VendorCommunication.PromiseStatus.BROKEN);
    }
}
