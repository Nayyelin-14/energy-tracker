package com.energytracker.alertservice.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.energytracker.alertservice.entity.Alert;

public interface AlertRepository extends JpaRepository<Alert, Long> {

        List<Alert> findByUserIdOrderByCreatedAtDesc(Long userId);

        List<Alert> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
}
