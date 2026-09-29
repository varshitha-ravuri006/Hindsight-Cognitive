package com.vishwas.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByMismatchIdOrderByDueOnAsc(Long mismatchId);

    List<Reminder> findByDoneFalseOrderByDueOnAsc();
}
