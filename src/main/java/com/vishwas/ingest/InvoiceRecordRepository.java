package com.vishwas.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InvoiceRecordRepository extends JpaRepository<InvoiceRecord, Long> {

    List<InvoiceRecord> findByPeriodAndSourceOrderByIdAsc(String period, InvoiceRow.Source source);

    List<InvoiceRecord> findBySupplierGstinOrderByInvoiceDateAsc(String gstin);

    long countByPeriodAndSource(String period, InvoiceRow.Source source);

    @Modifying
    @Query("delete from InvoiceRecord r where r.batchId = :batchId")
    void deleteByBatchId(Long batchId);
}
