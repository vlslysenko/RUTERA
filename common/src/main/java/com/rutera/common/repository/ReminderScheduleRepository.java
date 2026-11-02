package com.rutera.common.repository;

import com.rutera.common.domain.ReminderSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface ReminderScheduleRepository extends JpaRepository<ReminderSchedule, Long> {

    Optional<ReminderSchedule> findFirstByDefaultScheduleTrueOrderByIdAsc();
}
