package com.vishwas.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryBatchRepository extends JpaRepository<MemoryBatch, Long> {

    List<MemoryBatch> findAllByOrderByStartedAtAsc();
}
