package com.vishwas.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    Optional<ImportBatch> findByPeriodAndSource(String period, InvoiceRow.Source source);

    List<ImportBatch> findAllByOrderByPeriodAscSourceAsc();
}
