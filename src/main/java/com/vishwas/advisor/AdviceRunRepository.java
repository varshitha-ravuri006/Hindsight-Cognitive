package com.vishwas.advisor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdviceRunRepository extends JpaRepository<AdviceRun, Long> {

    Optional<AdviceRun> findFirstByPeriodOrderByStartedAtDesc(String period);
}
