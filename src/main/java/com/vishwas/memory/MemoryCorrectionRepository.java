package com.vishwas.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryCorrectionRepository extends JpaRepository<MemoryCorrection, Long> {

    List<MemoryCorrection> findByVendorGstinOrderByCreatedAtDesc(String vendorGstin);

    List<MemoryCorrection> findAllByOrderByCreatedAtDesc();
}
