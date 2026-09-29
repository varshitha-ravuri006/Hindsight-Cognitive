package com.vishwas.outcomes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountantActionRepository extends JpaRepository<AccountantAction, Long> {

    List<AccountantAction> findByVendorGstinOrderByOccurredAtAsc(String gstin);

    List<AccountantAction> findByMismatchIdOrderByOccurredAtAsc(Long mismatchId);
}
