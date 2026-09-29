package com.vishwas.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CloseSignoffRepository extends JpaRepository<CloseSignoff, Long> {

    Optional<CloseSignoff> findByPeriodAndItem(String period, String item);

    List<CloseSignoff> findByPeriod(String period);
}
