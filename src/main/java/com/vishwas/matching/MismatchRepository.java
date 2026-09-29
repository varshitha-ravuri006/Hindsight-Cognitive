package com.vishwas.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface MismatchRepository extends JpaRepository<Mismatch, Long> {

    List<Mismatch> findByPeriodOrderByVendorNameAscIdAsc(String period);

    List<Mismatch> findByStatusInOrderByPeriodAscIdAsc(Collection<MismatchStatus> statuses);

    List<Mismatch> findByVendorGstinOrderByDetectedAtAscIdAsc(String gstin);

    List<Mismatch> findByVendorGstinAndDimensionOrderByDetectedAtAsc(String gstin, Dimension dimension);

    List<Mismatch> findByVerdictPeriod(String period);

    List<Mismatch> findAllByOrderByDetectedAtAscIdAsc();

    long countByPeriod(String period);

    @org.springframework.data.jpa.repository.Query("select m.evidenceRecordId from Mismatch m where m.evidenceRecordId is not null")
    List<Long> findEvidenceRecordIds();
}
