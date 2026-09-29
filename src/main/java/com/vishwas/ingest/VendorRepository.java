package com.vishwas.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VendorRepository extends JpaRepository<Vendor, String> {

    List<Vendor> findAllByOrderByLegalNameAsc();
}
